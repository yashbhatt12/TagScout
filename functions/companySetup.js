"use strict";

/**
 * Writes a company's settings doc from a template and seeds starter
 * categories. Shared by the signup trigger (index.js) and the admin
 * command (scripts/applyTemplate.js).
 *
 * Safe by default: an existing settings doc is never overwritten unless
 * `overwrite` is passed, and categories are only seeded for product types
 * that have none yet — re-running never duplicates anything.
 */

const { FieldValue } = require("firebase-admin/firestore");
const { buildSettings, starterCategoriesFor, DEFAULT_PRESET } = require("./templates");

const ALREADY_EXISTS = 6; // gRPC status code returned by create() when the doc exists

function companyRef(db, companyId) {
  return db.collection("companies").doc(companyId);
}

function settingsRef(db, companyId) {
  return companyRef(db, companyId).collection("settings").doc("config");
}

/**
 * Writes the settings doc. Returns true if written, false if one already
 * existed and `overwrite` was not set.
 */
async function writeSettings(db, companyId, settings, { overwrite = false } = {}) {
  const ref = settingsRef(db, companyId);
  const data = { ...settings, updatedAt: FieldValue.serverTimestamp() };
  if (overwrite) {
    await ref.set(data);
    return true;
  }
  try {
    await ref.create(data);
    return true;
  } catch (e) {
    if (e.code === ALREADY_EXISTS) return false;
    throw e;
  }
}

function categoryDoc(name, productType, parentId, order) {
  return {
    name,
    nameKey: name.trim().toLowerCase(),
    productType,
    parentId,
    order,
    isActive: true,
    createdAt: FieldValue.serverTimestamp(),
    createdBy: "template",
  };
}

/**
 * Adds the template's starter categories for each enabled type that has
 * no categories yet. Returns how many category docs were created.
 */
async function seedStarterCategories(db, companyId, settings) {
  const col = companyRef(db, companyId).collection("categories");
  const existing = await col.get();
  const typesWithCategories = new Set(existing.docs.map((d) => d.get("productType")));

  const batch = db.batch();
  let created = 0;

  for (const { type, categories } of starterCategoriesFor(settings)) {
    if (typesWithCategories.has(type)) continue;
    categories.forEach((c, i) => {
      const parent = col.doc();
      batch.set(parent, categoryDoc(c.name, type, "", i + 1));
      created++;
      (c.children || []).forEach((child, j) => {
        batch.set(col.doc(), categoryDoc(child, type, parent.id, j + 1));
        created++;
      });
    });
  }

  if (created > 0) await batch.commit();
  return created;
}

/**
 * Full set-up: settings from [preset] (+ [extraTypes] switched on), then
 * starter categories. Categories are only seeded when settings were
 * actually written, so a company that already has settings is untouched.
 */
async function setUpCompany(db, companyId, { preset = DEFAULT_PRESET, extraTypes = [], overwrite = false } = {}) {
  const settings = buildSettings(preset, extraTypes);
  const written = await writeSettings(db, companyId, settings, { overwrite });
  const categories = written ? await seedStarterCategories(db, companyId, settings) : 0;
  return { companyId, preset, written, categories };
}

module.exports = { setUpCompany, writeSettings, seedStarterCategories, settingsRef };