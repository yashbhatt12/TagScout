// Presets and business views.
const {test} = require("node:test");
const assert = require("node:assert/strict");
const {PRESETS, buildSettings, starterCategoriesFor} = require("../lib/templates.js");

test("every preset builds settings with its business view", () => {
  const homes = {standard: "general", warehouse: "warehouse", jewellery: "jewellery", apparel: "retail", assets: "assets", food: "warehouse"};
  for (const key of Object.keys(PRESETS)) {
    const s = buildSettings(key);
    assert.equal(s.template, key);
    assert.equal(s.home, homes[key], key);
    assert.ok(Object.values(s.productTypes).some((t) => t.enabled), `${key} enables a product type`);
    assert.doesNotThrow(() => starterCategoriesFor(s));
  }
  assert.throws(() => buildSettings("nope"));
});

test("business view switches and labels", () => {
  const j = buildSettings("jewellery");
  assert.equal(j.features.wms, false);
  assert.equal(j.features.retail, true);
  assert.equal(j.labels.bin.singular, "Tray");
  assert.equal(j.labels.unit.singular, "Piece");
  const a = buildSettings("assets");
  assert.equal(a.features.retail, false);
  assert.equal(a.labels.warehouse.singular, "Site");
});
