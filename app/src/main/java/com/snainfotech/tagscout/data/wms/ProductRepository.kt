package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Reads/writes the company's product catalog.
 *
 * Scoped under /companies/{companyId}/products/{productId}.
 * companyId = userId for now (single-tenant).
 *
 * NOTE: distinct from the Shop's "products" collection (top-level, read-only
 * catalog of RFID hardware for sale). WMS products are the customer's own SKUs.
 */
class ProductRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun companyId(): String? = auth.currentUser?.uid

    private fun productsRef() = companyId()?.let {
        firestore.collection("companies").document(it).collection("products")
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
            val ref = productsRef() ?: return Result.failure(Exception("Not logged in"))

            // Enforce SKU uniqueness within the company
            val existing = ref.whereEqualTo("sku", sku.trim()).limit(1).get().await()
            if (!existing.isEmpty) {
                return Result.failure(Exception("A product with SKU '${sku.trim()}' already exists"))
            }

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
}