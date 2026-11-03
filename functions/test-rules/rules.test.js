/**
 * Security rules tests, against the real Firestore and Storage emulators.
 *
 *   npm run test:rules        (from functions/; needs Java, e.g. Android Studio's jbr)
 *
 * The emulators load ../firestore.rules and ../storage.rules from the repo,
 * so these check exactly the rules that would be deployed. Nothing here
 * touches the real project: "demo-" projects only exist in the emulator.
 */
const {test, before, beforeEach, after} = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const {initializeTestEnvironment, assertSucceeds, assertFails} = require("@firebase/rules-unit-testing");
require("firebase/firestore").setLogLevel("silent");

const C = "c1";
const OTHER = "c2";
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-tagscout",
    firestore: {rules: fs.readFileSync(path.join(__dirname, "..", "..", "firestore.rules"), "utf8")},
    storage: {rules: fs.readFileSync(path.join(__dirname, "..", "..", "storage.rules"), "utf8")},
  });
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.doc(`companies/${C}`).set({name: "Test Jewellers"});
    await db.doc(`companies/${C}/products/p1`).set({sku: "JW-1", title: "Ring"});
    await db.doc(`companies/${C}/alerts/a1`).set({epc: "E1", read: false, severity: "high"});
    await db.doc(`companies/${C}/ops/op1`).set({kind: "enrollUnits", status: "done"});
    await db.doc(`companies/${OTHER}/products/p9`).set({sku: "X-9"});
    await db.doc("users/staff1").set({name: "Ravi", companyId: C});
    await db.doc("products/shop1").set({title: "Printer roll"});
    await db.doc("purchase_requisitions/o1").set({userId: "staff1", items: []});
    await db.doc("purchase_requisitions/o2").set({userId: "someone-else", items: []});
    await ctx.storage().ref(`companies/${C}/products/p1/photo.jpg`).put(new Uint8Array([1, 2, 3]), {contentType: "image/jpeg"});
  });
});

after(async () => {
  await env?.cleanup();
});

const staff = () => env.authenticatedContext("staff1", {companyId: C, role: "staff"}).firestore();
const manager = () => env.authenticatedContext("mgr1", {companyId: C, role: "manager"}).firestore();
const outsider = () => env.authenticatedContext("x1", {companyId: OTHER, role: "owner"}).firestore();
const unlabelled = () => env.authenticatedContext(C).firestore(); // old style: uid == companyId, no claims
const anonymous = () => env.unauthenticatedContext().firestore();

// ── Company data ──────────────────────────────────────────────────

test("team members read their company's data; nobody else does", async () => {
  await assertSucceeds(staff().doc(`companies/${C}/products/p1`).get());
  await assertSucceeds(staff().doc(`companies/${C}`).get());
  await assertFails(outsider().doc(`companies/${C}/products/p1`).get());
  await assertFails(staff().doc(`companies/${OTHER}/products/p9`).get());
  await assertFails(unlabelled().doc(`companies/${C}/products/p1`).get());
  await assertFails(anonymous().doc(`companies/${C}/products/p1`).get());
});

test("no direct writes to stock or set-up data, whatever the role", async () => {
  const collections = ["products", "inventory_units", "movements", "inventory_sku", "batches", "warehouses", "categories",
    "brands", "sku_index", "counters", "settings", "members", "ops"];
  for (const who of [staff(), manager()]) {
    for (const col of collections) {
      await assertFails(who.doc(`companies/${C}/${col}/new`).set({x: 1}));
    }
    await assertFails(who.doc(`companies/${C}/warehouses/w1/racks/r1/bins/b1`).set({binCode: "B1"}));
    await assertFails(who.doc(`companies/${C}/products/p1`).update({title: "Changed"}));
    await assertFails(who.doc(`companies/${C}/products/p1`).delete());
    await assertFails(who.doc(`companies/${C}`).set({name: "Hijacked"}));
  }
});

test("duplicate-protection records can't even be read", async () => {
  await assertFails(manager().doc(`companies/${C}/ops/op1`).get());
});

test("alerts: only the read fields can change", async () => {
  await assertSucceeds(staff().doc(`companies/${C}/alerts/a1`).update({read: true, readAt: new Date(), readBy: "staff1"}));
  await assertFails(staff().doc(`companies/${C}/alerts/a1`).update({severity: "low"}));
  await assertFails(staff().doc(`companies/${C}/alerts/a2`).set({epc: "E2"}));
  await assertFails(outsider().doc(`companies/${C}/alerts/a1`).update({read: true}));
});

test("door-reader events can be added but not changed", async () => {
  await assertSucceeds(staff().doc(`companies/${C}/tag_events/e1`).set({epc: "E1"}));
  await assertFails(staff().doc(`companies/${C}/tag_events/e1`).update({epc: "E2"}));
  await assertFails(outsider().doc(`companies/${C}/tag_events/e2`).set({epc: "E1"}));
});

// ── Top-level collections ─────────────────────────────────────────

test("profiles: read your own, written only by the server", async () => {
  await assertSucceeds(staff().doc("users/staff1").get());
  await assertFails(staff().doc("users/mgr1").get());
  await assertFails(staff().doc("users/staff1").update({companyId: OTHER}));
});

test("Buy Supplies orders: only in your own name", async () => {
  await assertSucceeds(staff().doc("purchase_requisitions/o3").set({userId: "staff1", items: []}));
  await assertFails(staff().doc("purchase_requisitions/o4").set({userId: "someone-else", items: []}));
  await assertSucceeds(staff().doc("purchase_requisitions/o1").get());
  await assertFails(staff().doc("purchase_requisitions/o2").get());
  await assertFails(staff().doc("purchase_requisitions/o1").update({items: [1]}));
});

test("shop catalog: readable when signed in, never writable", async () => {
  await assertSucceeds(staff().doc("products/shop1").get());
  await assertFails(anonymous().doc("products/shop1").get());
  await assertFails(manager().doc("products/shop1").set({title: "x"}));
});

// ── Storage (product photos) ──────────────────────────────────────

const storageAs = (uid, claims) => env.authenticatedContext(uid, claims).storage();
const jpeg = new Uint8Array([1, 2, 3]);

test("photos: owners and managers upload images; staff only view", async () => {
  const mgr = storageAs("mgr1", {companyId: C, role: "manager"});
  const st = storageAs("staff1", {companyId: C, role: "staff"});
  await assertSucceeds(mgr.ref(`companies/${C}/products/p1/new.jpg`).put(jpeg, {contentType: "image/jpeg"}));
  await assertFails(st.ref(`companies/${C}/products/p1/new2.jpg`).put(jpeg, {contentType: "image/jpeg"}));
  await assertFails(mgr.ref(`companies/${C}/products/p1/doc.pdf`).put(jpeg, {contentType: "application/pdf"}));
  await assertSucceeds(st.ref(`companies/${C}/products/p1/photo.jpg`).getMetadata());
  await assertFails(st.ref(`companies/${C}/products/p1/photo.jpg`).delete());
  await assertFails(storageAs("x1", {companyId: OTHER, role: "owner"}).ref(`companies/${C}/products/p1/photo.jpg`).getMetadata());
});

test("GRN files: upload Excel to your own imports folder only", async () => {
  const st = storageAs("staff1", {companyId: C, role: "staff"});
  const xlsx = new Uint8Array([0x50, 0x4b, 3, 4]);
  const type = {contentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"};
  await assertSucceeds(st.ref(`companies/${C}/imports/staff1/grn-1.xlsx`).put(xlsx, type));
  await assertFails(st.ref(`companies/${C}/imports/mgr1/grn-1.xlsx`).put(xlsx, type));
  await assertFails(st.ref(`companies/${C}/imports/staff1/grn-1.exe`).put(xlsx, type));
  await assertFails(storageAs("x1", {companyId: OTHER, role: "owner"}).ref(`companies/${C}/imports/x1/grn.xlsx`).put(xlsx, type));
});
