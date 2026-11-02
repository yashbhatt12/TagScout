// Batches, GRN counter and cycle-count commit.
const fake = require("./fake");
const {test} = require("node:test");
const assert = require("node:assert/strict");
const {getFirestore} = require("firebase-admin/firestore");
const fns = require("../lib/index.js");
const {validateBatchDates, bumpGrnCounter, indiaToday, batchIdOf} = require("../lib/stock.js");

const C = "c1";
let staff;
let manager;
function setUp() {
  fake.reset({
    [`companies/${C}/products/p1`]: {sku: "JW-1001"},
    [`companies/${C}/warehouses/w1/racks/r1/bins/b1`]: {binCode: "TRAY-A1"},
  });
  staff = fake.member(C, "st", "staff");
  manager = fake.member(C, "mg", "manager");
}
const call = (fn, who, data) => fns[fn].run(fake.request(who.uid, who.claims, data));

test("batch dates follow the app's rules, judged in India time", () => {
  const today = "2026-11-02";
  assert.equal(validateBatchDates("2026-02-30", "", today), "Manufacturing date must be YYYY-MM-DD");
  assert.equal(validateBatchDates("2026-11-03", "", today), "Manufacturing date can't be in the future");
  assert.equal(validateBatchDates("2026-11-02", "2026-11-02", today), "Expiry date must be after manufacturing date");
  assert.equal(validateBatchDates("2026-10-01", "2027-10-01", today), null);
  assert.equal(indiaToday(new Date("2026-11-01T19:00:00Z")), "2026-11-02");
  assert.equal(batchIdOf("p1", "LOT 2026/11"), "p1__lot+2026%2F11");
});

test("batches: anyone can create, dates can't change, managers set status", async () => {
  setUp();
  const b = await call("batchesV1", staff, {action: "findOrCreate", productId: "p1", batchNo: " B-7 ", mfgDate: "2026-10-01", expiryDate: "2027-10-01"});
  assert.equal(b.id, "p1__b-7");
  assert.equal((await call("batchesV1", staff, {action: "findOrCreate", productId: "p1", batchNo: "b-7"})).id, b.id);
  await fake.rejects(call("batchesV1", staff, {action: "findOrCreate", productId: "p1", batchNo: "B-7", expiryDate: "2028-01-01"}), "failed-precondition");
  await fake.rejects(call("batchesV1", staff, {action: "setStatus", batchId: b.id, status: "recalled"}), "permission-denied");
  await fake.rejects(call("batchesV1", manager, {action: "setStatus", batchId: b.id, status: "expired"}), "invalid-argument");
  await call("batchesV1", manager, {action: "setStatus", batchId: b.id, status: "quarantined"});
  assert.equal(fake.doc(`companies/${C}/batches/${b.id}`).status, "quarantined");
});

test("GRN counter moves only forward, only for this year's GRN-YYYY-NNNN", async () => {
  setUp();
  const db = getFirestore();
  const today = "2026-11-02";
  assert.deepEqual(await bumpGrnCounter(db, C, "GRN-2026-0042", today), {sequence: 42});
  assert.deepEqual(await bumpGrnCounter(db, C, "GRN-2026-0100", today), {sequence: 100});
  assert.deepEqual(await bumpGrnCounter(db, C, "grn-2026-0010", today), {sequence: 100});
  assert.deepEqual(await bumpGrnCounter(db, C, "MYGRN2026", today), {sequence: null});
  assert.deepEqual(await bumpGrnCounter(db, C, "GRN-2025-0500", today), {sequence: null});
  assert.deepEqual(await bumpGrnCounter(db, C, "GRN-2027-0003", "2027-01-01"), {sequence: 3});
});

test("cycle count writes one movement per discrepancy, recording who counted", async () => {
  setUp();
  const r = await call("commitCycleCountV1", staff, {warehouseId: "w1", rackId: "r1", binId: "b1", reference: "CC-7", rows: [
    {epc: "e2000000000000000000aaaa", sku: "JW-1", classification: "MISSING"},
    {epc: "E2000000000000000000BBBB", sku: "JW-2", classification: "UNEXPECTED", expectedBinCode: "TRAY-B2"},
  ]});
  assert.equal(r.movementsWritten, 2);
  const moves = fake.paths(`companies/${C}/movements/`).map(fake.doc);
  assert.deepEqual(moves.map((m) => m.quantity), [-1, 1]);
  assert.equal(moves[0].userId, "st");
  assert.equal(moves[0].notes, "Expected at TRAY-A1, not physically found");
  assert.equal(moves[1].notes, "Physically found at TRAY-A1, database has it at TRAY-B2");
  await fake.rejects(call("commitCycleCountV1", staff, {warehouseId: "w1", rackId: "r1", binId: "b1",
    rows: [{epc: "E2000000000000000000AAAA", classification: "MATCHED"}]}), "invalid-argument");
});
