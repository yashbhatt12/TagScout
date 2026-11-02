/**
 * productsV1: the product catalog (Company Set Up → Products, the product
 * form, Bulk Catalog Creation).
 *
 *   { opId, action, ... }
 *   create       { product }                  → { id }
 *   update       { productId, product }       (every editable field; not the SKU)
 *   updateBasic  { productId, title, description, unitOfMeasure }
 *   changeSku    { productId, sku }           → { unitsUpdated }
 *   setImage     { productId, imageUrl }      ("" removes it)
 *   delete       { productId }
 *   backfillSkuKeys {}                        → { backfilled }   (one-off, older products)
 *
 * Same rules as the app's ProductRepository: SKU required and unique
 * (ignoring case) through /sku_index, guarded in a transaction; full name and
 * a 4/6/8-digit HSN code required; short name ≤ 20; known product type, unit
 * of measure and status; blank custom values dropped. Changing a SKU moves
 * its index entry and copies the new SKU onto every unit (movements keep the
 * old one). Delete is refused while any unit references the product, and
 * removes its photos. Owners and managers only.
 */

import {onCall, HttpsError} from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import {getStorage} from "firebase-admin/storage";
import {Timestamp, type DocumentReference, type Firestore} from "firebase-admin/firestore";
import {db, REGION} from "./firebase";
import {body, requireMember} from "./access";
import {cleanCustomFields} from "./enroll";
import {requireOpId, runOnce} from "./ops";

const SHORT_NAME_MAX = 20;
const HSN_FIELD_KEY = "hsnCode";
const HSN_RULE = "HSN code must be 4, 6 or 8 digits";
const PRODUCT_TYPES = new Set(["it_asset", "non_it_asset", "apparel", "jewellery", "food", "general"]);
const UNITS = new Set(["piece", "pair", "set", "dozen", "pack", "box", "case", "carton", "bag", "bottle", "roll", "pallet",
  "gram", "kilogram", "carat", "millilitre", "litre", "centimetre", "metre", "square_metre"]);
const STATUSES = new Set(["active", "discontinued"]);
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const BATCH = 400;

/** Same as Product.skuKeyOf in the app. */
export const skuKeyOf = (sku: string) => sku.trim().toLowerCase();

/** Java's URLEncoder.encode(value, "UTF-8"), which the app uses for document ids. */
export function javaUrlEncode(value: string): string {
  return encodeURIComponent(value)
    .replace(/%20/g, "+")
    .replace(/[!'()~]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase());
}

/** Same as ProductRepository.indexDocId: "k_" + URL-encoded key. */
export function indexDocId(skuKey: string): string {
  return "k_" + javaUrlEncode(skuKey);
}

const normaliseHsn = (raw: string) => raw.replace(/[\s.-]/g, "");
const isValidHsn = (code: string) => [4, 6, 8].includes(code.length) && /^[0-9]+$/.test(code);

function text(value: unknown, max: number, what: string): string {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string") throw new HttpsError("invalid-argument", `${what} must be text.`);
  const t = value.trim();
  if (t.length > max) throw new HttpsError("invalid-argument", `${what} is too long (max ${max}).`);
  return t;
}

function optionalId(value: unknown, what: string): string {
  if (value === undefined || value === null || value === "") return "";
  if (typeof value !== "string" || !ID.test(value)) throw new HttpsError("invalid-argument", `Invalid ${what}.`);
  return value;
}

function requireId(value: unknown, what: string): string {
  if (typeof value !== "string" || !ID.test(value)) throw new HttpsError("invalid-argument", `Missing ${what}.`);
  return value;
}

const duplicate = (sku: string) => new HttpsError("already-exists", `A product with SKU '${sku}' already exists`);

/**
 * The editable catalog fields, checked and normalised the way the app's
 * validateCore + editableData do. imageUrl is never taken from here: only
 * setImage changes it.
 */
function editable(raw: unknown): Record<string, unknown> {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) throw new HttpsError("invalid-argument", "Missing product.");
  const p = raw as Record<string, unknown>;
  const title = text(p.title, 200, "Full name");
  if (!title) throw new HttpsError("invalid-argument", "Full name is required");
  const hsnRaw = text(p.hsnCode, 20, "HSN code");
  if (!hsnRaw) throw new HttpsError("invalid-argument", "HSN code is required");
  const hsnCode = normaliseHsn(hsnRaw);
  if (!isValidHsn(hsnCode)) throw new HttpsError("invalid-argument", HSN_RULE);
  const shortName = text(p.shortName, 200, "Short name");
  if (shortName.length > SHORT_NAME_MAX) {
    throw new HttpsError("invalid-argument", `Short name can be at most ${SHORT_NAME_MAX} characters`);
  }
  const productType = text(p.productType, 40, "Product type") || "general";
  if (!PRODUCT_TYPES.has(productType)) throw new HttpsError("invalid-argument", `Unknown product type '${productType}'`);
  const unitOfMeasure = text(p.unitOfMeasure, 40, "Unit of measure") || "piece";
  if (!UNITS.has(unitOfMeasure)) throw new HttpsError("invalid-argument", `Unknown unit of measure '${unitOfMeasure}'`);
  const status = text(p.status, 20, "Status") || "active";
  if (!STATUSES.has(status)) throw new HttpsError("invalid-argument", `Unknown status '${status}'`);
  const customFields = cleanCustomFields(p.customFields);
  delete customFields[HSN_FIELD_KEY];
  return {
    title,
    hsnCode,
    shortName,
    productType,
    categoryId: optionalId(p.categoryId, "category"),
    subCategoryId: optionalId(p.subCategoryId, "sub-category"),
    brandId: optionalId(p.brandId, "brand"),
    description: text(p.description, 2000, "Description"),
    barcode: text(p.barcode, 100, "Barcode"),
    unitOfMeasure,
    status,
    customFields,
  };
}

/** Products not in sku_index yet (older ones): match on skuKey, then on the exact SKU text. */
async function unindexedMatch(products: FirebaseFirestore.CollectionReference, key: string, sku: string, excludeId: string | null) {
  const byKey = await products.where("skuKey", "==", key).limit(2).get();
  if (byKey.docs.some((d) => d.id !== excludeId)) return true;
  const byText = await products.where("sku", "==", sku).limit(2).get();
  return byText.docs.some((d) => d.id !== excludeId);
}

async function mustExist(ref: DocumentReference) {
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError("not-found", "Product not found");
  return snap;
}

export async function runProductAction(
  firestore: Firestore,
  companyId: string,
  uid: string,
  data: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const company = firestore.collection("companies").doc(companyId);
  const products = company.collection("products");
  const index = company.collection("sku_index");
  const now = Timestamp.now();

  switch (data.action) {
  case "create": {
    const fields = editable(data.product);
    const sku = text((data.product as Record<string, unknown>).sku, 100, "SKU");
    if (!sku) throw new HttpsError("invalid-argument", "SKU is required");
    const key = skuKeyOf(sku);
    if (await unindexedMatch(products, key, sku, null)) throw duplicate(sku);
    const productRef = products.doc();
    const indexRef = index.doc(indexDocId(key));
    await firestore.runTransaction(async (txn) => {
      if ((await txn.get(indexRef)).exists) throw duplicate(sku);
      txn.set(productRef, {
        ...fields, imageUrl: "", sku, skuKey: key, trackingMode: "unit", isActive: true,
        createdAt: now, createdBy: uid, updatedAt: now, updatedBy: uid,
      });
      txn.set(indexRef, {productId: productRef.id, sku});
    });
    return {id: productRef.id};
  }

  case "update": {
    const productId = requireId(data.productId, "product");
    const fields = editable(data.product);
    await mustExist(products.doc(productId));
    await products.doc(productId).update({...fields, updatedAt: now, updatedBy: uid});
    return {id: productId};
  }

  case "updateBasic": {
    const productId = requireId(data.productId, "product");
    const title = text(data.title, 200, "Full name");
    if (!title) throw new HttpsError("invalid-argument", "Full name is required");
    const unitOfMeasure = text(data.unitOfMeasure, 40, "Unit of measure") || "piece";
    if (!UNITS.has(unitOfMeasure)) throw new HttpsError("invalid-argument", `Unknown unit of measure '${unitOfMeasure}'`);
    await mustExist(products.doc(productId));
    await products.doc(productId).update({
      title, description: text(data.description, 2000, "Description"), unitOfMeasure, updatedAt: now, updatedBy: uid,
    });
    return {id: productId};
  }

  case "changeSku": {
    const productId = requireId(data.productId, "product");
    const sku = text(data.sku, 100, "SKU");
    if (!sku) throw new HttpsError("invalid-argument", "SKU is required");
    const productRef = products.doc(productId);
    const current = await mustExist(productRef);
    const currentSku = String(current.get("sku") ?? "");
    if (currentSku === sku) return {unitsUpdated: 0};
    const newKey = skuKeyOf(sku);
    const oldKey = skuKeyOf(currentSku);
    if (newKey !== oldKey && await unindexedMatch(products, newKey, sku, productId)) throw duplicate(sku);
    const oldIndexRef = index.doc(indexDocId(oldKey));
    const newIndexRef = index.doc(indexDocId(newKey));
    await firestore.runTransaction(async (txn) => {
      const existing = await txn.get(newIndexRef);
      if (existing.exists && existing.get("productId") !== productId) throw duplicate(sku);
      if (newKey !== oldKey) txn.delete(oldIndexRef);
      txn.set(newIndexRef, {productId, sku});
      txn.update(productRef, {sku, skuKey: newKey, updatedAt: now, updatedBy: uid});
    });
    // Units carry a copy of the SKU; movements keep the old one as history.
    const units = await company.collection("inventory_units").where("productId", "==", productId).get();
    for (let i = 0; i < units.docs.length; i += BATCH) {
      const batch = firestore.batch();
      units.docs.slice(i, i + BATCH).forEach((u) => batch.update(u.ref, {sku}));
      await batch.commit();
    }
    return {unitsUpdated: units.size};
  }

  case "setImage": {
    const productId = requireId(data.productId, "product");
    const url = text(data.imageUrl, 2000, "Image link");
    // Only this product's own photo folder in this company's storage.
    const folder = encodeURIComponent(`companies/${companyId}/products/${productId}/`);
    if (url && !(url.startsWith("https://firebasestorage.googleapis.com/") && url.includes(`/o/${folder}`))) {
      throw new HttpsError("invalid-argument", "That image isn't one of this product's uploaded photos.");
    }
    await mustExist(products.doc(productId));
    await products.doc(productId).update({imageUrl: url, updatedAt: now, updatedBy: uid});
    return {id: productId};
  }

  case "delete": {
    const productId = requireId(data.productId, "product");
    const units = (await company.collection("inventory_units").where("productId", "==", productId).count().get()).data().count;
    if (units > 0) {
      throw new HttpsError("failed-precondition",
        `Cannot delete product — ${units} unit(s) in inventory still reference this SKU. Dispatch them first.`);
    }
    const productRef = products.doc(productId);
    const snap = await productRef.get();
    if (!snap.exists) return {id: productId};
    const key = String(snap.get("skuKey") ?? "") || skuKeyOf(String(snap.get("sku") ?? ""));
    const indexRef = index.doc(indexDocId(key));
    const indexSnap = await indexRef.get();
    const batch = firestore.batch();
    batch.delete(productRef);
    // Only remove the index entry if it really points at this product.
    if (indexSnap.exists && indexSnap.get("productId") === productId) batch.delete(indexRef);
    await batch.commit();
    try {
      await getStorage().bucket().deleteFiles({prefix: `companies/${companyId}/products/${productId}/`});
    } catch (e) {
      logger.warn("Could not delete product photos", {companyId, productId, error: e instanceof Error ? e.message : String(e)});
    }
    return {id: productId};
  }

  case "backfillSkuKeys": {
    // One-off for products created before sku_index existed: oldest product keeps a shared SKU.
    const all = (await products.get()).docs;
    const missing = all
      .filter((d) => !String(d.get("skuKey") ?? "").trim() && String(d.get("sku") ?? "").trim())
      .sort((a, b) => (a.get("createdAt")?.toMillis?.() ?? 0) - (b.get("createdAt")?.toMillis?.() ?? 0));
    if (missing.length === 0) return {backfilled: 0};
    const indexed = new Set(all.map((d) => String(d.get("skuKey") ?? "")).filter(Boolean));
    let batch = firestore.batch();
    let ops = 0;
    for (const d of missing) {
      const sku = String(d.get("sku")).trim();
      const key = skuKeyOf(sku);
      batch.update(d.ref, {skuKey: key});
      ops++;
      if (!indexed.has(key)) {
        indexed.add(key);
        batch.set(index.doc(indexDocId(key)), {productId: d.id, sku});
        ops++;
      } else {
        logger.warn("Duplicate legacy SKU; not indexed", {companyId, productId: d.id, sku});
      }
      if (ops >= BATCH) {
        await batch.commit();
        batch = firestore.batch();
        ops = 0;
      }
    }
    if (ops > 0) await batch.commit();
    return {backfilled: missing.length};
  }

  default:
    throw new HttpsError("invalid-argument", "Unknown product action.");
  }
}

export const productsV1 = onCall({region: REGION, timeoutSeconds: 120}, async (request) => {
  const data = body(request);
  const caller = await requireMember(request, ["owner", "manager"]);
  const opId = requireOpId(data.opId);
  const action = String(data.action ?? "");
  return runOnce(caller.companyId, opId, `products.${action}`, caller.uid, async () => {
    const result = await runProductAction(db, caller.companyId, caller.uid, data);
    logger.info("Product catalog", {companyId: caller.companyId, uid: caller.uid, action, ...result});
    return result;
  });
});
