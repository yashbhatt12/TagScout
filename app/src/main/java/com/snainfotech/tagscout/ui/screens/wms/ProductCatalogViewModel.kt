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
}

class ProductCatalogViewModelFactory(
    private val repo: ProductRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ProductCatalogViewModel(repo) as T
    }
}