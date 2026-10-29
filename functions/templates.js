"use strict";

/**
 * TagScout settings templates.
 *
 * Everything a new company's settings doc (companies/{cid}/settings/config)
 * starts from: attribute groups, product types, presets and starter
 * categories. The shape matches CompanyConfigParser in the Android app.
 *
 * Rules that keep existing data safe:
 *   - Never rename a field `key`, group `id` or type key once a customer
 *     uses it. Change `label` freely.
 *   - Removing a field from a template does not touch companies already
 *     set up; their settings doc is their own copy.
 *
 * Bump TEMPLATE_VERSION whenever this file changes, so a company's
 * settings doc records which version it was built from.
 */

const TEMPLATE_VERSION = "2026-10-28";
const SCHEMA_VERSION = 1;

const P = "product";
const U = "unit";

// ── Field helpers ─────────────────────────────────────────────────

function field(key, label, type, level, extra = {}) {
  return { key, label, type, level, required: false, active: true, ...extra };
}
const text = (key, label, level, extra) => field(key, label, "text", level, extra);
const num = (key, label, level, unit, extra = {}) =>
  field(key, label, "number", level, unit ? { unit, ...extra } : extra);
const date = (key, label, level, extra) => field(key, label, "date", level, extra);
const bool = (key, label, level, extra) => field(key, label, "boolean", level, extra);
const pick = (key, label, level, options, extra = {}) =>
  field(key, label, "dropdown", level, { options, ...extra });
const req = { required: true };

/** Numbers fields in display order. */
function ordered(fields) {
  return fields.map((f, i) => ({ order: i + 1, ...f }));
}

function group(id, label, order, capability, fields) {
  const g = { id, label, order, fields: ordered(fields) };
  if (capability) g.capability = capability;
  return g;
}

// ── Attribute groups (shared, defined once) ───────────────────────
// Rule: a field that can differ between two pieces of the same product
// lives on the unit.

const ATTRIBUTE_GROUPS = [
  group("purchaseWarranty", "Purchase and warranty", 1, null, [
    date("purchaseDate", "Purchase date", U),
    text("vendor", "Vendor", U),
    text("invoiceNo", "Invoice number", U),
    text("poNo", "PO number", U),
    num("baseCost", "Base cost", U, "INR"),
    num("gstAmount", "GST amount", U, "INR"),
    date("warrantyEndDate", "Warranty end date", U),
  ]),

  // assignedTo / assignedAt are core unit fields owned by the capability.
  group("assignment", "Assignment", 2, "assignment", [
    text("employeeId", "Employee ID", U),
    text("department", "Department", U),
  ]),

  // Audit history is a subcollection (inventory_units/{epc}/audits);
  // the group only switches the capability on.
  group("audit", "Audit and condition", 3, "audit", []),

  // Book value is computed from cost, method and useful life — never stored.
  group("depreciation", "Depreciation and disposal", 4, "depreciation", [
    pick("depreciationMethod", "Depreciation method", U, ["Straight line", "Written down value"]),
    num("usefulLifeYears", "Useful life", U, "years"),
    date("disposalDate", "Disposal date", U),
    text("disposalMethod", "Disposal method", U),
    text("disposalCertificate", "Disposal certificate", U),
  ]),

  // GST rate is a number, not a slab list, so rate changes need no template edit.
  group("pricingTax", "Pricing and tax", 5, null, [
    num("mrp", "MRP", P, "INR"),
    num("sellingPrice", "Selling price", P, "INR"),
    num("costPrice", "Cost price", P, "INR"),
    num("discountPct", "Discount", P, "%"),
    num("gstRate", "GST rate", P, "%"),
  ]),

  group("supplierOrigin", "Supplier and origin", 6, null, [
    text("supplier", "Supplier / manufacturer", P),
    text("supplierSku", "Supplier SKU", P),
  ]),

  // batchNo / mfgDate / expiryDate are core unit fields owned by the capability.
  group("batch", "Batch tracking", 7, "batchTracking", [
    text("grnNo", "GRN number", U),
    date("grnDate", "GRN date", U),
  ]),

  // soldAt / soldBy are core; price is the core informational tag price.
  group("retailSale", "Retail sale", 8, "retailSale", [
    text("saleInvoiceNo", "Sale invoice number", U),
  ]),

  group("nesting", "Cartons and pallets", 9, "nesting", []),
];

// ── Product types ─────────────────────────────────────────────────
// `required` overrides apply to group fields and to core fields such as
// serialNumber, batchNo, mfgDate and expiryDate. `enabled` is set per preset.

const PRODUCT_TYPES = {
  it_asset: {
    label: "IT asset",
    groups: ["purchaseWarranty", "assignment", "audit", "depreciation"],
    fields: ordered([
      text("make", "Make", P, req),
      text("model", "Model", P, req),
      text("processor", "Processor", P),
      text("ram", "RAM", P),
      text("storage", "Storage", P),
      text("assetTag", "Asset tag number", U, req),
    ]),
    required: { serialNumber: true },
  },

  non_it_asset: {
    label: "Non-IT asset",
    groups: ["purchaseWarranty", "assignment", "audit", "depreciation"],
    fields: ordered([
      text("make", "Make", P),
      text("model", "Model", P),
      text("material", "Material", P),
      text("colour", "Colour", P),
      text("assetTag", "Asset tag number", U, req),
    ]),
    required: {},
  },

  apparel: {
    label: "Retail apparel",
    groups: ["pricingTax", "supplierOrigin", "retailSale", "nesting"],
    fields: ordered([
      text("styleCode", "Style code", P, req),
      text("size", "Size", P, req),
      text("colour", "Colour", P, req),
      text("season", "Season / collection", P),
      pick("gender", "Gender", P, ["Men", "Women", "Unisex", "Kids"]),
      text("fit", "Fit", P),
      text("fabric", "Fabric / composition", P),
      text("pattern", "Pattern", P),
      text("careInstructions", "Care instructions", P),
    ]),
    required: {},
  },

  jewellery: {
    label: "Retail jewellery",
    groups: ["pricingTax", "supplierOrigin", "retailSale"],
    fields: ordered([
      text("designCode", "Design code", P, req),
      pick("metal", "Metal", P, ["Gold", "Silver", "Platinum"]),
      pick("purity", "Purity / karat", P, ["24K", "22K", "18K", "14K", "925"], req),
      text("collection", "Design / collection", P),
      pick("makingChargeType", "Making charge type", P, ["Per gram", "Flat"]),
      num("makingCharge", "Making charge", P, "INR"),
      num("grossWeight", "Gross weight", U, "g", req),
      num("netWeight", "Net weight", U, "g", req),
      num("stoneWeight", "Stone weight", U),
      pick("stoneWeightUnit", "Stone weight unit", U, ["ct", "g"]),
      text("stoneType", "Stone type", U),
      num("stoneCount", "Stone count", U),
      text("stoneClarity", "Stone clarity", U),
      text("stoneColour", "Stone colour", U),
      text("stoneCut", "Stone cut", U),
      text("size", "Size", U),
      text("huid", "Hallmark / HUID", U),
      text("certificateNo", "Certificate number", U),
      pick("certificateLab", "Certificate lab", U, ["IGI", "GIA", "SGL", "Other"]),
    ]),
    required: {},
  },

  food: {
    label: "Food",
    groups: ["pricingTax", "supplierOrigin", "batch"],
    fields: ordered([
      num("shelfLifeDays", "Shelf life", P, "days"),
      text("fssaiLicence", "FSSAI licence number", P),
      pick("vegMarker", "Veg / non-veg", P, ["Veg", "Non-veg"]),
      num("netQuantity", "Net quantity", P),
      pick("netQuantityUnit", "Net quantity unit", P, ["g", "kg", "ml", "L"]),
      text("packSize", "Pack size", P),
      text("ingredients", "Ingredients", P),
      text("allergens", "Allergens", P),
      text("nutritionInfo", "Nutritional info", P),
      pick("storageTemp", "Storage", P, ["Ambient", "Chilled", "Frozen"]),
      num("storageTempMin", "Storage temp min", P, "°C"),
      num("storageTempMax", "Storage temp max", P, "°C"),
      bool("fefo", "FEFO picking", P),
    ]),
    required: { batchNo: true, mfgDate: true, expiryDate: true },
  },

  general: {
    label: "General",
    groups: ["pricingTax", "supplierOrigin", "batch", "nesting"],
    fields: ordered([
      text("packSize", "Pack size", P),
      num("unitsPerCase", "Units per case", P),
      num("casesPerPallet", "Cases per pallet", P),
      num("lengthCm", "Length", P, "cm"),
      num("widthCm", "Width", P, "cm"),
      num("heightCm", "Height", P, "cm"),
      num("weightKg", "Weight", P, "kg"),
      num("volumeL", "Volume", P, "L"),
      num("reorderLevel", "Reorder level", P),
      num("reorderQty", "Reorder quantity", P),
      num("minStock", "Min stock", P),
      num("maxStock", "Max stock", P),
      num("stackingLimit", "Stacking limit", P),
      bool("fragile", "Fragile", P),
      bool("hazardous", "Hazardous", P),
      text("putawayZone", "Putaway zone", P),
      text("owner", "Customer / owner (3PL)", U),
    ]),
    required: {},
  },
};

// ── Starter categories (seeded only if the type has none yet) ─────

const STARTER_CATEGORIES = {
  it_asset: [
    { name: "Laptop" }, { name: "Desktop" }, { name: "Monitor" }, { name: "Printer" },
    { name: "Network device" }, { name: "Phone / tablet" }, { name: "Server" }, { name: "Accessory" },
  ],
  non_it_asset: [
    { name: "Furniture" }, { name: "Machinery" }, { name: "Appliance" },
    { name: "Vehicle" }, { name: "Tool" }, { name: "Fixture" },
  ],
  apparel: [
    { name: "Tops" },
    { name: "Bottoms" },
    { name: "Dresses" }, { name: "Outerwear" }, { name: "Footwear" }, { name: "Accessories" },
  ],
  jewellery: [
    { name: "Ring" }, { name: "Chain" }, { name: "Necklace" }, { name: "Bangle" },
    { name: "Bracelet" }, { name: "Earring" }, { name: "Pendant" }, { name: "Mangalsutra" },
  ],
  food: [],
  general: [],
};

// ── Presets ───────────────────────────────────────────────────────

const ALL_FEATURES_ON = {
  quickScan: true, inventoryScan: true, orderPicking: true, writeTag: true, killTag: true,
  locateTag: true, wms: true, retail: true, managerAlerts: true, shop: true,
  // Admin-only: SNA switches this on per company in the console.
  catalogExcel: false,
};

const PRESETS = {
  standard: {
    label: "Standard (new signup)",
    types: ["general"],
    features: { retail: false, managerAlerts: false },
    labels: {},
    capabilities: {},
  },
  warehouse: {
    label: "Warehouse",
    types: ["general"],
    features: { retail: false, managerAlerts: false },
    labels: {},
    capabilities: {},
  },
  jewellery: {
    label: "Jewellery retail",
    types: ["jewellery"],
    features: { orderPicking: false },
    labels: {
      warehouse: { singular: "Store", plural: "Stores" },
      rack: { singular: "Counter", plural: "Counters" },
      bin: { singular: "Tray", plural: "Trays" },
      product: { singular: "Design", plural: "Designs" },
    },
    capabilities: {},
  },
  apparel: {
    label: "Apparel retail",
    types: ["apparel"],
    features: {},
    labels: {},
    capabilities: { nesting: { levels: ["carton"] } },
  },
  assets: {
    label: "Asset tracking",
    types: ["it_asset", "non_it_asset"],
    features: { retail: false, managerAlerts: false, orderPicking: false },
    labels: {
      warehouse: { singular: "Site", plural: "Sites" },
      rack: { singular: "Zone", plural: "Zones" },
      bin: { singular: "Spot", plural: "Spots" },
      product: { singular: "Asset model", plural: "Asset models" },
    },
    capabilities: {},
  },
  food: {
    label: "Food",
    types: ["food", "general"],
    features: { retail: false, managerAlerts: false },
    labels: {},
    capabilities: {
      batchTracking: { alertDays: 30, pickRule: "FEFO", blockExpiredDispatch: true, autoExpire: true },
    },
  },
};

const DEFAULT_PRESET = "standard";

// ── Builders ──────────────────────────────────────────────────────

const clone = (v) => JSON.parse(JSON.stringify(v));

/**
 * The full settings doc for a preset. [extraTypes] switches on more types
 * on top of the preset's own (e.g. keep "general" on for a pilot company).
 */
function buildSettings(presetKey = DEFAULT_PRESET, extraTypes = []) {
  const preset = PRESETS[presetKey];
  if (!preset) throw new Error(`Unknown preset '${presetKey}'. Known: ${Object.keys(PRESETS).join(", ")}`);

  const enabled = new Set([...preset.types, ...extraTypes]);
  for (const t of enabled) {
    if (!PRODUCT_TYPES[t]) throw new Error(`Unknown product type '${t}'`);
  }

  const productTypes = {};
  for (const [key, def] of Object.entries(PRODUCT_TYPES)) {
    productTypes[key] = { ...clone(def), enabled: enabled.has(key) };
  }

  return {
    schemaVersion: SCHEMA_VERSION,
    templateVersion: TEMPLATE_VERSION,
    template: presetKey,
    plan: "standard",
    features: { ...ALL_FEATURES_ON, ...preset.features },
    limits: { maxDevices: 10 },
    labels: clone(preset.labels),
    attributeGroups: clone(ATTRIBUTE_GROUPS),
    productTypes,
    capabilities: clone(preset.capabilities),
  };
}

/** Starter categories for the enabled types of a settings doc. */
function starterCategoriesFor(settings) {
  return Object.entries(settings.productTypes)
    .filter(([, def]) => def.enabled)
    .map(([type]) => ({ type, categories: STARTER_CATEGORIES[type] || [] }))
    .filter((x) => x.categories.length > 0);
}

module.exports = {
  TEMPLATE_VERSION,
  DEFAULT_PRESET,
  PRESETS,
  PRODUCT_TYPES,
  ATTRIBUTE_GROUPS,
  STARTER_CATEGORIES,
  buildSettings,
  starterCategoriesFor,
};