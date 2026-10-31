"use strict";

/**
 * Copy TagScout from one Firebase project to another, from your PC with admin rights.
 * Built for the move from tagscout-a0cc3 (US) to tagscout-in (Mumbai).
 *
 *   node scripts/migrateProject.js plan                 Count everything in both projects; writes nothing
 *   node scripts/migrateProject.js copy                 Copy logins, Firestore and Storage files
 *   node scripts/migrateProject.js copy --only auth     ...or just one part: auth | firestore | storage
 *   node scripts/migrateProject.js verify               Compare counts after a copy
 *
 * Options (defaults suit Yash's PC):
 *   --keys <dir>          Folder with <project>-admin.json keys and <src>-hash.txt
 *                         (default C:\Users\Microsoft\firebase-keys)
 *   --src <projectId>     default tagscout-a0cc3
 *   --dst <projectId>     default tagscout-in
 *   --src-bucket <name>   default <src>.firebasestorage.app
 *   --dst-bucket <name>   default <dst>.firebasestorage.app
 *   --yes                 Skip the "type COPY" confirmation
 *
 * What it does
 *   Logins: same uid, email, phone, verified flag, MFA phone and password (so companyId == uid
 *     still holds and nobody resets anything). Needs <src>-hash.txt: the "Password hash
 *     parameters" block from the old project's Authentication > Users menu, pasted as-is.
 *   Firestore: every collection and subcollection, same document IDs. Download links that
 *     point at the old Storage bucket are rewritten to the new bucket.
 *   Storage: every file, with its content type and download token, so links keep working.
 *
 * Safe to re-run: it overwrites what it copied before. It never deletes anything in either
 * project, so a document deleted in the old project after a copy stays in the new one.
 *
 * IMPORTANT: run the Firestore copy BEFORE deploying functions to the new project.
 * Otherwise every copied movement re-triggers updateSkuAggregate (double-counting stock)
 * and every copied user re-triggers setUpNewCompany.
 */

const fs = require("fs");
const path = require("path");
const readline = require("readline");
const { initializeApp, cert } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, DocumentReference, Timestamp, GeoPoint } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");

// ── Arguments ──────────────────────────────────────────────────────

function parseArgs(argv) {
  const args = {
    mode: argv[0],
    keys: "C:\\Users\\Microsoft\\firebase-keys",
    src: "tagscout-a0cc3",
    dst: "tagscout-in",
    only: null,
    yes: false,
  };
  for (let i = 1; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case "--keys": args.keys = next(); break;
      case "--src": args.src = next(); break;
      case "--dst": args.dst = next(); break;
      case "--src-bucket": args.srcBucket = next(); break;
      case "--dst-bucket": args.dstBucket = next(); break;
      case "--only": args.only = next(); break;
      case "--yes": args.yes = true; break;
      default: throw new Error(`Unknown option: ${a}`);
    }
  }
  if (!["plan", "copy", "verify"].includes(args.mode)) {
    throw new Error("First argument must be plan, copy or verify.");
  }
  if (args.only && !["auth", "firestore", "storage"].includes(args.only)) {
    throw new Error("--only must be auth, firestore or storage.");
  }
  if (args.src === args.dst) throw new Error("--src and --dst are the same project.");
  args.srcBucket = args.srcBucket || `${args.src}.firebasestorage.app`;
  args.dstBucket = args.dstBucket || `${args.dst}.firebasestorage.app`;
  return args;
}

// ── Setup ──────────────────────────────────────────────────────────

function loadKey(keysDir, projectId) {
  const file = path.join(keysDir, `${projectId}-admin.json`);
  if (!fs.existsSync(file)) throw new Error(`Key not found: ${file}`);
  const key = JSON.parse(fs.readFileSync(file, "utf8"));
  if (key.project_id !== projectId) {
    throw new Error(`${file} belongs to project "${key.project_id}", expected "${projectId}".`);
  }
  return key;
}

function openProject(name, keysDir, projectId, bucket) {
  const app = initializeApp({ credential: cert(loadKey(keysDir, projectId)), projectId }, name);
  return {
    projectId,
    bucketName: bucket,
    auth: getAuth(app),
    db: getFirestore(app),
    bucket: getStorage(app).bucket(bucket),
  };
}

/** Reads the "Password hash parameters" block pasted from the Firebase console. */
function parseHashConfig(text) {
  const get = (field) => {
    const m = new RegExp(`${field}\\s*:\\s*"?([^",\\n}]+)"?`).exec(text);
    return m ? m[1].trim() : undefined;
  };
  const algorithm = get("algorithm");
  const signerKey = get("base64_signer_key");
  const saltSeparator = get("base64_salt_separator");
  const rounds = Number(get("rounds"));
  const memCost = Number(get("mem_cost"));
  if (algorithm !== "SCRYPT" || !signerKey || saltSeparator === undefined || !rounds || !memCost) {
    throw new Error("Couldn't read the hash parameters. Paste the whole hash_config { ... } block into the file.");
  }
  return {
    algorithm: "SCRYPT",
    key: Buffer.from(signerKey, "base64"),
    saltSeparator: Buffer.from(saltSeparator, "base64"),
    rounds,
    memoryCost: memCost,
  };
}

function loadHashConfig(keysDir, projectId) {
  const file = path.join(keysDir, `${projectId}-hash.txt`);
  if (!fs.existsSync(file)) throw new Error(`Hash parameters not found: ${file}`);
  return parseHashConfig(fs.readFileSync(file, "utf8"));
}

/** Runs [work] over [items] with at most [limit] running at once. */
async function pool(items, limit, work) {
  let index = 0;
  const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (index < items.length) {
      const item = items[index++];
      await work(item);
    }
  });
  await Promise.all(runners);
}

function chunks(list, size) {
  const out = [];
  for (let i = 0; i < list.length; i += size) out.push(list.slice(i, i + size));
  return out;
}

function confirm(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => rl.question(question, (answer) => {
    rl.close();
    resolve(answer.trim());
  }));
}

function stripUndefined(obj) {
  return Object.fromEntries(Object.entries(obj).filter(([, v]) => v !== undefined && v !== null));
}

// ── Auth ───────────────────────────────────────────────────────────

async function listAllUsers(auth) {
  const users = [];
  let pageToken;
  do {
    const page = await auth.listUsers(1000, pageToken);
    users.push(...page.users);
    pageToken = page.pageToken;
  } while (pageToken);
  return users;
}

function hasPasswordLogin(user) {
  return user.providerData.some((p) => p.providerId === "password");
}

function toImportRecord(user) {
  const record = stripUndefined({
    uid: user.uid,
    email: user.email,
    emailVerified: user.emailVerified,
    displayName: user.displayName,
    photoURL: user.photoURL,
    phoneNumber: user.phoneNumber,
    disabled: user.disabled,
    customClaims: user.customClaims && Object.keys(user.customClaims).length ? user.customClaims : undefined,
    metadata: stripUndefined({
      creationTime: user.metadata.creationTime,
      lastSignInTime: user.metadata.lastSignInTime,
    }),
  });

  if (user.passwordHash) {
    record.passwordHash = Buffer.from(user.passwordHash, "base64");
    record.passwordSalt = Buffer.from(user.passwordSalt || "", "base64");
  }

  // Sign-in providers other than email/password and phone (those come from the fields above).
  const others = user.providerData
    .filter((p) => p.providerId !== "password" && p.providerId !== "phone")
    .map((p) => stripUndefined({
      uid: p.uid,
      providerId: p.providerId,
      email: p.email,
      displayName: p.displayName,
      photoURL: p.photoURL,
    }));
  if (others.length) record.providerData = others;

  const factors = user.multiFactor?.enrolledFactors ?? [];
  if (factors.length) {
    record.multiFactor = {
      enrolledFactors: factors.map((f) => stripUndefined({
        uid: f.uid,
        factorId: f.factorId,
        displayName: f.displayName,
        enrollmentTime: f.enrollmentTime,
        phoneNumber: f.phoneNumber,
      })),
    };
  }
  return record;
}

async function copyAuth(src, dst, hash) {
  const users = await listAllUsers(src.auth);
  const missingHash = users.filter((u) => hasPasswordLogin(u) && !u.passwordHash);
  if (missingHash.length) {
    throw new Error(
      `${missingHash.length} login(s) came back without a password hash, so their passwords can't be copied. ` +
      "Check that the old project's key is the Firebase Admin SDK service account.",
    );
  }

  let imported = 0;
  const failures = [];
  for (const batch of chunks(users, 1000)) {
    const result = await dst.auth.importUsers(batch.map(toImportRecord), { hash });
    imported += result.successCount;
    result.errors.forEach((e) => failures.push(`${batch[e.index].email || batch[e.index].uid}: ${e.error.message}`));
  }
  const mfa = users.filter((u) => (u.multiFactor?.enrolledFactors ?? []).length).length;
  console.log(`  Logins: ${imported} of ${users.length} copied (${mfa} with SMS verification).`);
  failures.forEach((f) => console.log(`  FAILED ${f}`));
  return failures.length === 0;
}

// ── Firestore ──────────────────────────────────────────────────────

/** Visits every collection under [parent] (a Firestore or a DocumentReference), depth-first. */
async function walkCollections(parent, visit) {
  const collections = await parent.listCollections();
  for (const col of collections) {
    const refs = await col.listDocuments(); // includes "empty" parents that only hold subcollections
    await visit(col, refs);
    await pool(refs, 16, (ref) => walkCollections(ref, visit));
  }
}

function makeTransform(dstDb, oldBucket, newBucket, stats) {
  const transform = (value) => {
    if (value === null || value === undefined) return value;
    if (typeof value === "string") {
      if (!value.includes(oldBucket)) return value;
      stats.linksRewritten++;
      return value.split(oldBucket).join(newBucket);
    }
    if (Array.isArray(value)) return value.map(transform);
    if (value instanceof DocumentReference) return dstDb.doc(value.path);
    if (value instanceof Timestamp || value instanceof GeoPoint || Buffer.isBuffer(value) || value instanceof Uint8Array) {
      return value;
    }
    if (typeof value === "object" && Object.getPrototypeOf(value) === Object.prototype) {
      return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, transform(v)]));
    }
    return value; // other Firestore types (e.g. vectors) pass through unchanged
  };
  return transform;
}

async function copyFirestore(src, dst) {
  const stats = { docs: 0, linksRewritten: 0, byRoot: {} };
  const transform = makeTransform(dst.db, src.bucketName, dst.bucketName, stats);
  const writer = dst.db.bulkWriter();
  let writeErrors = 0;
  writer.onWriteError((err) => {
    if (err.failedAttempts < 5) return true;
    writeErrors++;
    console.log(`  FAILED ${err.documentRef.path}: ${err.message}`);
    return false;
  });

  let lastLog = Date.now();
  await walkCollections(src.db, async (col, refs) => {
    const root = col.path.split("/")[0];
    for (const batch of chunks(refs, 300)) {
      const snaps = await src.db.getAll(...batch);
      for (const snap of snaps) {
        if (!snap.exists) continue;
        writer.set(dst.db.doc(snap.ref.path), transform(snap.data()));
        stats.docs++;
        stats.byRoot[root] = (stats.byRoot[root] || 0) + 1;
      }
    }
    if (Date.now() - lastLog > 3000) {
      console.log(`  ...${stats.docs} documents so far (${col.path})`);
      lastLog = Date.now();
    }
  });
  await writer.close();

  Object.entries(stats.byRoot).sort().forEach(([root, n]) => console.log(`  ${root}: ${n} documents`));
  console.log(`  Firestore: ${stats.docs} documents copied, ${stats.linksRewritten} file links pointed at the new bucket.`);
  if (writeErrors) console.log(`  ${writeErrors} document(s) failed to write.`);
  return writeErrors === 0;
}

/** Document counts per top-level collection, including every subcollection beneath it. */
async function countFirestore(db) {
  const byRoot = {};
  await walkCollections(db, async (col) => {
    const root = col.path.split("/")[0];
    const n = (await col.count().get()).data().count;
    byRoot[root] = (byRoot[root] || 0) + n;
  });
  return byRoot;
}

// ── Storage ────────────────────────────────────────────────────────

async function copyStorage(src, dst) {
  const [files] = await src.bucket.getFiles();
  let copied = 0;
  let bytes = 0;
  const failures = [];
  await pool(files, 8, async (file) => {
    try {
      const [data] = await file.download({ decompress: false });
      const md = file.metadata;
      await dst.bucket.file(file.name).save(data, {
        resumable: false,
        metadata: stripUndefined({
          contentType: md.contentType,
          cacheControl: md.cacheControl,
          contentDisposition: md.contentDisposition,
          contentEncoding: md.contentEncoding,
          contentLanguage: md.contentLanguage,
          metadata: md.metadata, // keeps firebaseStorageDownloadTokens, so download links stay valid
        }),
      });
      copied++;
      bytes += data.length;
    } catch (e) {
      failures.push(`${file.name}: ${e.message}`);
    }
  });
  console.log(`  Storage: ${copied} of ${files.length} files copied (${(bytes / 1048576).toFixed(1)} MB).`);
  failures.forEach((f) => console.log(`  FAILED ${f}`));
  return failures.length === 0;
}

async function countFiles(bucket) {
  const [files] = await bucket.getFiles();
  return files.length;
}

// ── Modes ──────────────────────────────────────────────────────────

async function counts(project) {
  const [users, firestore, files] = await Promise.all([
    listAllUsers(project.auth).then((u) => u.length),
    countFirestore(project.db),
    countFiles(project.bucket),
  ]);
  return { users, firestore, files };
}

function printComparison(a, b, labelA, labelB) {
  const roots = [...new Set([...Object.keys(a.firestore), ...Object.keys(b.firestore)])].sort();
  const rows = [
    ["Logins", a.users, b.users],
    ...roots.map((r) => [`Firestore: ${r}`, a.firestore[r] || 0, b.firestore[r] || 0]),
    ["Storage files", a.files, b.files],
  ];
  const width = Math.max(...rows.map((r) => r[0].length), 10);
  console.log(`\n  ${"".padEnd(width)}  ${labelA.padStart(14)}  ${labelB.padStart(14)}`);
  let allMatch = true;
  for (const [name, x, y] of rows) {
    const flag = x === y ? "" : "   <-- differs";
    if (x !== y) allMatch = false;
    console.log(`  ${name.padEnd(width)}  ${String(x).padStart(14)}  ${String(y).padStart(14)}${flag}`);
  }
  return allMatch;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const src = openProject("src", args.keys, args.src, args.srcBucket);
  const dst = openProject("dst", args.keys, args.dst, args.dstBucket);
  console.log(`From ${args.src} (bucket ${args.srcBucket})`);
  console.log(`To   ${args.dst} (bucket ${args.dstBucket})`);

  if (args.mode === "plan" || args.mode === "verify") {
    console.log("\nCounting (this reads every collection, writes nothing)...");
    const [a, b] = await Promise.all([counts(src), counts(dst)]);
    const same = printComparison(a, b, args.src, args.dst);
    if (args.mode === "verify") {
      console.log(same ? "\nEverything matches." : "\nSome counts differ (see above).");
      process.exitCode = same ? 0 : 1;
    }
    return;
  }

  const parts = args.only ? [args.only] : ["auth", "firestore", "storage"];
  const hash = parts.includes("auth") ? loadHashConfig(args.keys, args.src) : null;

  console.log(`\nAbout to copy: ${parts.join(", ")}. Existing copies in ${args.dst} are overwritten; nothing is deleted.`);
  if (parts.includes("firestore")) {
    console.log("Functions must NOT be deployed to the new project yet (copied documents would trigger them).");
  }
  if (!args.yes && (await confirm(`Type COPY to continue: `)) !== "COPY") {
    console.log("Stopped. Nothing was copied.");
    return;
  }

  let ok = true;
  if (parts.includes("auth")) {
    console.log("\nCopying logins...");
    ok = (await copyAuth(src, dst, hash)) && ok;
  }
  if (parts.includes("firestore")) {
    console.log("\nCopying Firestore...");
    ok = (await copyFirestore(src, dst)) && ok;
  }
  if (parts.includes("storage")) {
    console.log("\nCopying Storage files...");
    ok = (await copyStorage(src, dst)) && ok;
  }
  console.log(ok ? "\nDone. Run: node scripts/migrateProject.js verify" : "\nFinished with errors (see FAILED lines above).");
  process.exitCode = ok ? 0 : 1;
}

if (require.main === module) {
  main().catch((e) => {
    console.error(`\nError: ${e.message}`);
    process.exitCode = 1;
  });
}

module.exports = { parseArgs, parseHashConfig, toImportRecord, makeTransform };
