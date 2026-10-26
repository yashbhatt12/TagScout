"use strict";

/**
 * Apply a settings template to a company, from your PC with admin rights.
 *
 *   node scripts/applyTemplate.js --list
 *   node scripts/applyTemplate.js --email owner@shop.in --preset jewellery --dry-run
 *   node scripts/applyTemplate.js --email owner@shop.in --preset jewellery
 *   node scripts/applyTemplate.js --company <uid> --preset jewellery --types general --overwrite
 *
 * Options:
 *   --list              List every company and whether it has settings yet
 *   --email <email>     Pick the company by its owner's login email
 *   --company <uid>     ...or by company id (== the owner's uid)
 *   --preset <key>      standard | warehouse | jewellery | apparel | assets | food
 *   --types a,b         Extra product types to switch on (e.g. keep "general" on)
 *   --overwrite         Replace an existing settings doc (admin edits in it are lost)
 *   --dry-run           Show what would be written; write nothing
 *
 * Needs GOOGLE_APPLICATION_CREDENTIALS pointing at a service account key.
 */

const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");
const { buildSettings, starterCategoriesFor, PRESETS } = require("../templates");
const { setUpCompany, settingsRef } = require("../companySetup");

initializeApp();
const db = getFirestore();

function parseArgs(argv) {
  const args = { types: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case "--list": args.list = true; break;
      case "--email": args.email = next(); break;
      case "--company": args.company = next(); break;
      case "--preset": args.preset = next(); break;
      case "--types": args.types = (next() || "").split(",").map((s) => s.trim()).filter(Boolean); break;
      case "--overwrite": args.overwrite = true; break;
      case "--dry-run": args.dryRun = true; break;
      default: throw new Error(`Unknown option '${a}'`);
    }
  }
  return args;
}

async function listCompanies() {
  // listDocuments() includes companies that only have subcollections (no company doc).
  const refs = await db.collection("companies").listDocuments();
  console.log(`${refs.length} compan${refs.length === 1 ? "y" : "ies"}:\n`);
  for (const ref of refs) {
    const [settings, user] = await Promise.all([
      settingsRef(db, ref.id).get(),
      getAuth().getUser(ref.id).catch(() => null),
    ]);
    const s = settings.exists ? settings.data() : null;
    const status = s ? `settings: ${s.template || "custom"} (template ${s.templateVersion || "?"})` : "NO SETTINGS";
    console.log(`  ${ref.id}  ${user ? user.email : "(no login)"}  —  ${status}`);
  }
}

async function resolveCompanyId(args) {
  if (args.company) return args.company;
  if (args.email) return (await getAuth().getUserByEmail(args.email)).uid;
  throw new Error("Pass --email <email> or --company <uid> (or --list)");
}

function describe(settings) {
  const types = Object.entries(settings.productTypes).filter(([, d]) => d.enabled);
  const off = Object.entries(settings.features).filter(([, on]) => !on).map(([k]) => k);
  const labels = Object.entries(settings.labels).map(([k, v]) => `${k}→${v.singular || v}`);
  console.log(`  Template:  ${settings.template} (version ${settings.templateVersion})`);
  console.log(`  Types on:  ${types.map(([k, d]) => `${k} (${d.fields.length} fields, groups: ${d.groups.join(", ")})`).join("; ")}`);
  console.log(`  Features off: ${off.length ? off.join(", ") : "none"}`);
  console.log(`  Labels:    ${labels.length ? labels.join(", ") : "defaults"}`);
  for (const { type, categories } of starterCategoriesFor(settings)) {
    console.log(`  Starter categories (${type}, if none exist): ${categories.map((c) => c.name).join(", ")}`);
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.list) return listCompanies();

  if (!args.preset || !PRESETS[args.preset]) {
    throw new Error(`Pass --preset, one of: ${Object.keys(PRESETS).join(", ")}`);
  }
  const companyId = await resolveCompanyId(args);
  const existing = await settingsRef(db, companyId).get();

  console.log(`\nCompany ${companyId}`);
  console.log(`  Current settings: ${existing.exists ? `${existing.get("template") || "custom"}` : "none"}`);
  describe(buildSettings(args.preset, args.types));

  if (existing.exists && !args.overwrite) {
    console.log("\nSettings already exist. Re-run with --overwrite to replace them. Nothing written.");
    return;
  }
  if (args.dryRun) {
    console.log("\nDry run — nothing written.");
    return;
  }

  const result = await setUpCompany(db, companyId, {
    preset: args.preset,
    extraTypes: args.types,
    overwrite: Boolean(args.overwrite),
  });
  console.log(`\nDone: settings ${result.written ? "written" : "unchanged"}, ${result.categories} starter categor${result.categories === 1 ? "y" : "ies"} added.`);
}

main().then(() => process.exit(0)).catch((e) => {
  console.error(`\nError: ${e.message}`);
  process.exit(1);
});