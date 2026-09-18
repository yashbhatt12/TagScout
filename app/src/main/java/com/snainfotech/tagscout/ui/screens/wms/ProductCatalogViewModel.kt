package com.snainfotech.tagscout.ui.screens.wms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProductCatalogState(
    val isLoading: Boolean = false,
    val message: String? = null,
    val products: List<Product> = emptyList()
)

class ProductCatalogViewModel(
    private val repo: ProductRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ProductCatalogState())
    val state: StateFlow<ProductCatalogState> = _state.asStateFlow()

    init { loadProducts() }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun loadProducts() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            repo.getProducts().fold(
                onSuccess = { list ->
                    _state.value = _state.value.copy(isLoading = false, products = list)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = "Could not load products: ${e.message}"
                    )
                }
            )
        }
    }

    fun createProduct(sku: String, title: String, description: String, unitOfMeasure: String) {
        if (sku.isBlank()) {
            _state.value = _state.value.copy(message = "SKU is required"); return
        }
        if (title.isBlank()) {
            _state.value = _state.value.copy(message = "Title is required"); return
        }
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            repo.createProduct(sku, title, description, unitOfMeasure).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Product created")
                    loadProducts()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = e.message ?: "Could not create product"
                    )
                }
            )
        }
    }

    /**
     * Update a product's display fields. SKU is intentionally not editable —
     * changing it would require rewriting every InventoryUnit and Movement
     * that references it. If a SKU was typoed and has no inventory yet,
     * delete-and-recreate is the current workaround.
     */
    fun updateProduct(
        productId: String,
        title: String,
        description: String,
        unitOfMeasure: String
    ) {
        if (title.isBlank()) {
            _state.value = _state.value.copy(message = "Title is required"); return
        }
        viewModelScope.launch {
            repo.updateProduct(productId, title, description, unitOfMeasure).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Product updated")
                    loadProducts()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = e.message ?: "Could not update product"
                    )
                }
            )
        }
    }

    /**
     * Delete a product from the catalog. Repository blocks the delete if any
     * InventoryUnit still references it — dispatch first, then delete.
     */
    fun deleteProduct(product: Product) {
        viewModelScope.launch {
            repo.deleteProduct(product.id).fold(
                onSuccess = {
                    _state.value = _state.value.copy(message = "Product deleted")
                    loadProducts()
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        message = e.message ?: "Could not delete product"
                    )
                }
            )
        }
    }
}

class ProductCatalogViewModelFactory(
    private val repo: ProductRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ProductCatalogViewModel(repo) as T
    }
}