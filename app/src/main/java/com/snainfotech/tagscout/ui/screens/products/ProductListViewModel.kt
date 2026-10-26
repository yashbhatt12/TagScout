package com.snainfotech.tagscout.ui.screens.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.ProductTypeDef
import com.snainfotech.tagscout.data.config.ProductTypeKey
import com.snainfotech.tagscout.data.wms.CatalogLookupsRepository
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductRepository
import com.snainfotech.tagscout.data.wms.ProductStatus
import com.snainfotech.tagscout.data.wms.productStatus
import com.snainfotech.tagscout.data.wms.type
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row in the list, with ids already resolved to display names. */
data class ProductRow(
    val product: Product,
    val typeLabel: String,
    val categoryName: String,      // "Rings › Bands" when a sub-category is set
    val brandName: String
)

data class ProductListState(
    val loading: Boolean = true,
    val rows: List<ProductRow> = emptyList(),
    val config: CompanyConfig = CompanyConfig.DEFAULT,
    val query: String = "",
    val typeFilter: ProductTypeKey? = null,        // null = all types
    val showDiscontinued: Boolean = false,
    val deleting: Boolean = false,
    val message: String? = null
) {
    /** Types offered as filter chips: enabled types plus any type that has products. */
    val typeFilters: List<ProductTypeDef>
        get() {
            val inUse = rows.map { it.product.type() }.toSet()
            return ProductTypeKey.entries
                .filter { key -> config.typeDef(key).enabled || key in inUse }
                .map { config.typeDef(it) }
        }

    val visibleRows: List<ProductRow>
        get() {
            val q = query.trim().lowercase()
            return rows.filter { row ->
                val p = row.product
                (typeFilter == null || p.type() == typeFilter) &&
                        (showDiscontinued || p.productStatus() != ProductStatus.DISCONTINUED) &&
                        (q.isEmpty() || listOf(p.sku, p.title, p.shortName, p.barcode, row.brandName, row.categoryName)
                            .any { it.lowercase().contains(q) })
            }
        }

    val discontinuedCount: Int
        get() = rows.count { it.product.productStatus() == ProductStatus.DISCONTINUED }
}

/** The company's product catalog. Shared by every module that manages products. */
class ProductListViewModel(
    private val productRepo: ProductRepository = ProductRepository(),
    private val lookupsRepo: CatalogLookupsRepository = CatalogLookupsRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(ProductListState())
    val state: StateFlow<ProductListState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            ConfigRepository.config.collect { cfg ->
                _state.update { s -> s.copy(config = cfg, rows = s.rows.map { it.copy(typeLabel = cfg.typeDef(it.product.type()).label) }) }
            }
        }
    }

    /** Called each time the list is shown, so edits made in the form appear straight away. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.rows.isEmpty()) }

            val productsJob = async { productRepo.getProducts() }
            val categoriesJob = async { lookupsRepo.getCategories(includeInactive = true) }
            val brandsJob = async { lookupsRepo.getBrands(includeInactive = true) }

            val products = productsJob.await().getOrElse { e ->
                _state.update { it.copy(loading = false, message = "Could not load products: ${e.message}") }
                return@launch
            }
            val categories = categoriesJob.await().getOrElse { emptyList() }.associateBy { it.id }
            val brands = brandsJob.await().getOrElse { emptyList() }.associateBy { it.id }
            val config = _state.value.config

            val rows = products.map { p ->
                val top = categories[p.categoryId]?.name.orEmpty()
                val sub = categories[p.subCategoryId]?.name.orEmpty()
                ProductRow(
                    product = p,
                    typeLabel = config.typeDef(p.type()).label,
                    categoryName = listOf(top, sub).filter { it.isNotEmpty() }.joinToString(" › "),
                    brandName = brands[p.brandId]?.name.orEmpty()
                )
            }
            _state.update { it.copy(loading = false, rows = rows) }
        }
    }

    fun onQuery(q: String) = _state.update { it.copy(query = q) }
    fun onTypeFilter(type: ProductTypeKey?) = _state.update { it.copy(typeFilter = type) }
    fun onShowDiscontinued(show: Boolean) = _state.update { it.copy(showDiscontinued = show) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Repository blocks the delete if any unit still references the product. */
    fun delete(product: Product) {
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            productRepo.deleteProduct(product.id).fold(
                onSuccess = {
                    _state.update { s ->
                        s.copy(
                            deleting = false,
                            rows = s.rows.filterNot { it.product.id == product.id },
                            message = "Deleted ${product.sku}"
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(deleting = false, message = e.message ?: "Could not delete") }
                }
            )
        }
    }
}

class ProductListViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ProductListViewModel() as T
}