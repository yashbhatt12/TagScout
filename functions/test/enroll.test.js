const fake = require("./fake");
const {test} = require("node:test");
const assert = require("node:assert/strict");
const {enrollUnitsV1} = require("../lib/index.js");

const C = "c1";
const E = (n) => "E2000000000000000000" + String(n).padStart(4, "0");
const APP_UNIT_FIELDS = ["batchId", "batchNo", "currentBinCode", "currentBinId", "currentRackId", "currentWarehouseId", "customFields",
  "enrolledAt", "enrolledBy", "epc", "expiryDate", "inwardedAt", "lastMovedAt", "mfgDate", "price", "productId", "quantity", "serialNumber",
  "sku", "status", "tid", "unitKind"];
const APP_MOVEMENT_FIELDS = ["batchId", "epc", "fromBinCode", "fromBinId", "fromRackId", "fromWarehouseId", "notes", "productId", "quantity",
  "referenceId", "referenceType", "sku", "timestamp", "toBinCode", "toBinId", "toRackId", "toWarehouseId", "type", "userId"];

function setUp() {
  fake.reset({
    [`companies/${C}/products/p1`]: {sku: "JW-1001"},
    [`companies/${C}/products/p2`]: {sku: "JW-2002"},
    [`companies/${C}/warehouses/w1/racks/r1/bins/b1`]: {binCode: "TRAY-A1"},
    [`companies/${C}/batches/bt1`]: {productId: "p1", batchNo: "B-7", mfgDate: "2026-10-01", expiryDate: "2027-10-01"},
    [`companies/${C}/inventory_units/${E(1)}`]: {sku: "JW-9", status: "sold"},
  });
  return fake.member(C, "staff1", "staff");
}
const base = (extra) => ({productId: "p1", warehouseId: "w1", rackId: "r1", binId: "b1", ...extra});
const call = (who, data) => enrollUnitsV1.run(fake.request(who.uid, who.claims, data));
const movements = () => fake.paths(`companies/${C}/movements/`);

test("refuses callers without access", async () => {
  setUp();
  await fake.rejects(enrollUnitsV1.run(fake.request(null, null, base({units: [{epc: E(2)}]}))), "unauthenticated");
  await fake.rejects(call({uid: "x", claims: {}}, base({units: [{epc: E(2)}]})), "permission-denied");
  fake.state.docs[`companies/${C}/members/gone`] = {role: "staff", status: "removed"};
  await fake.rejects(call({uid: "gone", claims: {companyId: C, role: "staff"}}, base({units: [{epc: E(2)}]})), "permission-denied");
});

test("refuses bad requests", async () => {
  const who = setUp();
  await fake.rejects(call(who, base({units: []})), "invalid-argument");
  await fake.rejects(call(who, base({units: Array.from({length: 501}, (_, i) => ({epc: "E200" + String(i).padStart(20, "0")}))})), "invalid-argument");
  await fake.rejects(call(who, base({productId: "nope", units: [{epc: E(2)}]})), "not-found");
  await fake.rejects(call(who, base({binId: "nope", units: [{epc: E(2)}]})), "not-found");
  await fake.rejects(call(who, base({productId: "p2", batchId: "bt1", units: [{epc: E(2)}]})), "invalid-argument");
  await fake.rejects(call(who, base({units: [{epc: E(2), customFields: {weight: {g: 1}}}]})), "invalid-argument");
});

test("enrolls a mixed list like the app did, reading SKU and bin code on the server", async () => {
  const who = setUp();
  const r = await call(who, base({batchId: "bt1", referenceId: " GRN-1 ", sku: "FORGED", binCode: "FORGED", units: [
    {epc: " e2000000000000000000cccc ", tid: "E28011", serialNumber: " SN1 ", price: 52000, customFields: {purity: "22K", note: " ", grams: 4.2, ok: true, empty: null}},
    {epc: E(4)}, {epc: E(4)}, {epc: E(1)}, {epc: "XYZ"},
  ]}));
  assert.deepEqual(r.results.map((x) => x.outcome), ["DUPLICATE_IN_LIST", "INVALID_EPC", "ENROLLED", "ENROLLED", "ALREADY_ENROLLED"]);
  assert.equal(r.results[4].message, "Already enrolled as JW-9 (sold)");
  const unit = fake.doc(`companies/${C}/inventory_units/E2000000000000000000CCCC`);
  assert.deepEqual(Object.keys(unit).sort(), APP_UNIT_FIELDS);
  assert.equal(unit.sku, "JW-1001");
  assert.equal(unit.currentBinCode, "TRAY-A1");
  assert.equal(unit.enrolledBy, "staff1");
  assert.deepEqual(unit.customFields, {purity: "22K", grams: 4.2, ok: true});
  assert.equal(fake.doc(`companies/${C}/inventory_units/${E(1)}`).status, "sold");
  const m = fake.doc(movements()[0]);
  assert.deepEqual(Object.keys(m).sort(), APP_MOVEMENT_FIELDS);
  assert.equal(m.type, "inward");
  assert.equal(m.referenceId, "GRN-1");
});

test("a retried request writes nothing twice", async () => {
  const who = setUp();
  const req = fake.request(who.uid, who.claims, base({units: [{epc: E(2)}, {epc: E(3)}]}));
  const first = await enrollUnitsV1.run(req);
  const again = await enrollUnitsV1.run(req);
  assert.deepEqual(again, first);
  assert.equal(movements().length, 2);
});

test("120 tags in one call", async () => {
  const who = setUp();
  const r = await call(who, base({referenceType: "grn", units: Array.from({length: 120}, (_, i) => ({epc: "E3" + String(i).padStart(22, "0")}))}));
  assert.equal(r.enrolled, 120);
  assert.equal(movements().length, 120);
});
