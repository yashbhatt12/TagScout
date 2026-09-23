package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
import kotlinx.coroutines.tasks.await

/**
 * Reads/writes the company's product catalog.
 *
 * Scoped under /companies/{companyId}/products/{productId}.
 * companyId = userId for now (single-tenant).
 *
 * NOTE: distinct from the Shop's "products" collection (top-level, read-only
 * catalog of RFID hardware for sale). WMS products are the customer's own SKUs.
 *
 * The optional [inventoryRepository] is used by deleteProduct to block deletes
 * when inventory still references this product. Defaults to a fresh instance
 * so existing no-arg callers keep working.
 *
 * Uniqueness rule: SKU is unique within the company, case-insensitively.
 * "sku-1001" and "SKU-1001" are treated as the same. Original casing is
 * preserved when written. SKU itself is immutable after create, so the
 * uniqueness check only runs on createProduct.
 */
class ProductRepository(
    private val inventoryRepository: InventoryRepository = InventoryRepository()
) {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /** Uses AuthReady to avoid the startup race where currentUser is briefly null. */
    private suspend fun companyId(): String? = AuthReady.awaitUid()

    private suspend fun productsRef() = companyId()?.let {
        firestore.collection("companies").document(it).collection("products")
    }

    // ── Uniqueness helper ─────────────────────────────────────

    /**
     * Whether another product in this company already uses the given SKU,
     * compared case-insensitively after trimming whitespace. Pass excludeId
     * to skip a specific product (currently unused — SKU is immutable — but
     * kept symmetric with the warehouse/rack helpers).
     */
    private suspend fun skuExists(sku: String, excludeId: String? = null): Result<Boolean> {
        return try {
            val ref = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val target = sku.trim()
            val snap = ref.get().await()
            val hit = snap.documents.any { doc ->
                val existing = doc.getString("sku")?.trim().orEmpty()
                existing.equals(target, ignoreCase = true) && doc.id != excludeId
            }
            Result.success(hit)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Read ───────────────────────────────────────────────────

    suspend fun getProducts(): Result<List<Product>> {
        return try {
            val ref = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val snap = ref.get().await()
            val list = snap.documents.mapNotNull { doc ->
                Product(
                    id = doc.id,
                    sku = doc.getString("sku") ?: "",
                    title = doc.getString("title") ?: "",
                    description = doc.getString("description") ?: "",
                    unitOfMeasure = doc.getString("unitOfMeasure") ?: "pcs",
                    trackingMode = doc.getString("trackingMode") ?: "unit",
                    isActive = doc.getBoolean("isActive") ?: true,
                    createdAt = doc.getTimestamp("createdAt"),
                    updatedAt = doc.getTimestamp("updatedAt")
                )
            }.filter { it.isActive }.sortedBy { it.sku }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getProductBySku(sku: String): Result<Product?> {
        return try {
            val ref = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val snap = ref.whereEqualTo("sku", sku.trim()).limit(1).get().await()
            val product = snap.documents.firstOrNull()?.let { doc ->
                Product(
                    id = doc.id,
                    sku = doc.getString("sku") ?: "",
                    title = doc.getString("title") ?: "",
                    description = doc.getString("description") ?: "",
                    unitOfMeasure = doc.getString("unitOfMeasure") ?: "pcs",
                    trackingMode = doc.getString("trackingMode") ?: "unit",
                    isActive = doc.getBoolean("isActive") ?: true,
                    createdAt = doc.getTimestamp("createdAt"),
                    updatedAt = doc.getTimestamp("updatedAt")
                )
            }
            Result.success(product)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Write ──────────────────────────────────────────────────

    suspend fun createProduct(
        sku: String,
        title: String,
        description: String,
        unitOfMeasure: String
    ): Result<String> {
        return try {
            // Case-insensitive SKU uniqueness within the company.
            val duplicate = skuExists(sku).getOrElse { return Result.failure(it) }
            if (duplicate) {
                return Result.failure(Exception(
                    "A product with SKU '${sku.trim()}' already exists"
                ))
            }

            val ref = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val data = hashMapOf(
                "sku" to sku.trim(),
                "title" to title.trim(),
                "description" to description.trim(),
                "unitOfMeasure" to unitOfMeasure.trim().ifBlank { "pcs" },
                "trackingMode" to "unit",   // fixed for now; sku-only later
                "isActive" to true,
                "createdAt" to Timestamp.now(),
                "updatedAt" to Timestamp.now()
            )
            val doc = ref.add(data).await()
            Result.success(doc.id)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Update a product's display fields (title, description, unitOfMeasure).
     *
     * SKU is intentionally immutable — it's denormalized into every
     * InventoryUnit and Movement that references the product, and renaming
     * would require a bulk rewrite across those collections. If a SKU was
     * typed wrong and hasn't been used yet, delete and recreate the product.
     * No uniqueness check is needed here since SKU cannot change.
     */
    suspend fun updateProduct(
        productId: String,
        title: String,
        description: String,
        unitOfMeasure: String
    ): Result<Unit> {
        return try {
            val ref = productsRef()?.document(productId)
                ?: return Result.failure(Exception("Not logged in"))
            val data = hashMapOf<String, Any>(
                "title" to title.trim(),
                "description" to description.trim(),
                "unitOfMeasure" to unitOfMeasure.trim().ifBlank { "pcs" },
                "updatedAt" to Timestamp.now()
            )
            ref.update(data).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Delete a product from the catalog.
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

            val ref = productsRef()?.document(productId)
                ?: return Result.failure(Exception("Not logged in"))
            ref.delete().await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }
}