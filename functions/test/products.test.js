const fake = require("./fake");
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fns = require("../lib/index.js");
const {indexDocId} = require("../lib/products.js");

const C = "c1";
const P = `companies/${C}/products`;
const IX = `companies/${C}/sku_index`;
let staff;
let manager;
function setUp(docs = {}) {
  fake.reset(docs);
  staff = fake.member(C, "st", "staff");
  manager = fake.member(C, "mg", "manager");
}
const call = (who, data) => fns.productsV1.run(fake.request(who.uid, who.claims, data));
const product = (x = {}) => ({sku: "JW-1001", title: "Gold Ring", hsnCode: "7113 19", productType: "jewellery", unitOfMeasure: "piece",
  status: "active", customFields: {purity: "22K", blank: "", hsnCode: "999"}, imageUrl: "https://evil.example/x.jpg", ...x});

test("SKU index ids match the app's Java URLEncoder", () => {
  // Expected values produced by java.net.URLEncoder.encode(key, "UTF-8").
  const cases = {
    "jw-1001": "k_jw-1001", "ring 22k": "k_ring+22k", "a/b": "k_a%2Fb", "it's (new)!": "k_it%27s+%28new%29%21",
    "50%~off": "k_50%25%7Eoff", "*star*": "k_*star*", "café": "k_caf%C3%A9", "₹500": "k_%E2%82%B9500", "a+b&c=d": "k_a%2Bb%26c%3Dd",
  };
  for (const [key, id] of Object.entries(cases)) assert.equal(indexDocId(key), id, key);
});

test("create checks fields, normalises HSN and indexes the SKU", async () => {
  setUp();
  await fake.rejects(call(staff, {action: "create", product: product()}), "permission-denied");
  await fake.rejects(call(manager, {action: "create", product: product({hsnCode: "71131"})}), "invalid-argument");
  await fake.rejects(call(manager, {action: "create", product: product({shortName: "x".repeat(21)})}), "invalid-argument");
  await fake.rejects(call(manager, {action: "create", product: product({unitOfMeasure: "furlong"})}), "invalid-argument");
  const id = (await call(manager, {action: "create", product: product()})).id;
  const p = fake.doc(`${P}/${id}`);
  assert.equal(p.hsnCode, "711319");
  assert.equal(p.imageUrl, "");
  assert.deepEqual(p.customFields, {purity: "22K"});
  assert.deepEqual(fake.doc(`${IX}/${indexDocId("jw-1001")}`), {productId: id, sku: "JW-1001"});
  await fake.rejects(call(manager, {action: "create", product: product({sku: "jw-1001"})}), "already-exists");
});

test("an older product without an index entry still blocks its SKU", async () => {
  setUp({[`${P}/legacy`]: {sku: "OLD-7", title: "Old"}});
  await fake.rejects(call(manager, {action: "create", product: product({sku: "OLD-7"})}), "already-exists");
});

test("changing a SKU moves the index and updates the units", async () => {
  setUp();
  const id = (await call(manager, {action: "create", product: product()})).id;
  const other = (await call(manager, {action: "create", product: product({sku: "JW-2002"})})).id;
  fake.state.docs[`companies/${C}/inventory_units/E1`] = {productId: id, sku: "JW-1001"};
  await fake.rejects(call(manager, {action: "changeSku", productId: other, sku: "JW-1001"}), "already-exists");
  const r = await call(manager, {action: "changeSku", productId: id, sku: "JW-1001-A"});
  assert.equal(r.unitsUpdated, 1);
  assert.equal(fake.doc(`companies/${C}/inventory_units/E1`).sku, "JW-1001-A");
  assert.equal(fake.doc(`${IX}/${indexDocId("jw-1001")}`), undefined);
});

test("photo link: only this product's own uploads", async () => {
  setUp();
  const id = (await call(manager, {action: "create", product: product()})).id;
  await fake.rejects(call(manager, {action: "setImage", productId: id, imageUrl: "https://evil.example/x.jpg"}), "invalid-argument");
  const folder = encodeURIComponent(`companies/${C}/products/${id}/`);
  await call(manager, {action: "setImage", productId: id, imageUrl: `https://firebasestorage.googleapis.com/v0/b/x/o/${folder}1.jpg`});
  assert.ok(fake.doc(`${P}/${id}`).imageUrl.includes(folder));
});

test("delete is refused while units exist", async () => {
  setUp();
  const id = (await call(manager, {action: "create", product: product()})).id;
  fake.state.docs[`companies/${C}/inventory_units/E1`] = {productId: id};
  await fake.rejects(call(manager, {action: "delete", productId: id}), "failed-precondition");
  delete fake.state.docs[`companies/${C}/inventory_units/E1`];
  await call(manager, {action: "delete", productId: id});
  assert.equal(fake.doc(`${P}/${id}`), undefined);
});
