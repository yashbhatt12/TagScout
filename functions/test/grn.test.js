// GRN import: file reading, row checks (same rules as the app's GrnService) and commit.
const fake = require("./fake");
const {test} = require("node:test");
const assert = require("node:assert/strict");
const ExcelJS = require("exceljs");
const {getFirestore} = require("firebase-admin/firestore");
const fns = require("../lib/index.js");
const {parseGrnWorkbook} = require("../lib/grn/workbook.js");
const {validateGrnRows} = require("../lib/grn/grnImport.js");
const {check, parseDateText, CompanyConfig, matchesHeader} = require("../lib/grn/fields.js");

const C = "c1";
const E = (n) => "E2000000000000000000" + String(n).padStart(4, "0");

/** An .xlsx in memory: first row headers, then rows (cells may be numbers or Dates). */
async function xlsx(headers, rows, {extraSheetFirst = false} = {}) {
  const wb = new ExcelJS.Workbook();
  if (extraSheetFirst) wb.addWorksheet("Other").addRow(["not this one"]);
  const ws = wb.addWorksheet("GRN");
  ws.addRow(headers);
  for (const r of rows) ws.addRow(r);
  if (extraSheetFirst) wb.removeWorksheet(wb.worksheets[0].id); // keep GRN as the first sheet
  return Buffer.from(await wb.xlsx.writeBuffer());
}

// Settings for a jewellery company: a unit field "purity" (dropdown) and "Gross Wt" (number, g),
// batch tracking on food, serial required for it_asset, price required for jewellery.
const SETTINGS = {
  template: "jewellery",
  attributeGroups: [
    {id: "jewelleryUnit", label: "Jewellery", fields: [
      {key: "purity", label: "Purity", type: "dropdown", level: "unit", options: ["22K", "18K"], required: true},
      {key: "grossWeight", label: "Gross weight", type: "number", level: "unit", unit: "g", excelHeaders: ["Gross Wt"]},
    ]},
    {id: "batches", label: "Batches", capability: "batchTracking", fields: []},
  ],
  productTypes: {
    jewellery: {label: "Retail jewellery", groups: ["jewelleryUnit"], required: {price: true}},
    food: {label: "Food", groups: ["batches"], required: {batchNo: true, expiryDate: true}},
    it_asset: {label: "IT asset", groups: [], required: {serialNumber: true}},
  },
};

function setUp() {
  fake.reset({
    [`companies/${C}/settings/config`]: SETTINGS,
    [`companies/${C}/products/pj`]: {sku: "JW-1001", skuKey: "jw-1001", productType: "jewellery"},
    [`companies/${C}/products/pf`]: {sku: "MILK-1", skuKey: "milk-1", productType: "food"},
    [`companies/${C}/products/pa`]: {sku: "LAP-1", skuKey: "lap-1", productType: "it_asset"},
    [`companies/${C}/warehouses/w1`]: {name: "Andheri Showroom"},
    [`companies/${C}/warehouses/w1/racks/r1`]: {name: "Counter A", warehouseId: "w1"},
    [`companies/${C}/warehouses/w1/racks/r1/bins/b1`]: {binCode: "TRAY-A1", rackId: "r1", warehouseId: "w1"},
    [`companies/${C}/warehouses/w2`]: {name: "Vasai"},
    [`companies/${C}/warehouses/w2/racks/r2`]: {name: "Cold room", warehouseId: "w2"},
    [`companies/${C}/warehouses/w2/racks/r2/bins/b2`]: {binCode: "COLD-1", rackId: "r2", warehouseId: "w2"},
    [`companies/${C}/inventory_units/${E(9)}`]: {sku: "JW-1001", status: "in_stock"},
    [`companies/${C}/batches/pf__b-old`]: {productId: "pf", batchNo: "B-OLD", mfgDate: "2026-09-01", expiryDate: "2026-12-31"},
  });
  return fake.member(C, "st", "staff");
}

const HEADERS = ["SKU *", "EPC *", "Bin Code *", "Warehouse Name *", "Serial Number", "Notes", "Tag price", "Purity", "Gross Wt"];

test("field checks match the app's FieldValidator", () => {
  assert.equal(parseDateText("2027-03-31"), "2027-03-31");
  assert.equal(parseDateText("31-03-2027"), "2027-03-31");
  assert.equal(parseDateText("1/4/2027"), "2027-04-01");
  assert.equal(parseDateText("31.03.2027"), "2027-03-31");
  assert.equal(parseDateText("31 Mar 2027"), "2027-03-31");
  assert.equal(parseDateText("5-sep-2027"), "2027-09-05");
  assert.equal(parseDateText("46112"), "2026-03-31", "Excel serial as text");
  assert.equal(parseDateText("31-02-2027"), null, "no such day");
  assert.equal(parseDateText("31-03/2027"), null, "mixed separators");
  assert.equal(parseDateText("2027/03/31"), null);
  const num = {label: "Gross weight", type: "number", required: false, options: [], unit: "g"};
  assert.deepEqual(check(num, "6.42 g"), {value: 6.42, error: null});
  assert.deepEqual(check(num, "1,25,000"), {value: 125000, error: null});
  assert.equal(check(num, "six").error, "Gross weight must be a number");
  const pick = {label: "Purity", type: "dropdown", required: true, options: ["22K", "18K"], unit: ""};
  assert.deepEqual(check(pick, "22k"), {value: "22K", error: null});
  assert.equal(check(pick, "24K").error, "Purity must be one of: 22K, 18K");
  assert.equal(check(pick, " ").error, "Purity is required");
  const bool = {label: "Hallmarked", type: "boolean", required: false, options: [], unit: ""};
  assert.equal(check(bool, "Yes").value, true);
  assert.equal(check(bool, "maybe").error, "Hallmarked must be Yes or No");
  const config = new CompanyConfig(SETTINGS);
  const gross = config.fieldsFor("jewellery", "unit").find((f) => f.key === "grossWeight");
  assert.ok(matchesHeader(gross, "gross_wt") && matchesHeader(gross, "Gross weight") && matchesHeader(gross, "GROSSWEIGHT"));
  assert.equal(config.isCoreRequired("food", "batchNo"), true);
  assert.equal(config.isCoreRequired("jewellery", "batchNo"), false, "batch fields only with batch tracking");
});

test("file structure problems are one clear error", async () => {
  assert.equal((await parseGrnWorkbook(Buffer.from("not excel"))).error.startsWith("Could not read the file"), true);
  assert.equal((await parseGrnWorkbook(await xlsx(["SKU", "EPC"], [["A", E(1)]]))).error, "Missing required column(s): Bin Code, Warehouse Name");
  assert.equal((await parseGrnWorkbook(await xlsx(HEADERS, []))).error, "No data rows found under the header.");
});

test("reads rows like the app: header variants, blank rows skipped, extras kept, row numbers as in Excel", async () => {
  const buf = await xlsx(["sku", "EPC Code", "Bin", "Warehouse", "Serial", "Notes", "Gross Wt", "Mfg date"], [
    ["JW-1001", E(1), "TRAY-A1", "andheri showroom", "", "first", 6.42, new Date(Date.UTC(2026, 9, 1))],
    [],
    ["JW-1001", "", "TRAY-A1", "Andheri Showroom"],
  ]);
  const r = await parseGrnWorkbook(buf);
  assert.equal(r.rows.length, 2);
  assert.deepEqual(r.rows.map((x) => x.rowNumber), [2, 4]);
  assert.deepEqual(r.rows[0].extras, {"Gross Wt": "6.42", "Mfg date": "2026-10-01"});
  assert.deepEqual(r.rows[1].parseErrors, ["EPC is blank"]);
});

test("row checks and messages match the app's GrnService", async () => {
  setUp();
  const parsed = await parseGrnWorkbook(await xlsx(HEADERS, [
    ["JW-1001", E(1).toLowerCase(), "TRAY-A1", "Andheri Showroom", "", "", "52,000", "22k", "6.42 g"], // clean
    ["JW-1001", E(2), "TRAY-A1", "Andheri Showroom", "", "", "", "24K", "x"],                            // price, purity, weight
    ["NOPE-1", "XYZ", "TRAY-Z", "Nowhere"],                                                                 // sku, epc, warehouse, bin
    ["JW-1001", E(3), "COLD-1", "Andheri Showroom", "", "", "100", "18K"],                                 // bin in another warehouse
    ["JW-1001", E(4), "TRAY-A1", "Andheri Showroom", "", "", "100", "18K"],
    ["JW-1001", E(4), "TRAY-A1", "Andheri Showroom", "", "", "100", "18K"],                                // duplicate EPC
    ["JW-1001", E(9), "TRAY-A1", "Andheri Showroom", "", "", "100", "18K"],                                // already in stock
    ["LAP-1", E(5), "TRAY-A1", "Andheri Showroom", "", "", "0"],                                            // serial required, price 0
  ]));
  const rows = await validateGrnRows(getFirestore(), C, parsed.rows);
  const errs = Object.fromEntries(rows.map((r) => [r.rowNumber, r.errors]));
  assert.deepEqual(errs[2], []);
  assert.equal(rows[0].epc, E(1));
  assert.equal(rows[0].price, 52000);
  assert.deepEqual(rows[0].customFields, {purity: "22K", grossWeight: 6.42});
  assert.deepEqual(errs[3], ["Tag price is required", "Purity must be one of: 22K, 18K", "Gross weight must be a number"]);
  assert.deepEqual(errs[4], [
    "EPC 'XYZ' isn't a valid tag ID (hex, 16–124 characters in groups of 4)",
    "SKU 'NOPE-1' not found in the product catalog",
    "Warehouse 'Nowhere' not found",
    "Bin 'TRAY-Z' not found",
  ]);
  assert.deepEqual(errs[5], ["Bin 'COLD-1' not found in warehouse 'Andheri Showroom'"]);
  assert.deepEqual(errs[6], [`EPC '${E(4)}' appears in multiple rows of this file`]);
  assert.deepEqual(errs[8], [`EPC '${E(9)}' is already in inventory`]);
  assert.deepEqual(errs[9], ["Serial Number is required for IT asset", "Tag price must be more than 0"]);
});

test("batch rules: required number, same dates across the file, stored batch dates win", async () => {
  setUp();
  const H = ["SKU", "EPC", "Bin Code", "Warehouse Name", "Batch number", "Mfg date", "Expiry date"];
  const parsed = await parseGrnWorkbook(await xlsx(H, [
    ["MILK-1", E(11), "COLD-1", "Vasai", "", "", ""],                       // batch number required
    ["MILK-1", E(12), "COLD-1", "Vasai", "B-NEW", "01-10-2026", "01-01-2027"],
    ["MILK-1", E(13), "COLD-1", "Vasai", "b-new", "", "02-01-2027"],          // different expiry in this file
    ["MILK-1", E(14), "COLD-1", "Vasai", "B-OLD", "", "31-01-2027"],          // stored batch has another expiry
    ["MILK-1", E(15), "COLD-1", "Vasai", "B-NEW2", "", ""],                   // new batch, expiry required
  ]));
  const rows = await validateGrnRows(getFirestore(), C, parsed.rows);
  const errs = Object.fromEntries(rows.map((r) => [r.rowNumber, r.errors]));
  assert.deepEqual(errs[2], ["Batch number is required"]);
  assert.deepEqual(errs[3], []);
  assert.deepEqual(errs[4], ["Batch b-new has different dates on another row of this file"]);
  assert.deepEqual(errs[5], ["Batch B-OLD already exists with expiry date 2026-12-31"]);
  assert.deepEqual(errs[6], ["Expiry date is required for a new batch"]);
});

test("validate and commit through the function, as the app calls them", async () => {
  const who = setUp();
  const path = `companies/${C}/imports/st/grn-1.xlsx`;
  fake.state.files[path] = await xlsx(HEADERS, [
    ["JW-1001", E(21), "TRAY-A1", "Andheri Showroom", "SN1", "lot A", "52000", "22K", "6.42"],
    ["JW-1001", E(22), "TRAY-A1", "Andheri Showroom", "", "lot A", "48000", "18K", ""],
    ["MILK-1", E(23), "COLD-1", "Vasai"],
  ]);
  const run = (data) => fns.grnImportV1.run(fake.request(who.uid, who.claims, data));

  await fake.rejects(run({action: "validate", path: `companies/${C}/imports/someone-else/x.xlsx`}), "invalid-argument");
  await fake.rejects(run({action: "validate", path: `companies/other/imports/st/x.xlsx`}), "invalid-argument");

  const preview = await run({action: "validate", path});
  assert.equal(preview.totalRowCount, 3);
  assert.equal(preview.errorRowCount, 1);
  assert.deepEqual(preview.rows[2].errors, ["Batch number is required"]);
  assert.ok(!("productId" in preview.rows[0]), "only what the screen shows is sent back");
  assert.equal(fake.paths(`companies/${C}/inventory_units/`).length, 1, "validate writes nothing");

  const year = new Date().getFullYear();
  const done = await run({action: "commit", path, reference: `GRN-${year}-0007`});
  assert.equal(done.inwardedCount, 2);
  assert.deepEqual(done.failures, [{rowNumber: 4, reason: "Batch number is required"}]);
  const unit = fake.doc(`companies/${C}/inventory_units/${E(21)}`);
  assert.equal(unit.serialNumber, "SN1");
  assert.equal(unit.price, 52000);
  assert.deepEqual(unit.customFields, {purity: "22K", grossWeight: 6.42});
  const moves = fake.paths(`companies/${C}/movements/`).map(fake.doc);
  assert.ok(moves.every((m) => m.referenceType === "grn" && m.referenceId === `GRN-${year}-0007` && m.notes === "lot A"));
  assert.equal(fake.doc(`companies/${C}/counters/grn`), undefined, "a partial import leaves the GRN number alone");
  assert.equal(fake.state.files[path], undefined, "upload deleted after commit");
});

test("a fully clean import creates its batch and advances the GRN number", async () => {
  const who = setUp();
  const path = `companies/${C}/imports/st/grn-2.xlsx`;
  fake.state.files[path] = await xlsx(["SKU", "EPC", "Bin Code", "Warehouse Name", "Batch number", "Expiry date"], [
    ["MILK-1", E(31), "COLD-1", "Vasai", "B-77", "31-12-2027"],
    // Like the app, each row of a new batch repeats the dates when they're required (ENH-GRN-001).
    ["MILK-1", E(32), "COLD-1", "Vasai", "B-77", "31-12-2027"],
  ]);
  const year = new Date().getFullYear();
  const done = await fns.grnImportV1.run(fake.request(who.uid, who.claims, {action: "commit", path, reference: `GRN-${year}-0042`}));
  assert.equal(done.inwardedCount, 2);
  assert.deepEqual(done.failures, []);
  assert.equal(fake.doc(`companies/${C}/batches/pf__b-77`).expiryDate, "2027-12-31");
  assert.equal(fake.doc(`companies/${C}/inventory_units/${E(32)}`).batchNo, "B-77");
  assert.equal(fake.doc(`companies/${C}/counters/grn`).sequence, 42);
});

test("commit needs a reference and something to inward", async () => {
  const who = setUp();
  const path = `companies/${C}/imports/st/grn-3.xlsx`;
  fake.state.files[path] = await xlsx(HEADERS, [["NOPE", E(41), "TRAY-A1", "Andheri Showroom"]]);
  const run = (data) => fns.grnImportV1.run(fake.request(who.uid, who.claims, data));
  await fake.rejects(run({action: "commit", path, reference: " "}), "invalid-argument");
  await fake.rejects(run({action: "commit", path, reference: "GRN-X"}), "failed-precondition");
  await fake.rejects(run({action: "validate", path: `companies/${C}/imports/st/missing.xlsx`}), "not-found");
});
