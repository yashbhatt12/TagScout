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
