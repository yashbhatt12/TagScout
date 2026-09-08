package com.snainfotech.tagscout.ui.screens.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.shop.CartItem
import com.snainfotech.tagscout.data.shop.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import com.snainfotech.tagscout.data.shop.ShopRepository
import kotlinx.coroutines.tasks.await

data class ShopState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val products: List<Product> = emptyList(),
    val cart: List<CartItem> = emptyList(),
    val message: String? = null    // success/info messages
) {
    val cartItemCount: Int get() = cart.size
    val cartTotal: Double get() = cart.sumOf { it.lineTotal }
    val cartTotalFormatted: String get() {
        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        return format.format(cartTotal)
    }
    val isCartFull: Boolean get() = cart.size >= MAX_CART_ITEMS

    companion object {
        const val MAX_CART_ITEMS = 5
    }
}

class ShopViewModel(
    private val repository: ShopRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ShopState())
    val state: StateFlow<ShopState> = _state.asStateFlow()

    init {
        loadProducts()
    }

    fun loadProducts() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            repository.getProducts().fold(
                onSuccess = { products ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        products = products,
                        error = if (products.isEmpty()) "No products available" else null
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Could not load products: ${e.message}"
                    )
                }
            )
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ── Cart operations ────────────────────────────────────────

    fun addToCart(product: Product, quantity: Int) {
        val s = _state.value

        if (quantity < product.minimumOrderQuantity) {
            _state.value = s.copy(
                message = "Minimum order quantity is ${product.minimumOrderQuantity} ${product.unit}"
            )
            return
        }

        // Check if already in cart
        val existingIndex = s.cart.indexOfFirst { it.product.id == product.id }
        if (existingIndex >= 0) {
            // Update quantity
            val updated = s.cart.toMutableList()
            updated[existingIndex] = CartItem(product, quantity)
            _state.value = s.copy(cart = updated, message = "${product.title} quantity updated")
            return
        }

        // Check cart limit
        if (s.isCartFull) {
            _state.value = s.copy(message = "Cart is full (maximum ${ShopState.MAX_CART_ITEMS} items)")
            return
        }

        val updated = s.cart + CartItem(product, quantity)
        _state.value = s.copy(cart = updated, message = "${product.title} added to cart")
    }

    fun removeFromCart(productId: String) {
        val s = _state.value
        val updated = s.cart.filter { it.product.id != productId }
        _state.value = s.copy(cart = updated)
    }

    fun updateCartQuantity(productId: String, quantity: Int) {
        val s = _state.value
        val index = s.cart.indexOfFirst { it.product.id == productId }
        if (index < 0) return

        val item = s.cart[index]
        if (quantity < item.product.minimumOrderQuantity) {
            _state.value = s.copy(
                message = "Minimum order quantity is ${item.product.minimumOrderQuantity} ${item.product.unit}"
            )
            return
        }

        val updated = s.cart.toMutableList()
        updated[index] = CartItem(item.product, quantity)
        _state.value = s.copy(cart = updated)
    }

    fun isInCart(productId: String): Boolean {
        return _state.value.cart.any { it.product.id == productId }
    }

    fun getCartQuantity(productId: String): Int {
        return _state.value.cart.find { it.product.id == productId }?.quantity ?: 0
    }

    // ── Checkout (email BOM) ───────────────────────────────────

    fun initiateCheckout(userName: String, userEmail: String, companyName: String) {
        val s = _state.value
        if (s.cart.isEmpty()) {
            _state.value = s.copy(message = "Cart is empty")
            return
        }

        _state.value = s.copy(isLoading = true, message = null)

        val format = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

        // Build the order document for Firestore
        val orderItems = s.cart.map { item ->
            hashMapOf(
                "productId" to item.product.id,
                "productTitle" to item.product.title,
                "category" to item.product.category,
                "quantity" to item.quantity,
                "unit" to item.product.unit,
                "unitPrice" to item.product.sellingPrice,
                "lineTotal" to item.lineTotal,
                "deliveryTimeline" to item.product.deliveryTimeline
            )
        }

        val order = hashMapOf(
            "userName" to userName,
            "userEmail" to userEmail,
            "companyName" to companyName,
            "userId" to (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""),
            "items" to orderItems,
            "itemCount" to s.cart.size,
            "approximateTotal" to s.cartTotal,
            "approximateTotalFormatted" to s.cartTotalFormatted,
            "status" to "pending",
            "createdAt" to com.google.firebase.Timestamp.now(),
            "notes" to ""
        )

        viewModelScope.launch {
            try {
                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("purchase_requisitions")
                    .add(order)
                    .await()

                _state.value = _state.value.copy(
                    isLoading = false,
                    cart = emptyList(),
                    message = "Purchase requisition submitted! The SNA Infotech team will review your order."
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "Could not submit order: ${e.message}"
                )
            }
        }
    }
}

class ShopViewModelFactory(
    private val repository: ShopRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ShopViewModel(repository) as T
    }
}