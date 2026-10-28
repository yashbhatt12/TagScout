package com.snainfotech.tagscout.data.wms

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
import com.snainfotech.tagscout.data.config.ProductTypeKey
import kotlinx.coroutines.tasks.await
import java.net.URLEncoder
import com.google.firebase.storage.FirebaseStorage

/**
 * Reads/writes the company's product catalog.
 *
 * Scoped under /companies/{companyId}/products/{productId}.
 * companyId = userId for now (single-tenant).
 *
 * NOTE: distinct from the Shop's "products" collection (top-level, read-only
 * catalog of RFID hardware for sale). WMS products are the customer's own SKUs.
 *
 * ── SKU uniqueness ──
 * SKU is unique within the company, case-insensitively ("sku-1001" == "SKU-1001");
 * original casing is preserved. Enforced by an index doc per SKU:
 *
 *   /companies/{cid}/sku_index/{indexDocId(skuKey)}  →  { productId, sku }
 *
 * created/moved/deleted in the same transaction as the product, so two
 * concurrent creates of the same SKU can't both succeed, and a check is one
 * read instead of a full catalog scan. Products created before the index
 * existed are backfilled the first time the catalog is loaded; until then a
 * skuKey / exact-sku query also guards against them.
 *
 * ── SKU edits ──
 * SKU is editable via [changeSku]. The new SKU is copied onto every unit of
 * the product; movements keep the SKU they were written with (history).
 *
 * The optional [inventoryRepository] is used by deleteProduct to block deletes
 * when inventory still references this product.
 */
class ProductRepository(
    private val inventoryRepository: InventoryRepository = InventoryRepository()
) {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /** Uses AuthReady to avoid the startup race where currentUser is briefly null. */
    private suspend fun companyId(): String? = AuthReady.awaitUid()

    private fun currentUserId(): String = auth.currentUser?.uid ?: ""

    private suspend fun companyRef(): DocumentReference? =
        companyId()?.let { firestore.collection("companies").document(it) }

    private suspend fun productsRef(): CollectionReference? = companyRef()?.collection("products")

    private suspend fun skuIndexRef(): CollectionReference? = companyRef()?.collection(SKU_INDEX)

    companion object {
        private const val TAG = "ProductRepository"
        private const val SKU_INDEX = "sku_index"
        private const val MAX_BATCH_OPS = 400   // Firestore limit is 500 per batch

        /**
         * Firestore doc ids can't contain "/" or be "." / "..", so the key is
         * URL-encoded and prefixed. Same input always gives the same id.
         */
        fun indexDocId(skuKey: String): String = "k_" + URLEncoder.encode(skuKey, "UTF-8")
    }

    class DuplicateSkuException(sku: String) :
        Exception("A product with SKU '$sku' already exists")

    // ── Mapping ───────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toProduct(): Product {
        val sku = getString("sku") ?: ""
        return Product(
            id = id,
            sku = sku,
            skuKey = getString("skuKey") ?: Product.skuKeyOf(sku),
            title = getString("title") ?: "",
            shortName = getString("shortName") ?: "",
            productType = getString("productType") ?: ProductTypeKey.GENERAL.key,
            categoryId = getString("categoryId") ?: "",
            subCategoryId = getString("subCategoryId") ?: "",
            brandId = getString("brandId") ?: "",
            description = getString("description") ?: "",
            barcode = getString("barcode") ?: "",
            unitOfMeasure = getString("unitOfMeasure") ?: UnitOfMeasure.DEFAULT.key,
            imageUrl = getString("imageUrl") ?: "",
            status = getString("status") ?: ProductStatus.ACTIVE.key,
            trackingMode = getString("trackingMode") ?: "unit",
            isActive = getBoolean("isActive") ?: true,
            customFields = (get("customFields") as? Map<String, Any?>) ?: emptyMap(),
            createdAt = getTimestamp("createdAt"),
            createdBy = getString("createdBy") ?: "",
            updatedAt = getTimestamp("updatedAt"),
            updatedBy = getString("updatedBy") ?: ""
        )
    }

    /**
     * Editable catalog fields as written to Firestore. SKU fields, audit
     * stamps, isActive and trackingMode are added by the callers that own them.
     * UoM is normalised to its canonical key; blank custom values are dropped
     * (an empty value is an absent key).
     */
    private fun editableData(p: Product): Map<String, Any> = mapOf(
        "title" to p.title.trim(),
        "shortName" to p.shortName.trim(),
        "productType" to (ProductTypeKey.fromKey(p.productType) ?: ProductTypeKey.GENERAL).key,
        "categoryId" to p.categoryId.trim(),
        "subCategoryId" to p.subCategoryId.trim(),
        "brandId" to p.brandId.trim(),
        "description" to p.description.trim(),
        "barcode" to p.barcode.trim(),
        "unitOfMeasure" to (UnitOfMeasure.parse(p.unitOfMeasure) ?: UnitOfMeasure.DEFAULT).key,
        "imageUrl" to p.imageUrl.trim(),
        "status" to (ProductStatus.fromKey(p.status) ?: ProductStatus.ACTIVE).key,
        "customFields" to p.customFields
            .filterValues { v -> v != null && !(v is String && v.isBlank()) }
            .mapValues { (_, v) -> v!! }
    )

    /** Core-field checks only; custom-field validation against settings lives in the field validator. */
    private fun validateCore(p: Product, requireSku: Boolean): String? = when {
        requireSku && p.sku.isBlank() -> "SKU is required"
        p.title.isBlank() -> "Full name is required"
        p.shortName.trim().length > Product.SHORT_NAME_MAX ->
            "Short name can be at most ${Product.SHORT_NAME_MAX} characters"
        p.productType.isNotBlank() && ProductTypeKey.fromKey(p.productType) == null ->
            "Unknown product type '${p.productType}'"
        p.unitOfMeasure.isNotBlank() && UnitOfMeasure.parse(p.unitOfMeasure) == null ->
            "Unknown unit of measure '${p.unitOfMeasure}'"
        else -> null
    }

    /** Finds the DuplicateSkuException inside a failed transaction's cause chain. */
    private fun unwrap(e: Throwable): Throwable =
        generateSequence(e) { it.cause }.firstOrNull { it is DuplicateSkuException } ?: e

    // ── Uniqueness helpers ────────────────────────────────────

    /**
     * Guards against products not yet in sku_index (created before it existed
     * and not yet backfilled): matches on skuKey, then on the exact SKU text.
     */
    private suspend fun unindexedSkuMatch(
        products: CollectionReference,
        key: String,
        sku: String,
        excludeId: String?
    ): Boolean {
        val byKey = products.whereEqualTo("skuKey", key).limit(2).get().await()
        if (byKey.documents.any { it.id != excludeId }) return true
        val byText = products.whereEqualTo("sku", sku).limit(2).get().await()
        return byText.documents.any { it.id != excludeId }
    }

    /**
     * One-off migration, run on catalog load: give every product without a
     * skuKey its key and an index doc. If two old products already share a
     * SKU (possible before case-insensitive checks), only the oldest is
     * indexed and the clash is logged; the catalog still loads.
     */
    private suspend fun backfillSkuKeys(docs: List<DocumentSnapshot>) {
        val missing = docs.filter {
            it.getString("skuKey").isNullOrBlank() && !it.getString("sku").isNullOrBlank()
        }
        if (missing.isEmpty()) return
        val index = skuIndexRef() ?: return

        val indexedKeys = docs.mapNotNull { it.getString("skuKey")?.takeIf { k -> k.isNotBlank() } }
            .toMutableSet()

        var batch = firestore.batch()
        var ops = 0
        for (doc in missing.sortedBy { it.getTimestamp("createdAt") }) {
            val sku = doc.getString("sku")!!.trim()
            val key = Product.skuKeyOf(sku)
            batch.update(doc.reference, "skuKey", key)
            ops++
            if (indexedKeys.add(key)) {
                batch.set(index.document(indexDocId(key)), mapOf("productId" to doc.id, "sku" to sku))
                ops++
            } else {
                Log.w(TAG, "Duplicate legacy SKU '$sku' on product ${doc.id}; not indexed")
            }
            if (ops >= MAX_BATCH_OPS) {
                batch.commit().await()
                batch = firestore.batch()
                ops = 0
            }
        }
        if (ops > 0) batch.commit().await()
        Log.i(TAG, "Backfilled skuKey on ${missing.size} product(s)")
    }

    // ── Read ───────────────────────────────────────────────────

    /** Active products, sorted by SKU. Also runs the one-off skuKey backfill. */
    suspend fun getProducts(): Result<List<Product>> {
        return try {
            val ref = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val snap = ref.get().await()
            runCatching { backfillSkuKeys(snap.documents) }
                .onFailure { Log.w(TAG, "skuKey backfill failed; will retry next load", it) }
            val list = snap.documents.map { it.toProduct() }
                .filter { it.isActive }
                .sortedBy { it.sku }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getProduct(productId: String): Result<Product?> {
        return try {
            val ref = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val doc = ref.document(productId).get().await()
            Result.success(if (doc.exists()) doc.toProduct() else null)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Case-insensitive SKU lookup (skuKey), falling back to exact text for un-backfilled products. */
    suspend fun getProductBySku(sku: String): Result<Product?> {
        return try {
            val ref = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val trimmed = sku.trim()
            val key = Product.skuKeyOf(trimmed)
            if (key.isEmpty()) return Result.success(null)

            val doc = ref.whereEqualTo("skuKey", key).limit(1).get().await().documents.firstOrNull()
                ?: ref.whereEqualTo("sku", trimmed).limit(1).get().await().documents.firstOrNull()
            Result.success(doc?.toProduct())
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Write ──────────────────────────────────────────────────

    /** Creates a product with every catalog field. Returns the new product id. */
    suspend fun createProduct(product: Product): Result<String> {
        return try {
            val sku = product.sku.trim()
            val key = Product.skuKeyOf(sku)
            validateCore(product.copy(sku = sku), requireSku = true)
                ?.let { return Result.failure(IllegalArgumentException(it)) }

            val products = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val index = skuIndexRef() ?: return Result.failure(Exception("Not logged in"))

            if (unindexedSkuMatch(products, key, sku, excludeId = null)) {
                return Result.failure(DuplicateSkuException(sku))
            }

            val productRef = products.document()
            val indexRef = index.document(indexDocId(key))
            val now = Timestamp.now()
            val uid = currentUserId()
            val data = editableData(product) + mapOf(
                "sku" to sku,
                "skuKey" to key,
                "trackingMode" to "unit",
                "isActive" to true,
                "createdAt" to now,
                "createdBy" to uid,
                "updatedAt" to now,
                "updatedBy" to uid
            )

            firestore.runTransaction { txn ->
                if (txn.get(indexRef).exists()) throw DuplicateSkuException(sku)
                txn.set(productRef, data)
                txn.set(indexRef, mapOf("productId" to productRef.id, "sku" to sku))
                null
            }.await()

            Result.success(productRef.id)
        } catch (e: Exception) { Result.failure(unwrap(e)) }
    }

    /** Legacy signature used by the current catalog screen. */
    suspend fun createProduct(
        sku: String,
        title: String,
        description: String,
        unitOfMeasure: String
    ): Result<String> = createProduct(
        Product(
            sku = sku,
            title = title,
            description = description,
            unitOfMeasure = unitOfMeasure.ifBlank { UnitOfMeasure.DEFAULT.key }
        )
    )

    /**
     * Updates every editable catalog field of an existing product.
     * SKU is not touched here — use [changeSku].
     */
    suspend fun updateProduct(product: Product): Result<Unit> {
        return try {
            validateCore(product, requireSku = false)
                ?.let { return Result.failure(IllegalArgumentException(it)) }
            val ref = productsRef()?.document(product.id)
                ?: return Result.failure(Exception("Not logged in"))
            val data = editableData(product) + mapOf(
                "updatedAt" to Timestamp.now(),
                "updatedBy" to currentUserId()
            )
            ref.update(data).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Legacy signature used by the current catalog screen: updates only
     * title, description and unit of measure, leaving every other field as is.
     */
    suspend fun updateProduct(
        productId: String,
        title: String,
        description: String,
        unitOfMeasure: String
    ): Result<Unit> {
        return try {
            if (title.isBlank()) return Result.failure(IllegalArgumentException("Full name is required"))
            val uom = UnitOfMeasure.parse(unitOfMeasure.ifBlank { UnitOfMeasure.DEFAULT.key })
                ?: return Result.failure(IllegalArgumentException("Unknown unit of measure '$unitOfMeasure'"))
            val ref = productsRef()?.document(productId)
                ?: return Result.failure(Exception("Not logged in"))
            val data = hashMapOf<String, Any>(
                "title" to title.trim(),
                "description" to description.trim(),
                "unitOfMeasure" to uom.key,
                "updatedAt" to Timestamp.now(),
                "updatedBy" to currentUserId()
            )
            ref.update(data).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Changes a product's SKU. Moves its sku_index entry atomically with the
     * product, then copies the new SKU onto every unit of the product.
     * Returns the number of units updated. Movements keep their original SKU.
     */
    suspend fun changeSku(productId: String, newSku: String): Result<Int> {
        return try {
            val sku = newSku.trim()
            if (sku.isEmpty()) return Result.failure(IllegalArgumentException("SKU is required"))
            val newKey = Product.skuKeyOf(sku)

            val products = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val index = skuIndexRef() ?: return Result.failure(Exception("Not logged in"))
            val current = getProduct(productId).getOrElse { return Result.failure(it) }
                ?: return Result.failure(Exception("Product not found"))
            if (current.sku == sku) return Result.success(0)

            val oldKey = Product.skuKeyOf(current.sku)
            if (newKey != oldKey && unindexedSkuMatch(products, newKey, sku, excludeId = productId)) {
                return Result.failure(DuplicateSkuException(sku))
            }

            val productRef = products.document(productId)
            val oldIndexRef = index.document(indexDocId(oldKey))
            val newIndexRef = index.document(indexDocId(newKey))

            firestore.runTransaction { txn ->
                val existing = txn.get(newIndexRef)
                if (existing.exists() && existing.getString("productId") != productId) {
                    throw DuplicateSkuException(sku)
                }
                if (newKey != oldKey) txn.delete(oldIndexRef)
                txn.set(newIndexRef, mapOf("productId" to productId, "sku" to sku))
                txn.update(
                    productRef, mapOf(
                        "sku" to sku,
                        "skuKey" to newKey,
                        "updatedAt" to Timestamp.now(),
                        "updatedBy" to currentUserId()
                    )
                )
                null
            }.await()

            Result.success(copySkuToUnits(productId, sku))
        } catch (e: Exception) { Result.failure(unwrap(e)) }
    }

    /** Rewrites the denormalised sku on every unit of a product, in batches. */
    private suspend fun copySkuToUnits(productId: String, sku: String): Int {
        val units = companyRef()?.collection("inventory_units") ?: return 0
        val snap = units.whereEqualTo("productId", productId).get().await()
        snap.documents.chunked(MAX_BATCH_OPS).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.update(it.reference, "sku", sku) }
            batch.commit().await()
        }
        return snap.size()
    }

    /**
     * Delete a product from the catalog, together with its sku_index entry.
     *
     * BLOCKED if any InventoryUnit currently references this product's ID.
     * Movement history is left in place as audit trail — old records may
     * point to a product ID that no longer exists.
     */
    suspend fun deleteProduct(productId: String): Result<Unit> {
        return try {
            val units = inventoryRepository.countUnitsForProduct(productId)
                .getOrElse { return Result.failure(it) }
            if (units > 0L) {
                return Result.failure(Exception(
                    "Cannot delete product — $units unit(s) in inventory still reference this SKU. Dispatch them first."
                ))
            }

            val products = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val index = skuIndexRef() ?: return Result.failure(Exception("Not logged in"))
            val productRef = products.document(productId)
            val snap = productRef.get().await()
            if (!snap.exists()) return Result.success(Unit)

            val key = snap.getString("skuKey")?.takeIf { it.isNotBlank() }
                ?: Product.skuKeyOf(snap.getString("sku") ?: "")
            val indexRef = index.document(indexDocId(key))
            val indexSnap = indexRef.get().await()

            val batch = firestore.batch()
            batch.delete(productRef)
            // Only remove the index entry if it really points at this product.
            if (indexSnap.exists() && indexSnap.getString("productId") == productId) {
                batch.delete(indexRef)
            }
            batch.commit().await()
            deleteProductPhotos(productId)
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Removes every file under companies/{cid}/products/{productId}/ in Storage.
     * Best effort: a failure is logged, never surfaced; the product is already gone.
     */
    private suspend fun deleteProductPhotos(productId: String) {
        runCatching {
            val cid = AuthReady.awaitUid() ?: return
            val folder = FirebaseStorage.getInstance().reference
                .child("companies/$cid/products/$productId")
            folder.listAll().await().items.forEach { file ->
                runCatching { file.delete().await() }
            }
        }.onFailure { Log.w("ProductRepository", "Could not delete photos for product $productId", it) }
    }
}