package com.snainfotech.tagscout.data.shop

/**
 * Product from the Firestore "products" collection.
 *
 * Firestore document fields map directly to these properties.
 * To add/edit products, update them in Firebase Console →
 * Firestore → "products" collection. No app update needed.
 */
data class Product(
    val id: String = "",
    val title: String = "",
    val imageUrl: String = "",          // URL or empty (shows placeholder icon)
    val category: String = "",           // "scanner", "printer", "label", "accessory"
    val features: List<String> = emptyList(),
    val warranty: String = "",
    val deliveryTimeline: String = "",
    val sellingPrice: Double = 0.0,
    val minimumOrderQuantity: Int = 1,
    val unit: String = "pcs",
    val isActive: Boolean = true,
    val sortOrder: Int = 0
)

/**
 * An item in the user's cart — a product with a chosen quantity.
 */
data class CartItem(
    val product: Product,
    val quantity: Int
) {
    val lineTotal: Double
        get() = product.sellingPrice * quantity
}