// Background functions: stock totals and door alerts.
const fake = require("./fake");
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fns = require("../lib/index.js");

const C = "c1";
const move = (type, productId, quantity, from, to) => ({type, productId, sku: productId ? "S-" + productId : "", quantity,
  fromWarehouseId: from ? "w1" : "", fromBinCode: from || "", toWarehouseId: to ? "w1" : "", toBinCode: to || ""});

test("stock totals follow the movements ledger", async () => {
  fake.reset();
  const seq = [
    move("inward", "p1", 1, null, "A1"), move("inward", "p1", 1, null, "A1"), move("inward", "p1", 1, null, "A1"),
    move("outward", "p1", -1, "A1", null), move("relocation", "p1", 0, "A1", "B2"), move("cyclecount_adjust", "p1", -1, null, "B2"),
    move("inward", "", 1, null, "A1"), move("status_change", "p1", 0, "A1", "A1"),
  ];
  let i = 0;
  for (const m of seq) await fns.updateSkuAggregate.run({data: {data: () => m}, params: {companyId: C, movementId: "m" + (i++)}});
  const agg = fake.doc(`companies/${C}/inventory_sku/p1`);
  assert.deepEqual(agg.byLocation, {"w1/A1": 1});
  assert.equal(agg.totalQuantity, 1);
});

test("door alert only for stock, with readable item details", async () => {
  const E = (n) => "E2000000000000000000" + String(n).padStart(4, "0");
  fake.reset({
    [`companies/${C}/inventory_units/${E(1)}`]: {status: "in_stock", sku: "JW-1", productId: "p1", price: 500},
    [`companies/${C}/inventory_units/${E(2)}`]: {status: "sold", sku: "JW-2"},
    [`companies/${C}/products/p1`]: {title: "Temple Necklace", categoryId: "cat1"},
    [`companies/${C}/categories/cat1`]: {name: "Necklace"},
  });
  for (const epc of [E(1).toLowerCase(), E(2), E(99)]) {
    await fns.alarmNotifier.run({data: {data: () => ({epc, readerId: "DOOR-1"})}, params: {companyId: C, eventId: "ev-" + epc}});
  }
  const alerts = fake.paths(`companies/${C}/alerts/`).map(fake.doc);
  assert.equal(alerts.length, 1);
  assert.equal(alerts[0].itemCode, "JW-1");
  assert.equal(alerts[0].category, "Necklace");
  assert.equal(alerts[0].productTitle, "Temple Necklace");
});
