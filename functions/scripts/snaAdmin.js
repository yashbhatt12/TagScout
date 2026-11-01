"use strict";

/**
 * SNA admin: create companies and manage their people, from your PC.
 * Self sign-up is switched off, so this script (and each owner's Team page
 * on the dashboard) is how logins get created.
 *
 *   node scripts/snaAdmin.js create-company --email owner@shop.in --name "Owner Name"
 *        --company "Shop Name" --preset jewellery [--mobile 98xxxxxxxx] [--types general]
 *   node scripts/snaAdmin.js add-member --company owner@shop.in --email staff@shop.in
 *        --name "Staff Name" --role staff|manager
 *   node scripts/snaAdmin.js resend-invite --email someone@shop.in [--link]
 *   node scripts/snaAdmin.js team --company owner@shop.in
 *   node scripts/snaAdmin.js companies
 *   node scripts/snaAdmin.js sync-company-names
 *
 * --company takes the owner's email or the company id.
 * sync-company-names copies each company's name from its owner's profile
 * onto the company record (companies/{id}.name), where everyone in the
 * company can read it. Needed once for companies created before team logins.
 * create-company and add-member email the person Firebase's "set your
 * password" link. Add --no-email to skip that (send it later with
 * resend-invite). resend-invite --link prints the link instead of emailing
 * it, for WhatsApp; it works once and expires after a while (about an hour).
 *
 * Options for every command:
 *   --key <file>      Service account key (default C:\Users\Microsoft\firebase-keys\tagscout-in-admin.json)
 *   --api-key <key>   Web API key, for sending emails (default: read from web/.env.local)
 *   --yes             Don't ask for confirmation
 *
 * Uses the built functions code: run `npm run build` in functions/ first.
 */

const fs = require("fs");
const path = require("path");
const readline = require("readline");
const { initializeApp, cert, getApps } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore } = require("firebase-admin/firestore");
const core = require("../lib/teamCore");
const { readMemberClaims } = require("../lib/members");
const { PRESETS } = require("../lib/templates");

const DASHBOARD = "https://tagscout-in.web.app/";
const ACTOR = "sna-admin";

// ── Arguments ──────────────────────────────────────────────────────

const FLAGS = new Set(["--yes", "--link", "--no-email"]);

function parseArgs(argv) {
  const args = { command: argv[0], key: "C:\\Users\\Microsoft\\firebase-keys\\tagscout-in-admin.json" };
  for (let i = 1; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith("--")) throw new Error(`Unexpected '${a}'`);
    const name = a.slice(2).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    if (FLAGS.has(a)) args[name] = true;
    else {
      const value = argv[++i];
      if (value === undefined || value.startsWith("--")) throw new Error(`${a} needs a value`);
      args[name] = value;
    }
  }
  return args;
}

function need(args, ...names) {
  const missing = names.filter((n) => !args[n]);
  if (missing.length) {
    throw new Error(`Missing ${missing.map((n) => "--" + n.replace(/[A-Z]/g, (c) => "-" + c.toLowerCase())).join(", ")}`);
  }
}

function confirm(args, question) {
  if (args.yes) return Promise.resolve(true);
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => rl.question(`${question} Type YES to continue: `, (a) => {
    rl.close();
    resolve(a.trim() === "YES");
  }));
}

// ── Firebase ───────────────────────────────────────────────────────

function open(args) {
  if (!fs.existsSync(args.key)) throw new Error(`Key not found: ${args.key}`);
  const key = JSON.parse(fs.readFileSync(args.key, "utf8"));
  if (getApps().length === 0) initializeApp({ credential: cert(key), projectId: key.project_id });
  return { auth: getAuth(), db: getFirestore(), projectId: key.project_id };
}

function webApiKey(args) {
  if (args.apiKey) return args.apiKey;
  const envFile = path.resolve(__dirname, "..", "..", "web", ".env.local");
  if (fs.existsSync(envFile)) {
    const m = /^VITE_FIREBASE_API_KEY=(.+)$/m.exec(fs.readFileSync(envFile, "utf8"));
    if (m) return m[1].trim();
  }
  throw new Error("No web API key: pass --api-key, or keep web/.env.local in the repo.");
}

/** Asks Firebase to email the "set your password" link (same email as Forgot password). */
async function sendInviteEmail(args, email) {
  const res = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=${webApiKey(args)}`, {
    method: "POST",
    // The Referer satisfies a website restriction on the web API key, if one is set.
    headers: { "Content-Type": "application/json", Referer: DASHBOARD },
    body: JSON.stringify({ requestType: "PASSWORD_RESET", email, continueUrl: DASHBOARD }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(`Login created, but the email wasn't sent (${body.error?.message || res.status}). Run resend-invite.`);
  }
}

async function inviteOrSkip(args, auth, email) {
  if (args.noEmail) {
    console.log(`  No email sent. Later: node scripts/snaAdmin.js resend-invite --email ${email}`);
    return;
  }
  await sendInviteEmail(args, email);
  console.log(`  Emailed ${email} the link to set their password.`);
}

/** --company as owner email or company id → company id. */
async function companyIdOf(auth, db, value) {
  if (value.includes("@")) {
    const user = await auth.getUserByEmail(value.trim().toLowerCase()).catch(() => null);
    if (!user) throw new Error(`No login with email ${value}.`);
    const claims = readMemberClaims(user.customClaims);
    if (!claims) throw new Error(`${value} isn't labelled with a company yet (run labelOwners.js).`);
    return claims.companyId;
  }
  const settings = await db.collection("companies").doc(value).collection("settings").doc("config").get();
  if (!settings.exists) throw new Error(`No company with id ${value}.`);
  return value;
}

async function companyName(db, companyId) {
  const company = await db.collection("companies").doc(companyId).get();
  if (company.exists && company.get("name")) return company.get("name");
  const owner = await db.collection("users").doc(companyId).get();
  return (owner.exists && owner.get("companyName")) || "(no name)";
}

// ── Commands ───────────────────────────────────────────────────────

async function createCompanyCmd(args, { auth, db }) {
  need(args, "email", "company", "preset");
  const types = args.types ? args.types.split(",").map((s) => s.trim()).filter(Boolean) : [];
  console.log(`New company "${args.company}", preset ${args.preset}${types.length ? ` (+ ${types.join(", ")})` : ""}`);
  console.log(`Owner: ${args.name || "(no name)"} <${args.email.trim().toLowerCase()}>`);
  if (!(await confirm(args, ""))) return console.log("Stopped. Nothing created.");
  const r = await core.createCompany(auth, db, ACTOR, {
    ownerEmail: args.email, ownerName: args.name, companyName: args.company,
    mobile: args.mobile, preset: args.preset, extraTypes: types,
  });
  console.log(`  Created company ${r.companyId} with ${r.categories} starter categories; owner labelled.`);
  await inviteOrSkip(args, auth, r.email);
}

async function addMemberCmd(args, { auth, db }) {
  need(args, "company", "email", "role");
  const companyId = await companyIdOf(auth, db, args.company);
  console.log(`Add ${args.name || "(no name)"} <${args.email}> as ${args.role} to "${await companyName(db, companyId)}" (${companyId})`);
  if (!(await confirm(args, ""))) return console.log("Stopped. Nothing changed.");
  const r = await core.addMemberToCompany(auth, db, companyId, ACTOR, { email: args.email, name: args.name, role: args.role });
  const what = { created: "Login created", restored: "Brought back (was removed earlier)", already: "Already in this company; nothing changed" };
  console.log(`  ${what[r.status]}: ${r.email} as ${r.role}.`);
  if (r.status !== "already") await inviteOrSkip(args, auth, r.email);
}

async function resendInviteCmd(args, { auth }) {
  need(args, "email");
  const email = args.email.trim().toLowerCase();
  const user = await auth.getUserByEmail(email).catch(() => null);
  if (!user) throw new Error(`No login with email ${email}.`);
  if (user.disabled) throw new Error(`${email} was removed from their company; add them again first.`);
  if (args.link) {
    const link = await auth.generatePasswordResetLink(email, { url: DASHBOARD });
    console.log(`Link for ${email} (works once, expires in about an hour):\n\n${link}\n`);
    return;
  }
  await sendInviteEmail(args, email);
  console.log(`Emailed ${email} the link to set their password.`);
}

async function teamCmd(args, { auth, db }) {
  need(args, "company");
  const companyId = await companyIdOf(auth, db, args.company);
  const snap = await db.collection("companies").doc(companyId).collection("members").get();
  console.log(`"${await companyName(db, companyId)}" (${companyId}): ${snap.size} people\n`);
  const order = { owner: 0, manager: 1, staff: 2 };
  snap.docs
    .map((d) => d.data())
    .sort((a, b) => (order[a.role] ?? 9) - (order[b.role] ?? 9) || String(a.email).localeCompare(String(b.email)))
    .forEach((m) => console.log(`  ${String(m.role).padEnd(8)} ${String(m.email).padEnd(36)} ${String(m.name || "").padEnd(24)} ${m.status}`));
}

async function companiesCmd(args, { db }) {
  const refs = await db.collection("companies").listDocuments();
  console.log(`${refs.length} companies\n`);
  for (const ref of refs) {
    const [settings, owner, members] = await Promise.all([
      ref.collection("settings").doc("config").get(),
      db.collection("users").doc(ref.id).get(),
      ref.collection("members").where("status", "==", "active").count().get(),
    ]);
    const name = await companyName(db, ref.id);
    const email = (owner.exists && owner.get("email")) || "";
    const preset = settings.exists ? settings.get("template") : "no settings";
    console.log(`  ${name.padEnd(28)} ${String(preset).padEnd(11)} ${String(members.data().count).padStart(3)} people  ${email}  ${ref.id}`);
  }
}

async function syncCompanyNamesCmd(args, { db }) {
  const refs = await db.collection("companies").listDocuments();
  let copied = 0;
  for (const ref of refs) {
    const [company, owner] = await Promise.all([ref.get(), db.collection("users").doc(ref.id).get()]);
    const current = company.exists ? company.get("name") : "";
    const fromOwner = owner.exists ? owner.get("companyName") : "";
    if (current) {
      console.log(`  ${ref.id}  has name "${current}"`);
    } else if (fromOwner) {
      await ref.set({ name: fromOwner }, { merge: true });
      copied++;
      console.log(`  ${ref.id}  set to "${fromOwner}"`);
    } else {
      console.log(`  ${ref.id}  no name on the owner's profile either; left blank`);
    }
  }
  console.log(`\n${copied} company name(s) copied.`);
}

const COMMANDS = {
  "create-company": createCompanyCmd,
  "add-member": addMemberCmd,
  "resend-invite": resendInviteCmd,
  team: teamCmd,
  companies: companiesCmd,
  "sync-company-names": syncCompanyNamesCmd,
};

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const run = COMMANDS[args.command];
  if (!run) {
    throw new Error(`Command must be one of: ${Object.keys(COMMANDS).join(", ")}. Presets: ${Object.keys(PRESETS).join(", ")}.`);
  }
  const project = open(args);
  console.log(`Project: ${project.projectId}\n`);
  await run(args, project);
}

if (require.main === module) {
  main().catch((e) => {
    console.error(`\nError: ${e.message}`);
    process.exitCode = 1;
  });
}

module.exports = { parseArgs, main };
