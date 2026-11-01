"use strict";

/**
 * One-time (safe to repeat): label every existing login as owner of its own
 * company, the way logins worked before multi-user (companyId == uid).
 * Sets the custom claims { companyId: uid, role: "owner" } and adds the login
 * to companies/{uid}/members. Logins that already have a company are skipped.
 *
 *   node scripts/labelOwners.js plan      List every login and what would happen; writes nothing
 *   node scripts/labelOwners.js apply     Do it
 *
 * Options:
 *   --key <file>   Service account key (default C:\Users\Microsoft\firebase-keys\tagscout-in-admin.json)
 *
 * Uses the built functions code: run `npm run build` in functions/ first.
 * New labels reach a phone or browser the next time its login refreshes
 * (within an hour, or straight away after signing out and in).
 */

const fs = require("fs");
const { initializeApp, cert } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const { makeOwnerOfOwnCompany, readMemberClaims } = require("../lib/members");

function parseArgs(argv) {
  const args = { mode: argv[0], key: "C:\\Users\\Microsoft\\firebase-keys\\tagscout-in-admin.json" };
  for (let i = 1; i < argv.length; i++) {
    if (argv[i] === "--key") args.key = argv[++i];
    else throw new Error(`Unknown option: ${argv[i]}`);
  }
  if (!["plan", "apply"].includes(args.mode)) throw new Error("First argument must be plan or apply.");
  return args;
}

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

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!fs.existsSync(args.key)) throw new Error(`Key not found: ${args.key}`);
  const key = JSON.parse(fs.readFileSync(args.key, "utf8"));
  initializeApp({ credential: cert(key), projectId: key.project_id });
  const auth = getAuth();
  const db = getFirestore();
  console.log(`Project: ${key.project_id}\n`);

  const users = await listAllUsers(auth);
  let toLabel = 0;
  let labelled = 0;
  for (const user of users) {
    const current = readMemberClaims(user.customClaims);
    const [profile, settings] = await Promise.all([
      db.collection("users").doc(user.uid).get(),
      db.collection("companies").doc(user.uid).collection("settings").doc("config").get(),
    ]);
    const who = (user.email || user.phoneNumber || user.uid).padEnd(36);
    const company = settings.exists ? `company preset: ${settings.get("template") || "?"}` : "no company settings";

    if (current) {
      const same = current.companyId === user.uid ? "own company" : `company ${current.companyId}`;
      console.log(`  ${who} already ${current.role} of ${same}`);
      continue;
    }
    toLabel++;
    if (args.mode === "plan") {
      console.log(`  ${who} will become owner of its own company (${company})`);
      continue;
    }
    const result = await makeOwnerOfOwnCompany(auth, db, user.uid, profile.exists ? profile.data() : {});
    if (result === "set") labelled++;
    console.log(`  ${who} ${result === "set" ? "labelled owner" : "already labelled"} (${company})`);
  }

  console.log(
    args.mode === "plan"
      ? `\n${users.length} logins, ${toLabel} to label. Nothing written. Run with apply to do it.`
      : `\n${users.length} logins, ${labelled} labelled now.`,
  );
}

main().catch((e) => {
  console.error(`\nError: ${e.message}`);
  process.exitCode = 1;
});
