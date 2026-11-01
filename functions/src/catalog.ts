/**
 * catalogV1: categories and brands (Company Set Up → Categories & brands,
 * product form, Bulk Catalog Creation).
 *
 *   { opId, action, ... }
 *   createCategory    { name, productType, parentId?, order? }   → { id }
 *   renameCategory    { categoryId, name }
 *   setCategoryOrder  { categoryId, order }
 *   setCategoryActive { categoryId, active }
 *   deleteCategory    { categoryId }
 *   findOrCreateBrand { name }   → { id, name, nameKey, isActive, createdBy }
 *   renameBrand       { brandId, name }
 *   setBrandActive    { brandId, active }
 *   deleteBrand       { brandId }
 *
 * Same rules as the app's CatalogLookupsRepository: names 1–60 characters;
 * a category name is unique among its siblings (same type and parent,
 * ignoring case); sub-categories only one level deep and of the parent's
 * type; brand names unique (a deactivated brand is reactivated rather than
 * duplicated); deleting is refused while products use it (deactivate
 * instead) or, for a category, while it has sub-categories.
 * Owners and managers only.
 */

import {onCall, HttpsError} from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import {Timestamp, type CollectionReference, type Firestore} from "firebase-admin/firestore";
import {db, REGION} from "./firebase";
import {body, requireMember} from "./access";
import {requireOpId, runOnce} from "./ops";

const MAX_NAME_LENGTH = 60;
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const TYPE = /^[a-z][a-z0-9_]{0,39}$/;

const nameKeyOf = (name: string) => name.trim().toLowerCase();

function name(value: unknown, what: "Category" | "Brand"): string {
  const t = typeof value === "string" ? value.trim() : "";
  if (!t) throw new HttpsError("invalid-argument", `${what} name is required`);
  if (t.length > MAX_NAME_LENGTH) throw new HttpsError("invalid-argument", `${what} name can be at most ${MAX_NAME_LENGTH} characters`);
  return t;
}

function id(value: unknown, what: string): string {
  if (typeof value !== "string" || !ID.test(value)) throw new HttpsError("invalid-argument", `Missing ${what}.`);
  return value;
}

function bool(value: unknown): boolean {
  if (typeof value !== "boolean") throw new HttpsError("invalid-argument", "active must be true or false.");
  return value;
}

function int(value: unknown, fallback: number): number {
  if (value === undefined || value === null) return fallback;
  if (typeof value !== "number" || !Number.isInteger(value) || Math.abs(value) > 1_000_000) {
    throw new HttpsError("invalid-argument", "Order must be a whole number.");
  }
  return value;
}

async function count(col: CollectionReference, field: string, value: string): Promise<number> {
  return (await col.where(field, "==", value).count().get()).data().count;
}

async function existing(col: CollectionReference, docId: string, what: string) {
  const snap = await col.doc(docId).get();
  if (!snap.exists) throw new HttpsError("not-found", `${what} not found`);
  return snap;
}

export async function runCatalogAction(
  firestore: Firestore,
  companyId: string,
  uid: string,
  data: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const company = firestore.collection("companies").doc(companyId);
  const categories = company.collection("categories");
  const brands = company.collection("brands");
  const products = company.collection("products");

  switch (data.action) {
  // ── Categories ──────────────────────────────────────────────
  case "createCategory": {
    const n = name(data.name, "Category");
    const productType = typeof data.productType === "string" && TYPE.test(data.productType) ? data.productType : "";
    if (!productType) throw new HttpsError("invalid-argument", "Missing product type.");
    const parentId = data.parentId ? id(data.parentId, "parent category") : "";
    const order = int(data.order, 0);
    const all = (await categories.get()).docs;
    if (parentId) {
      const parent = all.find((d) => d.id === parentId);
      if (!parent) throw new HttpsError("not-found", "Parent category not found");
      if (String(parent.get("parentId") ?? "") !== "") {
        throw new HttpsError("invalid-argument", "Sub-categories can't have their own sub-categories");
      }
      if (parent.get("productType") !== productType) {
        throw new HttpsError("invalid-argument", "Parent category belongs to a different product type");
      }
    }
    const key = nameKeyOf(n);
    const clash = all.some((d) => d.get("productType") === productType &&
      String(d.get("parentId") ?? "") === parentId && (d.get("nameKey") ?? nameKeyOf(String(d.get("name") ?? ""))) === key);
    if (clash) throw new HttpsError("already-exists", `Category '${n}' already exists here`);
    const ref = await categories.add({
      name: n, nameKey: key, productType, parentId, order, isActive: true, createdAt: Timestamp.now(), createdBy: uid,
    });
    return {id: ref.id};
  }

  case "renameCategory": {
    const categoryId = id(data.categoryId, "category");
    const n = name(data.name, "Category");
    const all = (await categories.get()).docs;
    const current = all.find((d) => d.id === categoryId);
    if (!current) throw new HttpsError("not-found", "Category not found");
    const key = nameKeyOf(n);
    const clash = all.some((d) => d.id !== categoryId && d.get("productType") === current.get("productType") &&
      String(d.get("parentId") ?? "") === String(current.get("parentId") ?? "") &&
      (d.get("nameKey") ?? nameKeyOf(String(d.get("name") ?? ""))) === key);
    if (clash) throw new HttpsError("already-exists", `Category '${n}' already exists here`);
    await categories.doc(categoryId).update({name: n, nameKey: key});
    return {id: categoryId};
  }

  case "setCategoryOrder": {
    const categoryId = id(data.categoryId, "category");
    await existing(categories, categoryId, "Category");
    await categories.doc(categoryId).update({order: int(data.order, 0)});
    return {id: categoryId};
  }

  case "setCategoryActive": {
    const categoryId = id(data.categoryId, "category");
    await existing(categories, categoryId, "Category");
    await categories.doc(categoryId).update({isActive: bool(data.active)});
    return {id: categoryId};
  }

  case "deleteCategory": {
    const categoryId = id(data.categoryId, "category");
    await existing(categories, categoryId, "Category");
    const children = await count(categories, "parentId", categoryId);
    if (children > 0) {
      throw new HttpsError("failed-precondition", `Delete or move its ${children} sub-categor${children === 1 ? "y" : "ies"} first`);
    }
    const used = (await count(products, "categoryId", categoryId)) + (await count(products, "subCategoryId", categoryId));
    if (used > 0) throw new HttpsError("failed-precondition", `${used} product(s) use this category. Deactivate it instead.`);
    await categories.doc(categoryId).delete();
    return {id: categoryId};
  }

  // ── Brands ──────────────────────────────────────────────────
  case "findOrCreateBrand": {
    const n = name(data.name, "Brand");
    const key = nameKeyOf(n);
    const hit = (await brands.where("nameKey", "==", key).limit(1).get()).docs[0];
    if (hit) {
      if (hit.get("isActive") === false) await hit.ref.update({isActive: true});
      return {
        id: hit.id,
        name: String(hit.get("name") ?? n),
        nameKey: key,
        isActive: true,
        createdBy: String(hit.get("createdBy") ?? ""),
      };
    }
    const ref = await brands.add({name: n, nameKey: key, isActive: true, createdAt: Timestamp.now(), createdBy: uid});
    return {id: ref.id, name: n, nameKey: key, isActive: true, createdBy: uid};
  }

  case "renameBrand": {
    const brandId = id(data.brandId, "brand");
    const n = name(data.name, "Brand");
    await existing(brands, brandId, "Brand");
    const key = nameKeyOf(n);
    const clash = (await brands.where("nameKey", "==", key).limit(2).get()).docs.some((d) => d.id !== brandId);
    if (clash) throw new HttpsError("already-exists", `Brand '${n}' already exists`);
    await brands.doc(brandId).update({name: n, nameKey: key});
    return {id: brandId};
  }

  case "setBrandActive": {
    const brandId = id(data.brandId, "brand");
    await existing(brands, brandId, "Brand");
    await brands.doc(brandId).update({isActive: bool(data.active)});
    return {id: brandId};
  }

  case "deleteBrand": {
    const brandId = id(data.brandId, "brand");
    await existing(brands, brandId, "Brand");
    const used = await count(products, "brandId", brandId);
    if (used > 0) throw new HttpsError("failed-precondition", `${used} product(s) use this brand. Deactivate it instead.`);
    await brands.doc(brandId).delete();
    return {id: brandId};
  }

  default:
    throw new HttpsError("invalid-argument", "Unknown catalog action.");
  }
}

export const catalogV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request, ["owner", "manager"]);
  const opId = requireOpId(data.opId);
  const action = String(data.action ?? "");
  return runOnce(caller.companyId, opId, `catalog.${action}`, caller.uid, async () => {
    const result = await runCatalogAction(db, caller.companyId, caller.uid, data);
    logger.info("Catalog set-up", {companyId: caller.companyId, uid: caller.uid, action, id: result.id});
    return result;
  });
});
