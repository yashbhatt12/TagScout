/**
 * Writes a company's settings doc from a template and seeds starter
 * categories. Shared by the signup trigger (setUpNewCompany) and the admin
 * command (scripts/applyTemplate.js, which uses the built copy in lib/).
 *
 * Safe by default: an existing settings doc is never overwritten unless
 * `overwrite` is passed, and categories are only seeded for product types
 * that have none yet — re-running never duplicates anything.
 *
 * Takes the Firestore instance as a parameter (rather than importing it)
 * so the admin scripts can pass their own.
 */

import {FieldValue, type Firestore} from "firebase-admin/firestore";
import {
  buildSettings,
  starterCategoriesFor,
  DEFAULT_PRESET,
  type CompanySettings,
} from "./templates";

const ALREADY_EXISTS = 6; // gRPC status code returned by create() when the doc exists

export function companyRef(db: Firestore, companyId: string) {
  return db.collection("companies").doc(companyId);
}

export function settingsRef(db: Firestore, companyId: string) {
  return companyRef(db, companyId).collection("settings").doc("config");
}

/**
 * Writes the settings doc. Returns true if written, false if one already
 * existed and `overwrite` was not set.
 */
export async function writeSettings(
  db: Firestore,
  companyId: string,
  settings: CompanySettings,
  {overwrite = false}: { overwrite?: boolean } = {},
): Promise<boolean> {
  const ref = settingsRef(db, companyId);
  const data = {...settings, updatedAt: FieldValue.serverTimestamp()};
  if (overwrite) {
    await ref.set(data);
    return true;
  }
  try {
    await ref.create(data);
    return true;
  } catch (e) {
    if ((e as { code?: unknown }).code === ALREADY_EXISTS) return false;
    throw e;
  }
}

function categoryDoc(name: string, productType: string, parentId: string, order: number) {
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
export async function seedStarterCategories(
  db: Firestore,
  companyId: string,
  settings: Pick<CompanySettings, "productTypes">,
): Promise<number> {
  const col = companyRef(db, companyId).collection("categories");
  const existing = await col.get();
  const typesWithCategories = new Set(existing.docs.map((d) => d.get("productType")));

  const batch = db.batch();
  let created = 0;

  for (const {type, categories} of starterCategoriesFor(settings)) {
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

export interface SetUpOptions {
  preset?: string;
  extraTypes?: string[];
  overwrite?: boolean;
}

/**
 * Full set-up: settings from [preset] (+ [extraTypes] switched on), then
 * starter categories. Categories are only seeded when settings were
 * actually written, so a company that already has settings is untouched.
 */
export async function setUpCompany(
  db: Firestore,
  companyId: string,
  {preset = DEFAULT_PRESET, extraTypes = [], overwrite = false}: SetUpOptions = {},
) {
  const settings = buildSettings(preset, extraTypes);
  const written = await writeSettings(db, companyId, settings, {overwrite});
  const categories = written ? await seedStarterCategories(db, companyId, settings) : 0;
  return {companyId, preset, written, categories};
}
