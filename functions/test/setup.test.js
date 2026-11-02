// Company Set Up: locations, categories and brands.
const fake = require("./fake");
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fns = require("../lib/index.js");

const C = "c1";
const W = `companies/${C}/warehouses`;
let staff;
let manager;
function setUp(docs = {}) {
  fake.reset(docs);
  staff = fake.member(C, "st", "staff");
  manager = fake.member(C, "mg", "manager");
}
const call = (fn, who, data) => fns[fn].run(fake.request(who.uid, who.claims, data));

test("locations: owners and managers only; names and codes unique ignoring case", async () => {
  setUp();
  await fake.rejects(call("locationsV1", staff, {action: "createWarehouse", name: "Andheri"}), "permission-denied");
  const w1 = (await call("locationsV1", manager, {action: "createWarehouse", name: " Andheri "})).id;
  assert.equal(fake.doc(`${W}/${w1}`).name, "Andheri");
  await fake.rejects(call("locationsV1", manager, {action: "createWarehouse", name: "ANDHERI"}), "already-exists");
  const w2 = (await call("locationsV1", manager, {action: "createWarehouse", name: "Vasai"})).id;
  const r1 = (await call("locationsV1", manager, {action: "createRack", warehouseId: w1, name: "Counter A"})).id;
  await fake.rejects(call("locationsV1", manager, {action: "createRack", warehouseId: w1, name: "counter a"}), "already-exists");
  const r2 = (await call("locationsV1", manager, {action: "createRack", warehouseId: w2, name: "Counter A"})).id;
  await call("locationsV1", manager, {action: "createBin", warehouseId: w1, rackId: r1, binCode: "TRAY-A1"});
  await fake.rejects(call("locationsV1", manager, {action: "createBin", warehouseId: w2, rackId: r2, binCode: "tray-a1"}), "already-exists");
});

test("locations: deletes refused while units are stored, then cascade", async () => {
  setUp();
  const w = (await call("locationsV1", manager, {action: "createWarehouse", name: "A"})).id;
  const r = (await call("locationsV1", manager, {action: "createRack", warehouseId: w, name: "R"})).id;
  const b = (await call("locationsV1", manager, {action: "createBin", warehouseId: w, rackId: r, binCode: "B1"})).id;
  fake.state.docs[`companies/${C}/inventory_units/E1`] = {currentWarehouseId: w, currentRackId: r, currentBinId: b};
  await fake.rejects(call("locationsV1", manager, {action: "deleteBin", warehouseId: w, rackId: r, binId: b}), "failed-precondition");
  await fake.rejects(call("locationsV1", manager, {action: "deleteWarehouse", warehouseId: w}), "failed-precondition");
  delete fake.state.docs[`companies/${C}/inventory_units/E1`];
  await call("locationsV1", manager, {action: "deleteWarehouse", warehouseId: w});
  assert.equal(fake.paths(`${W}/${w}`).length, 0);
});

test("categories: unique per type and parent, one level of sub-categories, delete guards", async () => {
  setUp();
  await fake.rejects(call("catalogV1", staff, {action: "createCategory", name: "Rings", productType: "jewellery"}), "permission-denied");
  const rings = (await call("catalogV1", manager, {action: "createCategory", name: " Rings ", productType: "jewellery"})).id;
  await fake.rejects(call("catalogV1", manager, {action: "createCategory", name: "RINGS", productType: "jewellery"}), "already-exists");
  await call("catalogV1", manager, {action: "createCategory", name: "Rings", productType: "apparel"});
  const sub = (await call("catalogV1", manager, {action: "createCategory", name: "Bands", productType: "jewellery", parentId: rings})).id;
  await fake.rejects(call("catalogV1", manager, {action: "createCategory", name: "Thin", productType: "jewellery", parentId: sub}), "invalid-argument");
  await fake.rejects(call("catalogV1", manager, {action: "deleteCategory", categoryId: rings}), "failed-precondition");
  fake.state.docs[`companies/${C}/products/p1`] = {subCategoryId: sub};
  await fake.rejects(call("catalogV1", manager, {action: "deleteCategory", categoryId: sub}), "failed-precondition");
});

test("brands: find-or-create never duplicates and reactivates", async () => {
  setUp();
  const b = await call("catalogV1", manager, {action: "findOrCreateBrand", name: " Tanishq "});
  assert.equal((await call("catalogV1", manager, {action: "findOrCreateBrand", name: "TANISHQ"})).id, b.id);
  await call("catalogV1", manager, {action: "setBrandActive", brandId: b.id, active: false});
  await call("catalogV1", manager, {action: "findOrCreateBrand", name: "tanishq"});
  assert.equal(fake.doc(`companies/${C}/brands/${b.id}`).isActive, true);
  assert.equal(fake.paths(`companies/${C}/brands/`).length, 1);
});
