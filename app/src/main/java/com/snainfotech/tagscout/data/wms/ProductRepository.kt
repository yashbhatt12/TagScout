package com.snainfotech.tagscout.data.wms

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
import com.snainfotech.tagscout.data.auth.MembershipRepository
import com.snainfotech.tagscout.data.server.ServerCalls
import com.google.firebase.functions.FirebaseFunctionsException
import com.snainfotech.tagscout.data.config.ProductTypeKey
import kotlinx.coroutines.tasks.await
import java.net.URLEncoder

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
    private suspend fun companyId(): String? = AuthReady.awaitCompanyId()

    private fun currentUserId(): String = auth.currentUser?.uid ?: ""

    private suspend fun companyRef(): DocumentReference? =
        companyId()?.let { firestore.collection("companies").document(it) }

    private suspend fun productsRef(): CollectionReference? = companyRef()?.collection("products")

    companion object {
        private const val TAG = "ProductRepository"
        private const val SKU_INDEX = "sku_index"

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
            hsnCode = getString("hsnCode")?.takeIf { it.isNotBlank() }
                ?: (get("customFields") as? Map<*, *>)?.get(Product.HSN_FIELD_KEY)?.toString()?.trim().orEmpty(),
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
        "hsnCode" to Product.normaliseHsn(p.hsnCode),
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
            .filterKeys { it != Product.HSN_FIELD_KEY }
            .filterValues { v -> v != null && !(v is String && v.isBlank()) }
            .mapValues { (_, v) -> v!! }
    )

    /** Core-field checks only; custom-field validation against settings lives in the field validator. */
    private fun validateCore(p: Product, requireSku: Boolean): String? = when {
        requireSku && p.sku.isBlank() -> "SKU is required"
        p.title.isBlank() -> "Full name is required"
        p.hsnCode.isBlank() -> "HSN code is required"
        !Product.isValidHsn(Product.normaliseHsn(p.hsnCode)) -> Product.HSN_RULE
        p.shortName.trim().length > Product.SHORT_NAME_MAX ->
            "Short name can be at most ${Product.SHORT_NAME_MAX} characters"
        p.productType.isNotBlank() && ProductTypeKey.fromKey(p.productType) == null ->
            "Unknown product type '${p.productType}'"
        p.unitOfMeasure.isNotBlank() && UnitOfMeasure.parse(p.unitOfMeasure) == null ->
            "Unknown unit of measure '${p.unitOfMeasure}'"
        else -> null
    }

    // ── Uniqueness helpers ────────────────────────────────────

    /**
     * One-off migration for products created before sku_index existed: when
     * the catalog loads with any of them, ask the server (productsV1
     * backfillSkuKeys) to key and index them. Owners and managers only;
     * best effort, retried on the next load.
     */
    private suspend fun backfillSkuKeys(docs: List<DocumentSnapshot>) {
        val needed = docs.any { it.getString("skuKey").isNullOrBlank() && !it.getString("sku").isNullOrBlank() }
        if (!needed || MembershipRepository.current?.role?.canSetUp != true) return
        val reply = ServerCalls.call("productsV1", mapOf("action" to "backfillSkuKeys"))
        Log.i(TAG, "Backfilled skuKey on ${reply["backfilled"]} product(s)")
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

    // ── Write (on the server: productsV1) ──────────────────────
    //
    // productsV1 (functions/src/products.ts) repeats the checks below, keeps
    // the SKU unique through sku_index in a transaction, copies a changed SKU
    // onto the product's units, refuses deletes while units exist and removes
    // the product's photos. Owners and managers only.

    /** Creates a product with every catalog field. Returns the new product id. */
    suspend fun createProduct(product: Product): Result<String> {
        val sku = product.sku.trim()
        validateCore(product.copy(sku = sku), requireSku = true)
            ?.let { return Result.failure(IllegalArgumentException(it)) }
        return server(sku) {
            val reply = call("create", "product" to serverFields(product) + ("sku" to sku))
            reply["id"]?.toString().orEmpty()
        }
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
        validateCore(product, requireSku = false)
            ?.let { return Result.failure(IllegalArgumentException(it)) }
        return server(null) { call("update", "productId" to product.id, "product" to serverFields(product)); Unit }
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
        if (title.isBlank()) return Result.failure(IllegalArgumentException("Full name is required"))
        val uom = UnitOfMeasure.parse(unitOfMeasure.ifBlank { UnitOfMeasure.DEFAULT.key })
            ?: return Result.failure(IllegalArgumentException("Unknown unit of measure '$unitOfMeasure'"))
        return server(null) {
            call(
                "updateBasic",
                "productId" to productId,
                "title" to title.trim(),
                "description" to description.trim(),
                "unitOfMeasure" to uom.key
            )
            Unit
        }
    }

    /**
     * Changes a product's SKU. The server moves its sku_index entry atomically
     * with the product, then copies the new SKU onto every unit of the product.
     * Returns the number of units updated. Movements keep their original SKU.
     */
    suspend fun changeSku(productId: String, newSku: String): Result<Int> {
        val sku = newSku.trim()
        if (sku.isEmpty()) return Result.failure(IllegalArgumentException("SKU is required"))
        return server(sku) {
            val reply = call("changeSku", "productId" to productId, "sku" to sku)
            (reply["unitsUpdated"] as? Number)?.toInt() ?: 0
        }
    }

    /**
     * Delete a product from the catalog, together with its sku_index entry and
     * its photos. BLOCKED (on the server) if any unit still references it.
     * Movement history is left in place as audit trail.
     */
    suspend fun deleteProduct(productId: String): Result<Unit> =
        server(null) { call("delete", "productId" to productId); Unit }

    /** Saves (or, with "", removes) the product's photo link. Used by ProductImageRepository. */
    suspend fun setImageUrl(productId: String, url: String): Result<Unit> =
        server(null) { call("setImage", "productId" to productId, "imageUrl" to url); Unit }

    // ── Server plumbing ────────────────────────────────────────

    /** The fields productsV1 takes, normalised like [editableData]; values the server can carry. */
    private fun serverFields(p: Product): Map<String, Any> =
        editableData(p).mapValues { (key, value) ->
            if (key == "customFields") {
                (value as Map<*, *>).entries.associate { (k, v) ->
                    k.toString() to when (v) {
                        is String, is Number, is Boolean -> v
                        is Timestamp -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(v.toDate())
                        else -> v.toString()
                    }
                }
            } else {
                value
            }
        }

    private suspend fun call(action: String, vararg fields: Pair<String, Any?>): Map<*, *> =
        ServerCalls.call("productsV1", mapOf<String, Any?>("action" to action) + fields)

    /** Runs a server write; an "already exists" reply for [sku] becomes [DuplicateSkuException]. */
    private suspend fun <T> server(sku: String?, block: suspend () -> T): Result<T> = try {
        if (companyId() == null) Result.failure(Exception("Not logged in")) else Result.success(block())
    } catch (e: Exception) {
        if (sku != null && e is FirebaseFunctionsException && e.code == FirebaseFunctionsException.Code.ALREADY_EXISTS) {
            Result.failure(DuplicateSkuException(sku))
        } else {
            Result.failure(Exception(ServerCalls.message(e), e))
        }
    }
}
