"use strict";

/**
 * Fills test companies with realistic data: warehouses, racks, bins,
 * categories, brands, products of each company's own product types, and
 * stock (units with their inward movements, in GRN-style deliveries).
 *
 *   node scripts/seedTestData.js                 the four ybhatt10+ test companies: 200 products, 2000 units each
 *   node scripts/seedTestData.js --company ybhatt10+jewellery@gmail.com --products 300 --units 5000
 *
 * Options:
 *   --company <owner email | company id>   repeatable; default: the four test companies
 *   --products <n>                         products per company (default 200)
 *   --units <n>                            units of stock per company (default 2000; 0 = none)
 *   --warehouses <n> --racks <n> --bins <n>   per company / per warehouse / per rack (default 3 / 4 / 6)
 *   --key <file>                           service account key (default C:\Users\Microsoft\firebase-keys\tagscout-in-admin.json)
 *   --yes                                  don't ask for confirmation
 *
 * Every write goes through the same server code the app uses (locationsV1,
 * catalogV1, productsV1, batchesV1 and enrollUnitsV1 logic), so names, bin
 * codes, SKUs and units follow the real rules. Test EPCs start "E2FF", so
 * they can't collide with real tags. Safe to re-run: anything that already
 * exists is skipped.
 *
 * Stock totals: the updateSkuAggregate function keeps each product's totals
 * from its movements. After enrolling, the script waits for those updates to
 * settle, then checks every product's totals against its units and repairs
 * any that differ (a burst of thousands can lose a few to contention).
 * Uses the built functions code: run `npm run build` in functions/ first.
 * For test companies only — never point it at a customer's company.
 */

const fs = require("fs");
const path = require("path");
const readline = require("readline");

const TEST_COMPANIES = ["jewellery", "apparel", "warehouse", "assets"].map((p) => `ybhatt10+${p}@gmail.com`);

// ── Arguments ──────────────────────────────────────────────────────

function parseArgs(argv) {
  const args = {
    companies: [], products: 200, units: 2000, warehouses: 3, racks: 4, bins: 6, yes: false,
    key: "C:\\Users\\Microsoft\\firebase-keys\\tagscout-in-admin.json",
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${a} needs a value`);
      return v;
    };
    if (a === "--company") args.companies.push(next());
    else if (a === "--products") args.products = Number(next());
    else if (a === "--units") args.units = Number(next());
    else if (a === "--warehouses") args.warehouses = Number(next());
    else if (a === "--racks") args.racks = Number(next());
    else if (a === "--bins") args.bins = Number(next());
    else if (a === "--key") args.key = next();
    else if (a === "--yes") args.yes = true;
    else throw new Error(`Unknown option: ${a}`);
  }
  for (const k of ["products", "warehouses", "racks", "bins"]) {
    if (!Number.isInteger(args[k]) || args[k] < 0 || args[k] > 5000) throw new Error(`--${k} must be a whole number from 0 to 5000`);
  }
  if (!Number.isInteger(args.units) || args.units < 0 || args.units > 50000) throw new Error("--units must be a whole number from 0 to 50000");
  if (args.warehouses > 9 || args.racks > 26 || args.bins > 99) throw new Error("At most 9 warehouses, 26 racks and 99 bins per rack.");
  if (args.companies.length === 0) args.companies = TEST_COMPANIES;
  return args;
}

// ── Deterministic "random", so a re-run makes the same SKUs ───────

function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}
const pick = (r, list) => list[Math.floor(r() * list.length)];
const between = (r, lo, hi, step = 1) => Math.round((lo + r() * (hi - lo)) / step) * step;

// ── Product catalogues per type ───────────────────────────────────
// Each returns the product fields productsV1 takes, plus category and brand names.

const JEWELLERY = {
  items: {
    Ring: ["Solitaire Ring", "Band Ring", "Cocktail Ring", "Floral Ring", "Twisted Ring"],
    Chain: ["Rope Chain", "Box Chain", "Curb Chain", "Singapore Chain", "Figaro Chain"],
    Necklace: ["Temple Necklace", "Choker", "Rani Haar", "Layered Necklace", "Kundan Necklace"],
    Bangle: ["Plain Bangle", "Kada", "Filigree Bangle", "Stone Bangle"],
    Bracelet: ["Tennis Bracelet", "Link Bracelet", "Charm Bracelet", "Cuff Bracelet"],
    Earring: ["Jhumka", "Stud Earrings", "Hoop Earrings", "Drop Earrings", "Chandbali"],
    Pendant: ["Heart Pendant", "Om Pendant", "Initial Pendant", "Floral Pendant"],
    Mangalsutra: ["Classic Mangalsutra", "Diamond Mangalsutra", "Short Mangalsutra"],
  },
  metals: [["Gold", ["22K", "18K", "14K"], "711319"], ["Silver", ["24K"], "711311"], ["Platinum", ["18K"], "711319"]],
  brands: ["Swarna Heritage", "Kalasha", "Rivaah Craft", "Anmol"],
  collections: ["Bridal", "Daily Wear", "Festive", "Heritage", "Office Wear"],
};

const APPAREL = {
  items: {
    Tops: [["Cotton Shirt", "6205"], ["Polo T-Shirt", "6105"], ["Round Neck T-Shirt", "6109"], ["Linen Kurta", "6211"]],
    Bottoms: [["Slim Jeans", "6203"], ["Chinos", "6203"], ["Palazzo", "6204"], ["Track Pants", "6103"]],
    Dresses: [["A-Line Dress", "6204"], ["Maxi Dress", "6204"], ["Anarkali Suit", "6204"]],
    Outerwear: [["Denim Jacket", "6201"], ["Bomber Jacket", "6201"], ["Nehru Jacket", "6203"]],
    Footwear: [["Leather Loafers", "6403"], ["Canvas Sneakers", "6404"], ["Kolhapuri Chappal", "6403"]],
    Accessories: [["Leather Belt", "4203"], ["Cotton Cap", "6505"], ["Silk Stole", "6214"]],
  },
  colours: ["Black", "Navy", "White", "Olive", "Maroon", "Beige", "Grey", "Mustard"],
  sizes: ["S", "M", "L", "XL"],
  fabrics: ["Cotton", "Linen", "Denim", "Polyester", "Rayon", "Leather"],
  genders: ["Men", "Women", "Unisex", "Kids"],
  brands: ["Urban Thread", "Desi Loom", "Northline", "Kidzo"],
};

const GENERAL = {
  categories: {
    Packaging: [["Corrugated Box", "4819", "box"], ["Stretch Film Roll", "3920", "roll"], ["Packing Tape", "3919", "roll"], ["Bubble Wrap Roll", "3921", "roll"]],
    Fasteners: [["Hex Bolt", "7318", "box"], ["Wood Screw", "7318", "box"], ["Wall Anchor", "3926", "pack"], ["Spring Washer", "7318", "pack"]],
    Electrical: [["Copper Cable", "8544", "roll"], ["MCB Switch", "8536", "piece"], ["LED Panel", "9405", "piece"], ["Extension Board", "8536", "piece"]],
    Plumbing: [["Ball Valve", "8481", "piece"], ["PVC Pipe", "3917", "piece"], ["Elbow Joint", "3917", "pack"], ["Water Tap", "8481", "piece"]],
    Tools: [["Spanner Set", "8204", "set"], ["Hammer", "8205", "piece"], ["Measuring Tape", "9017", "piece"], ["Drill Bit Set", "8207", "set"]],
    "Spare parts": [["Ball Bearing", "8482", "piece"], ["V-Belt", "4010", "piece"], ["Oil Seal", "4016", "piece"], ["Gear Wheel", "8483", "piece"]],
  },
  sizes: ["Small", "Medium", "Large", "6 mm", "10 mm", "16 mm", "25 m", "50 m"],
  brands: ["Indus Packaging", "FastenPro", "VoltLine", "AquaFlow"],
};

const IT_ASSET = {
  items: {
    Laptop: [["Latitude 5440", "Dell", "8471"], ["ThinkPad E14", "Lenovo", "8471"], ["ProBook 450", "HP", "8471"]],
    Desktop: [["OptiPlex 7010", "Dell", "8471"], ["ThinkCentre M70", "Lenovo", "8471"], ["ProDesk 400", "HP", "8471"]],
    Monitor: [["P2422H 24in", "Dell", "8528"], ["ThinkVision T24", "Lenovo", "8528"], ["S24C 24in", "Samsung", "8528"]],
    Printer: [["LaserJet M234", "HP", "8443"], ["EcoTank L3250", "Epson", "8443"], ["VersaLink B405", "Xerox", "8443"]],
    "Network device": [["Catalyst 1000 Switch", "Cisco", "8517"], ["Archer AX55 Router", "TP-Link", "8517"], ["UniFi U6 Access Point", "Ubiquiti", "8517"]],
    "Phone / tablet": [["Galaxy Tab A9", "Samsung", "8471"], ["iPhone 15", "Apple", "8517"], ["Redmi Note 13", "Xiaomi", "8517"]],
    Server: [["PowerEdge R660", "Dell", "8471"], ["ProLiant DL360", "HPE", "8471"]],
    Accessory: [["Docking Station", "Dell", "8473"], ["Wireless Keyboard Set", "Logitech", "8471"], ["UPS 1kVA", "APC", "8504"]],
  },
  processors: ["Intel i5", "Intel i7", "AMD Ryzen 5", "AMD Ryzen 7"],
  rams: ["8 GB", "16 GB", "32 GB"],
  storages: ["256 GB SSD", "512 GB SSD", "1 TB SSD"],
};

const NON_IT_ASSET = {
  items: {
    Furniture: [["Ergonomic Chair", "Featherlite", "9401"], ["Workstation Desk", "Godrej", "9403"], ["Storage Cabinet", "Godrej", "9403"]],
    Machinery: [["Packing Machine", "Sealtech", "8422"], ["Label Printer Rewinder", "Labelmate", "8479"]],
    Appliance: [["Split AC 1.5T", "Voltas", "8415"], ["Water Dispenser", "Blue Star", "8418"], ["Refrigerator", "Whirlpool", "8418"]],
    Vehicle: [["Electric Scooter", "Ather", "8711"], ["Pallet Truck", "Godrej", "8427"]],
    Tool: [["Cordless Drill", "Bosch", "8467"], ["Angle Grinder", "Bosch", "8467"]],
    Fixture: [["LED High Bay Light", "Philips", "9405"], ["Display Rack", "Godrej", "9403"]],
  },
  materials: ["Steel", "Aluminium", "Wood", "Plastic"],
  colours: ["Black", "Grey", "White", "Blue"],
};

/** Builds product number [i] of [type] for the company. Returns null for types with no catalogue (e.g. food). */
function makeProduct(type, i, r) {
  const n = String(i + 1).padStart(4, "0");
  const money = (lo, hi) => between(r, lo, hi, 10);
  switch (type) {
  case "jewellery": {
    const category = pick(r, Object.keys(JEWELLERY.items));
    const name = pick(r, JEWELLERY.items[category]);
    const [metal, purities, hsn] = pick(r, JEWELLERY.metals);
    const purity = pick(r, purities);
    const price = metal === "Silver" ? money(1500, 25000) : money(18000, 350000);
    return {
      sku: `JW-${n}`, title: `${purity} ${metal} ${name}`, shortName: `${name}`.slice(0, 20), hsnCode: hsn,
      unitOfMeasure: category === "Earring" ? "pair" : "piece", category, brand: pick(r, JEWELLERY.brands),
      customFields: {
        designCode: `D${category.slice(0, 2).toUpperCase()}-${between(r, 1000, 9999)}`, metal, purity,
        collection: pick(r, JEWELLERY.collections), makingChargeType: pick(r, ["Per gram", "Flat"]),
        makingCharge: money(300, 1500), mrp: price, sellingPrice: Math.round(price * 0.95), gstRate: 3,
      },
    };
  }
  case "apparel": {
    const category = pick(r, Object.keys(APPAREL.items));
    const [name, hsn] = pick(r, APPAREL.items[category]);
    const colour = pick(r, APPAREL.colours);
    const size = category === "Footwear" ? String(between(r, 6, 11)) : category === "Accessories" ? "Free" : pick(r, APPAREL.sizes);
    const mrp = money(499, 4999);
    return {
      sku: `AP-${n}`, title: `${colour} ${name} ${size}`, shortName: `${name}`.slice(0, 20), hsnCode: hsn,
      unitOfMeasure: category === "Footwear" ? "pair" : "piece", category, brand: pick(r, APPAREL.brands),
      customFields: {
        styleCode: `ST-${between(r, 100, 999)}`, size, colour, gender: pick(r, APPAREL.genders), fabric: pick(r, APPAREL.fabrics),
        season: pick(r, ["Summer", "Winter", "Festive", "All season"]), mrp, sellingPrice: Math.round(mrp * 0.9), gstRate: mrp > 1000 ? 12 : 5,
      },
    };
  }
  case "general": {
    const category = pick(r, Object.keys(GENERAL.categories));
    const [name, hsn, uom] = pick(r, GENERAL.categories[category]);
    const size = pick(r, GENERAL.sizes);
    const cost = money(50, 5000);
    return {
      sku: `WH-${n}`, title: `${name} ${size}`, shortName: `${name}`.slice(0, 20), hsnCode: hsn, unitOfMeasure: uom,
      category, brand: pick(r, GENERAL.brands),
      customFields: {
        packSize: size, unitsPerCase: pick(r, [6, 10, 12, 20, 24, 50]), weightKg: between(r, 1, 250) / 10,
        reorderLevel: between(r, 5, 50), reorderQty: between(r, 20, 200, 10), fragile: r() < 0.2, hazardous: false,
        costPrice: cost, mrp: Math.round(cost * 1.4), gstRate: 18,
      },
    };
  }
  case "it_asset": {
    const category = pick(r, Object.keys(IT_ASSET.items));
    const [model, make, hsn] = pick(r, IT_ASSET.items[category]);
    const computer = ["Laptop", "Desktop", "Server"].includes(category);
    const fields = {make, model};
    if (computer) Object.assign(fields, {processor: pick(r, IT_ASSET.processors), ram: pick(r, IT_ASSET.rams), storage: pick(r, IT_ASSET.storages)});
    return {
      sku: `IT-${n}`, title: `${make} ${model}${computer ? ` ${fields.ram}` : ""}`, shortName: model.slice(0, 20), hsnCode: hsn,
      unitOfMeasure: "piece", category, brand: make, customFields: fields,
    };
  }
  case "non_it_asset": {
    const category = pick(r, Object.keys(NON_IT_ASSET.items));
    const [model, make, hsn] = pick(r, NON_IT_ASSET.items[category]);
    return {
      sku: `NA-${n}`, title: `${make} ${model}`, shortName: model.slice(0, 20), hsnCode: hsn, unitOfMeasure: "piece",
      category, brand: make,
      customFields: {make, model, material: pick(r, NON_IT_ASSET.materials), colour: pick(r, NON_IT_ASSET.colours)},
    };
  }
  default:
    return null;
  }
}

/** Categories the generator uses for a type (created if the company doesn't have them). */
function categoryNames(type) {
  return {
    jewellery: Object.keys(JEWELLERY.items), apparel: Object.keys(APPAREL.items), general: Object.keys(GENERAL.categories),
    it_asset: Object.keys(IT_ASSET.items), non_it_asset: Object.keys(NON_IT_ASSET.items),
  }[type] ?? [];
}

// ── Seeding ───────────────────────────────────────────────────────

const PLACES = [["Andheri", "AND"], ["Vasai", "VAS"], ["Thane", "THA"], ["Borivali", "BOR"], ["Powai", "POW"],
  ["Navi Mumbai", "NVM"], ["Bhiwandi", "BHI"], ["Pune", "PUN"], ["Nashik", "NSK"]];

/**
 * Seeds one company. [lib] holds the server's action functions; [log] prints a line.
 * Returns counts of what was created and skipped.
 */
async function seedCompany(lib, db, companyId, opts, log = () => undefined) {
  const actor = "sna-seed";
  const company = db.collection("companies").doc(companyId);
  const settings = (await company.collection("settings").doc("config").get()).data() ?? {};
  const label = (key, fallback) => settings.labels?.[key]?.singular || fallback;
  const L = {warehouse: label("warehouse", "Warehouse"), rack: label("rack", "Rack"), bin: label("bin", "Bin")};
  const types = Object.entries(settings.productTypes ?? {general: {enabled: true}})
    .filter(([, t]) => t.enabled !== false).map(([k]) => k)
    .filter((t) => categoryNames(t).length > 0);
  const counts = {warehouses: 0, racks: 0, bins: 0, categories: 0, brands: 0, products: 0, skipped: 0};
  const exists = (e) => e && e.code === "already-exists";

  // Locations
  for (const [place, code] of PLACES.slice(0, opts.warehouses)) {
    const name = `${place} ${L.warehouse}`;
    let warehouseId;
    try {
      warehouseId = (await lib.location(db, companyId, {action: "createWarehouse", name, address: `${place}, Maharashtra`})).id;
      counts.warehouses++;
    } catch (e) {
      if (!exists(e)) throw e;
      warehouseId = (await company.collection("warehouses").where("name", "==", name).limit(1).get()).docs[0]?.id;
      counts.skipped++;
      if (!warehouseId) continue;
    }
    for (let ri = 0; ri < opts.racks; ri++) {
      const letter = String.fromCharCode(65 + ri);
      const rackName = `${L.rack} ${letter}`;
      let rackId;
      try {
        rackId = (await lib.location(db, companyId, {action: "createRack", warehouseId, name: rackName})).id;
        counts.racks++;
      } catch (e) {
        if (!exists(e)) throw e;
        rackId = (await company.collection("warehouses").doc(warehouseId).collection("racks").where("name", "==", rackName).limit(1).get()).docs[0]?.id;
        counts.skipped++;
        if (!rackId) continue;
      }
      for (let bi = 1; bi <= opts.bins; bi++) {
        try {
          await lib.location(db, companyId, {action: "createBin", warehouseId, rackId, binCode: `${code}-${letter}${bi}`, name: `${L.bin} ${letter}${bi}`});
          counts.bins++;
        } catch (e) {
          if (!exists(e)) throw e;
          counts.skipped++;
        }
      }
    }
  }
  log(`  locations: ${counts.warehouses} ${L.warehouse.toLowerCase()}s, ${counts.racks} ${L.rack.toLowerCase()}s, ${counts.bins} ${L.bin.toLowerCase()}s created`);

  // Categories (by type and name) and brands (by name)
  const categoryIds = new Map();
  for (const doc of (await company.collection("categories").get()).docs) {
    if (!doc.get("parentId")) categoryIds.set(`${doc.get("productType")}|${String(doc.get("name")).toLowerCase()}`, doc.id);
  }
  for (const type of types) {
    for (const name of categoryNames(type)) {
      const key = `${type}|${name.toLowerCase()}`;
      if (categoryIds.has(key)) continue;
      const id = (await lib.catalog(db, companyId, actor, {action: "createCategory", name, productType: type})).id;
      categoryIds.set(key, id);
      counts.categories++;
    }
  }
  const brandIds = new Map();
  const brandId = async (name) => {
    if (!brandIds.has(name)) {
      const before = (await company.collection("brands").where("nameKey", "==", name.toLowerCase()).limit(1).get()).size;
      brandIds.set(name, (await lib.catalog(db, companyId, actor, {action: "findOrCreateBrand", name})).id);
      if (!before) counts.brands++;
    }
    return brandIds.get(name);
  };

  // Products, split across the company's types
  const r = rng([...companyId].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7));
  const perType = types.map((t, i) => [t, Math.floor(opts.products / types.length) + (i < opts.products % types.length ? 1 : 0)]);
  for (const [type, howMany] of perType) {
    for (let i = 0; i < howMany; i++) {
      const p = makeProduct(type, i, r);
      if (!p) continue;
      const {category, brand, ...fields} = p;
      try {
        await lib.product(db, companyId, actor, {
          action: "create",
          product: {...fields, productType: type, status: "active", categoryId: categoryIds.get(`${type}|${category.toLowerCase()}`) ?? "", brandId: await brandId(brand)},
        });
        counts.products++;
      } catch (e) {
        if (!exists(e)) throw new Error(`${fields.sku}: ${e.message}`);
        counts.skipped++;
      }
    }
    log(`  ${type}: ${howMany} products done`);
  }
  log(`  ${counts.categories} categories and ${counts.brands} brands added; ${counts.skipped} already there`);
  return counts;
}

// ── Stock ─────────────────────────────────────────────────────────

const ACTOR = "sna-seed";
const DAY = 86400000;
const isoDay = (ms) => new Date(ms).toISOString().slice(0, 10);

/** Per-piece values for one unit of [product], matching the company's unit fields. */
function unitFields(type, product, r, now) {
  const pf = product.customFields ?? {};
  const purchase = now - between(r, 30, 900) * DAY;
  switch (type) {
  case "jewellery": {
    const gross = between(r, 20, 600) / 10;
    const fields = {grossWeight: gross, netWeight: Math.round(gross * (0.82 + r() * 0.15) * 10) / 10,
      huid: Array.from({length: 6}, () => "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Math.floor(r() * 32)]).join("")};
    if (r() < 0.3) Object.assign(fields, {stoneWeight: between(r, 5, 300) / 100, stoneWeightUnit: "ct", stoneType: pick(r, ["Diamond", "Ruby", "Emerald", "CZ"])});
    return {fields, price: typeof pf.mrp === "number" ? Math.round(pf.mrp * (0.95 + r() * 0.1)) : null};
  }
  case "apparel":
    return {fields: {}, price: typeof pf.sellingPrice === "number" ? pf.sellingPrice : null};
  case "general":
    return {fields: {}, price: null};
  case "it_asset":
  case "non_it_asset": {
    const cost = between(r, 8000, 180000, 100);
    return {
      fields: {
        purchaseDate: isoDay(purchase), vendor: pick(r, ["Rashi Peripherals", "Ingram Micro", "Redington", "Local dealer"]),
        invoiceNo: `INV-${between(r, 10000, 99999)}`, baseCost: cost, gstAmount: Math.round(cost * 0.18),
        warrantyEndDate: isoDay(purchase + pick(r, [365, 730, 1095]) * DAY),
        department: pick(r, ["Accounts", "Sales", "Operations", "IT", "HR", "Stores"]),
        depreciationMethod: pick(r, ["Straight line", "Written down value"]), usefulLifeYears: pick(r, [3, 5, 8]),
      },
      price: null,
    };
  }
  default:
    return {fields: {}, price: null};
  }
}

/** How many units arrive together in one delivery of this type. */
const deliverySize = (type, r) => ({jewellery: between(r, 1, 6), apparel: between(r, 2, 15), general: between(r, 5, 30)}[type] ?? between(r, 1, 5));

/**
 * Enrolls [opts.units] units across the company's products and bins, through
 * the server's enrollment code. Deliveries are deterministic, so a re-run
 * finds the same EPCs already enrolled and adds nothing.
 */
async function seedStock(lib, db, companyId, opts, log = () => undefined) {
  const company = db.collection("companies").doc(companyId);
  const products = (await company.collection("products").get()).docs
    .map((d) => ({id: d.id, ...d.data()}))
    .filter((p) => p.status !== "discontinued")
    .sort((a, b) => String(a.sku).localeCompare(String(b.sku)));
  const bins = [];
  for (const wh of (await company.collection("warehouses").get()).docs) {
    for (const rack of (await wh.ref.collection("racks").get()).docs) {
      for (const b of (await rack.ref.collection("bins").get()).docs) bins.push({warehouseId: wh.id, rackId: rack.id, binId: b.id});
    }
  }
  if (!products.length || !bins.length) {
    log("  stock: skipped (no products or no bins)");
    return {enrolled: 0, already: 0, failed: 0};
  }
  bins.sort((a, b) => `${a.warehouseId}${a.rackId}${a.binId}`.localeCompare(`${b.warehouseId}${b.rackId}${b.binId}`));

  const settings = (await company.collection("settings").doc("config").get()).data() ?? {};
  const hasBatches = (type) => {
    const t = settings.productTypes?.[type];
    const groups = new Set(t?.groups ?? []);
    return (settings.attributeGroups ?? []).some((g) => groups.has(g.id) && g.capability === "batchTracking");
  };
  const caller = {uid: ACTOR, companyId, role: "owner"};
  const hash = [...companyId].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 11).toString(16).toUpperCase().padStart(8, "0").slice(-6);
  const r = rng([...companyId].reduce((h, c) => (h * 37 + c.charCodeAt(0)) >>> 0, 3));
  const now = Date.UTC(2026, 10, 1); // fixed, so a re-run produces the same deliveries
  const counts = {enrolled: 0, already: 0, failed: 0, batches: 0};
  let unit = 0;
  let delivery = 0;
  const started = Date.now();

  while (unit < opts.units) {
    const product = products[delivery % products.length]; // every product gets stock
    const type = String(product.productType || "general");
    // First round: one delivery per product, small enough that every product gets stock.
    const cap = delivery < products.length ? Math.max(1, Math.floor(opts.units / products.length)) : Infinity;
    const size = Math.min(deliverySize(type, r), cap, opts.units - unit);
    const bin = pick(r, bins);
    const grn = `SEED-GRN-${String(Math.floor(delivery / 8) + 1).padStart(4, "0")}`;

    let batchId = "";
    if (hasBatches(type) && r() < 0.7) {
      const mfg = now - between(r, 15, 240) * DAY;
      try {
        const b = await lib.batch(db, caller, {productId: product.id, batchNo: `L${isoDay(mfg).slice(2, 7).replace("-", "")}-${between(r, 10, 99)}`,
          mfgDate: isoDay(mfg), expiryDate: isoDay(mfg + between(r, 365, 900) * DAY)});
        batchId = b.id;
        counts.batches++;
      } catch {
        batchId = ""; // same lot number seen with other dates: enroll without a batch
      }
    }

    const units = [];
    for (let k = 0; k < size; k++, unit++) {
      const {fields, price} = unitFields(type, product, r, now);
      if (type === "general") Object.assign(fields, {grnNo: grn, grnDate: isoDay(now - between(r, 1, 60) * DAY)});
      units.push({
        epc: `E2FF${hash}${unit.toString(16).toUpperCase().padStart(14, "0")}`,
        serialNumber: type === "it_asset" || type === "non_it_asset" ? `SN${hash}${String(unit).padStart(6, "0")}` : "",
        ...(price ? {price} : {}),
        customFields: type === "it_asset" || type === "non_it_asset" ? {assetTag: `AT-${String(unit + 1).padStart(5, "0")}`, ...fields} : fields,
      });
    }
    const summary = await lib.enroll(db, companyId, ACTOR, {
      productId: product.id, warehouseId: bin.warehouseId, rackId: bin.rackId, binId: bin.binId, batchId,
      referenceType: "grn", referenceId: grn, notes: "Test data", units,
    });
    for (const res of summary.results) {
      if (res.outcome === "ENROLLED") counts.enrolled++;
      else if (res.outcome === "ALREADY_ENROLLED") counts.already++;
      else counts.failed++;
    }
    delivery++;
    if (delivery % 50 === 0) log(`  stock: ${unit} of ${opts.units} units...`);
  }
  log(`  stock: ${counts.enrolled} units enrolled in ${delivery} deliveries (${counts.already} already there, ${counts.failed} failed)` +
    `${counts.batches ? `, ${counts.batches} batched` : ""} — ${Math.round((Date.now() - started) / 1000)} s`);
  return counts;
}

/** Statuses counted as stock (same as the app and updateSkuAggregate). */
const STOCK_STATUSES = new Set(["in_stock", "picked"]);

/**
 * Waits until the stock-total updates triggered by the new movements stop
 * changing, then compares every product's totals with its units and fixes
 * any difference. [waitMs] = 0 skips the wait (tests).
 */
async function settleAndRepairTotals(db, companyId, log = () => undefined, {waitMs = 8000, maxWaitMs = 300000} = {}) {
  const company = db.collection("companies").doc(companyId);
  const sum = async () => (await company.collection("inventory_sku").get()).docs
    .reduce((s, d) => s + (Number(d.get("totalQuantity")) || 0), 0);
  if (waitMs > 0) {
    const start = Date.now();
    let last = await sum();
    for (;;) {
      await new Promise((resolve) => setTimeout(resolve, waitMs));
      const now = await sum();
      if (now === last || Date.now() - start > maxWaitMs) break;
      last = now;
    }
  }

  const expected = new Map(); // productId → {sku, byLocation}
  for (const u of (await company.collection("inventory_units").get()).docs) {
    if (!u.get("productId") || !STOCK_STATUSES.has(u.get("status"))) continue;
    const e = expected.get(u.get("productId")) ?? {sku: u.get("sku"), byLocation: {}};
    const key = `${u.get("currentWarehouseId")}/${u.get("currentBinCode")}`;
    e.byLocation[key] = (e.byLocation[key] ?? 0) + 1;
    expected.set(u.get("productId"), e);
  }
  const actual = new Map((await company.collection("inventory_sku").get()).docs.map((d) => [d.id, d.data()]));
  const {FieldValue} = require("firebase-admin/firestore");
  let fixed = 0;
  let batch = db.batch();
  let ops = 0;
  for (const id of new Set([...expected.keys(), ...actual.keys()])) {
    const want = expected.get(id)?.byLocation ?? {};
    const have = actual.get(id)?.byLocation ?? {};
    const keys = new Set([...Object.keys(want), ...Object.keys(have)]);
    if ([...keys].every((k) => (want[k] ?? 0) === (have[k] ?? 0))) continue;
    const total = Object.values(want).reduce((a, b) => a + b, 0);
    batch.set(company.collection("inventory_sku").doc(id), {
      productId: id, sku: expected.get(id)?.sku ?? actual.get(id)?.sku ?? "", totalQuantity: total, byLocation: want,
      lastComputedAt: FieldValue.serverTimestamp(),
    });
    fixed++;
    if (++ops === 400) {
      await batch.commit();
      batch = db.batch();
      ops = 0;
    }
  }
  if (ops > 0) await batch.commit();
  const units = [...expected.values()].reduce((s, e) => s + Object.values(e.byLocation).reduce((a, b) => a + b, 0), 0);
  log(`${units} units in stock across ${expected.size} products; ${fixed ? `${fixed} product total(s) repaired` : "all totals correct"}`);
  return {units, products: expected.size, fixed};
}

// ── Main ──────────────────────────────────────────────────────────

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!fs.existsSync(args.key)) throw new Error(`Key not found: ${args.key}`);
  const key = JSON.parse(fs.readFileSync(args.key, "utf8"));
  // The server code starts Firebase itself from these, with the admin key.
  process.env.GOOGLE_APPLICATION_CREDENTIALS = path.resolve(args.key);
  process.env.GCLOUD_PROJECT = key.project_id;

  const {getAuth} = require("firebase-admin/auth");
  const {getFirestore} = require("firebase-admin/firestore");
  const lib = {
    location: require("../lib/locations").runLocationAction,
    catalog: require("../lib/catalog").runCatalogAction,
    product: require("../lib/products").runProductAction,
    enroll: require("../lib/enroll").enrollUnits,
    batch: require("../lib/stock").findOrCreateBatch,
  };
  const {readMemberClaims} = require("../lib/members");
  const db = getFirestore();

  const targets = [];
  for (const c of args.companies) {
    if (c.includes("@")) {
      const user = await getAuth().getUserByEmail(c.trim().toLowerCase()).catch(() => null);
      const claims = user && readMemberClaims(user.customClaims);
      if (!claims || claims.role !== "owner") throw new Error(`${c} isn't a company owner's login.`);
      targets.push([c, claims.companyId]);
    } else {
      targets.push([c, c]);
    }
  }

  console.log(`Project: ${key.project_id}`);
  console.log(`Seeding ${targets.length} compan${targets.length === 1 ? "y" : "ies"}: ${targets.map((t) => t[0]).join(", ")}`);
  console.log(`Each: ${args.warehouses} warehouses × ${args.racks} racks × ${args.bins} bins, ${args.products} products, ${args.units} units of stock.`);
  if (!args.yes) {
    const rl = readline.createInterface({input: process.stdin, output: process.stdout});
    const answer = await new Promise((resolve) => rl.question("Test companies only. Type YES to continue: ", (a) => {
      rl.close();
      resolve(a.trim());
    }));
    if (answer !== "YES") return console.log("Stopped. Nothing created.");
  }

  for (const [labelText, companyId] of targets) {
    console.log(`\n${labelText} (${companyId})`);
    await seedCompany(lib, db, companyId, args, console.log);
    if (args.units > 0) {
      await seedStock(lib, db, companyId, args, console.log);
    }
  }
  if (args.units > 0) {
    console.log("\nWaiting for stock totals to finish updating, then checking them...");
    for (const [labelText, companyId] of targets) {
      await settleAndRepairTotals(db, companyId, (line) => console.log(`  ${labelText}: ${line}`));
    }
  }
  console.log("\nDone. Open the app or the dashboard to see the data.");
}

if (require.main === module) {
  main().catch((e) => {
    console.error(`\nError: ${e.message}`);
    process.exitCode = 1;
  });
}

module.exports = {seedCompany, seedStock, settleAndRepairTotals, makeProduct, parseArgs};
