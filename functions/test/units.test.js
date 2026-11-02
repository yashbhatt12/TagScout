const fake = require("./fake");
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fns = require("../lib/index.js");

const C = "c1";
const E = (n) => "E2000000000000000000" + String(n).padStart(4, "0");
const unit = (status, extra = {}) => ({status, productId: "p1", sku: "JW-1", currentWarehouseId: "w1", currentRackId: "r1",
  currentBinId: "b1", currentBinCode: "TRAY-A1", batchId: "", ...extra});
let staff;
let manager;
function setUp(units) {
  fake.reset({[`companies/${C}/products/p1`]: {sku: "JW-1001"}, ...Object.fromEntries(Object.entries(units).map(([e, u]) => [`companies/${C}/inventory_units/${e}`, u]))});
  staff = fake.member(C, "st", "staff");
  manager = fake.member(C, "mg", "manager");
}
const call = (fn, who, data) => fns[fn].run(fake.request(who.uid, who.claims, data));
const lastMove = () => { const p = fake.paths(`companies/${C}/movements/`); return fake.doc(p[p.length - 1]); };

test("status changes log the same movements as the app", async () => {
  setUp({[E(1)]: unit("in_stock"), [E(2)]: unit("in_stock")});
  await call("changeUnitStatusV1", staff, {epc: E(1), status: "sold"});
  assert.equal(lastMove().type, "outward");
  assert.equal(lastMove().quantity, -1);
  assert.equal(fake.doc(`companies/${C}/inventory_units/${E(1)}`).soldBy, "st");
  await call("changeUnitStatusV1", staff, {epc: E(1), status: "in_stock"});
  assert.equal(lastMove().type, "inward");
  assert.ok(!("soldAt" in fake.doc(`companies/${C}/inventory_units/${E(1)}`)));
  await call("changeUnitStatusV1", staff, {epc: E(2), status: "in_repair"});
  assert.equal(lastMove().type, "outward");
  await call("changeUnitStatusV1", staff, {epc: E(2), status: "returned"});
  assert.equal(lastMove().type, "status_change");
  assert.equal(lastMove().quantity, 0);
});

test("staff can't write off; managers can", async () => {
  setUp({[E(3)]: unit("in_stock")});
  await fake.rejects(call("changeUnitStatusV1", staff, {epc: E(3), status: "lost"}), "permission-denied");
  await fake.rejects(call("changeUnitStatusV1", staff, {epc: E(3), status: "disposed"}), "permission-denied");
  await call("changeUnitStatusV1", manager, {epc: E(3), status: "lost"});
  await fake.rejects(call("changeUnitStatusV1", staff, {epc: E(3), status: "in_stock"}), "permission-denied");
  await call("changeUnitStatusV1", manager, {epc: E(3), status: "in_stock"});
  assert.equal(lastMove().type, "inward");
});

test("system statuses, re-tagged and unknown units are refused", async () => {
  setUp({[E(3)]: unit("in_stock"), [E(4)]: unit("retagged")});
  await fake.rejects(call("changeUnitStatusV1", manager, {epc: E(3), status: "expired"}), "invalid-argument");
  await fake.rejects(call("changeUnitStatusV1", manager, {epc: E(4), status: "in_stock"}), "failed-precondition");
  await fake.rejects(call("changeUnitStatusV1", manager, {epc: E(99), status: "sold"}), "not-found");
});

test("checkout sells once, with the right sale type", async () => {
  setUp({[E(5)]: unit("in_stock"), [E(6)]: unit("in_stock", {productId: "", sku: "OLD-1", itemCode: "R-77"}), [E(7)]: unit("picked")});
  const r = await call("markSoldV1", staff, {epc: E(5)});
  assert.equal(typeof r.soldAt, "number");
  assert.equal(lastMove().referenceType, "retail_sale");
  assert.equal(lastMove().sku, "JW-1001");
  await fake.rejects(call("markSoldV1", staff, {epc: E(5)}), "failed-precondition");
  await call("markSoldV1", staff, {epc: E(6)});
  assert.equal(lastMove().referenceType, "jewellery_sale");
  assert.equal(lastMove().referenceId, "R-77");
  await fake.rejects(call("markSoldV1", staff, {epc: E(7)}), "failed-precondition");
});

test("only owners and managers edit unit details", async () => {
  setUp({[E(8)]: unit("in_stock", {price: 100}), [E(4)]: unit("retagged")});
  await fake.rejects(call("updateUnitDetailsV1", staff, {epc: E(8), serialNumber: "Y"}), "permission-denied");
  await call("updateUnitDetailsV1", manager, {epc: E(8), serialNumber: " SN-9 ", price: 2500, customFields: {purity: "22K", blank: " "}});
  assert.equal(fake.doc(`companies/${C}/inventory_units/${E(8)}`).serialNumber, "SN-9");
  assert.deepEqual(fake.doc(`companies/${C}/inventory_units/${E(8)}`).customFields, {purity: "22K"});
  await call("updateUnitDetailsV1", manager, {epc: E(8), price: null});
  assert.ok(!("price" in fake.doc(`companies/${C}/inventory_units/${E(8)}`)));
  await fake.rejects(call("updateUnitDetailsV1", manager, {epc: E(4), serialNumber: "Z"}), "failed-precondition");
});
