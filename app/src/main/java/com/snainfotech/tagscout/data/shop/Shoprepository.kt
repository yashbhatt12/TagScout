package com.snainfotech.tagscout.data.shop

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Reads the product catalog from Firestore's "products" collection.
 *
 * To manage products: open Firebase Console → Firestore → "products".
 * Each document should have fields matching the Product data class:
 *   title, imageUrl, category, features (array), warranty,
 *   deliveryTimeline, sellingPrice (number), minimumOrderQuantity (number),
 *   unit, isActive (boolean), sortOrder (number)
 */
class ShopRepository {

    private val firestore = FirebaseFirestore.getInstance()

    /**
     * Fetch all active products, sorted by sortOrder.
     */
    suspend fun getProducts(): Result<List<Product>> {
        return try {
            val snapshot = firestore.collection("products")
                .get()
                .await()

            val products = snapshot.documents.mapNotNull { doc ->
                try {
                    Product(
                        id = doc.id,
                        title = doc.getString("title") ?: "",
                        imageUrl = doc.getString("imageUrl") ?: "",
                        category = doc.getString("category") ?: "",
                        features = (doc.get("features") as? List<*>)
                            ?.mapNotNull { it as? String } ?: emptyList(),
                        warranty = doc.getString("warranty") ?: "",
                        deliveryTimeline = doc.getString("deliveryTimeline") ?: "",
                        sellingPrice = doc.getDouble("sellingPrice") ?: 0.0,
                        minimumOrderQuantity = (doc.getLong("minimumOrderQuantity") ?: 1).toInt(),
                        unit = doc.getString("unit") ?: "pcs",
                        isActive = doc.getBoolean("isActive") ?: true,
                        sortOrder = (doc.getLong("sortOrder") ?: 0).toInt()
                    )
                } catch (e: Exception) {
                    null // skip malformed documents
                }
            }

            Result.success(
                products.filter { it.isActive }.sortedBy { it.sortOrder }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}