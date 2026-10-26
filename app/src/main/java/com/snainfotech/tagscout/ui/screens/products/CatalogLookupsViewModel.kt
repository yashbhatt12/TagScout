package com.snainfotech.tagscout.ui.screens.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.ProductTypeDef
import com.snainfotech.tagscout.data.config.ProductTypeKey
import com.snainfotech.tagscout.data.wms.Brand
import com.snainfotech.tagscout.data.wms.CatalogLookupsRepository
import com.snainfotech.tagscout.data.wms.Category
import com.snainfotech.tagscout.data.wms.isTopLevel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LookupsTab { CATEGORIES, BRANDS }

/** A top-level category with its sub-categories, for the tree view. */
data class CategoryNode(val category: Category, val children: List<Category>)

data class CatalogLookupsState(
    val loading: Boolean = true,
    val tab: LookupsTab = LookupsTab.CATEGORIES,
    val selectedType: ProductTypeKey = ProductTypeKey.GENERAL,
    val categories: List<Category> = emptyList(),   // every type, active and inactive
    val brands: List<Brand> = emptyList(),          // active and inactive
    val config: CompanyConfig = CompanyConfig.DEFAULT,
    val busy: Boolean = false,
    val message: String? = null
) {
    /** Enabled types, plus any type that already has categories. */
    val typeOptions: List<ProductTypeDef>
        get() {
            val withCategories = categories.map { it.productType }.toSet()
            return ProductTypeKey.entries
                .filter { config.typeDef(it).enabled || it.key in withCategories }
                .map { config.typeDef(it) }
        }

    /** Category tree for [selectedType]: top level by order/name, children under each. */
    val tree: List<CategoryNode>
        get() {
            val ofType = categories.filter { it.productType == selectedType.key }
            val byParent = ofType.filterNot { it.isTopLevel() }.groupBy { it.parentId }
            return ofType.filter { it.isTopLevel() }.map { top ->
                CategoryNode(top, byParent[top.id].orEmpty())
            }
        }
}

/** Manage the company's categories (per product type) and brands. */
class CatalogLookupsViewModel(
    private val repo: CatalogLookupsRepository = CatalogLookupsRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogLookupsState())
    val state: StateFlow<CatalogLookupsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            ConfigRepository.config.collect { cfg ->
                _state.update { s ->
                    // Start on the first enabled type; keep the user's pick afterwards.
                    val type = if (s.loading) cfg.enabledTypes.firstOrNull()?.key ?: s.selectedType else s.selectedType
                    s.copy(config = cfg, selectedType = type)
                }
            }
        }
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            val categoriesJob = async { repo.getCategories(includeInactive = true) }
            val brandsJob = async { repo.getBrands(includeInactive = true) }
            val categories = categoriesJob.await()
            val brands = brandsJob.await()

            _state.update {
                it.copy(
                    loading = false,
                    busy = false,
                    categories = categories.getOrElse { _ -> it.categories },
                    brands = brands.getOrElse { _ -> it.brands },
                    message = categories.exceptionOrNull()?.let { e -> "Could not load categories: ${e.message}" }
                        ?: brands.exceptionOrNull()?.let { e -> "Could not load brands: ${e.message}" }
                        ?: it.message
                )
            }
        }
    }

    /** Runs one write, shows its error or [successMessage], then reloads both lists. */
    private fun write(successMessage: String?, action: suspend () -> Result<*>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = action()
            _state.update {
                it.copy(message = result.exceptionOrNull()?.message ?: successMessage)
            }
            reload()
        }
    }

    // ── Navigation within the screen ──────────────────────────

    fun selectTab(tab: LookupsTab) = _state.update { it.copy(tab = tab) }
    fun selectType(type: ProductTypeKey) = _state.update { it.copy(selectedType = type) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    // ── Categories ────────────────────────────────────────────

    /** [parentId] = "" adds a top-level category to the selected type. */
    fun addCategory(name: String, parentId: String = "") {
        val type = _state.value.selectedType
        write("Added ${name.trim()}") { repo.createCategory(name, type, parentId) }
    }

    fun renameCategory(category: Category, newName: String) {
        if (newName.trim() == category.name) return
        write("Renamed to ${newName.trim()}") { repo.renameCategory(category.id, newName) }
    }

    fun toggleCategoryActive(category: Category) {
        val next = !category.isActive
        write(if (next) "${category.name} is active again" else "${category.name} hidden from pickers") {
            repo.setCategoryActive(category.id, next)
        }
    }

    fun deleteCategory(category: Category) {
        write("Deleted ${category.name}") { repo.deleteCategory(category.id) }
    }

    // ── Brands ────────────────────────────────────────────────

    fun addBrand(name: String) {
        val key = CatalogLookupsRepository.nameKeyOf(name)
        if (_state.value.brands.any { it.nameKey == key && it.isActive }) {
            _state.update { it.copy(message = "Brand '${name.trim()}' already exists") }
            return
        }
        // findOrCreate also reactivates a hidden brand of the same name instead of duplicating it.
        write("Added ${name.trim()}") { repo.findOrCreateBrand(name) }
    }

    fun renameBrand(brand: Brand, newName: String) {
        if (newName.trim() == brand.name) return
        write("Renamed to ${newName.trim()}") { repo.renameBrand(brand.id, newName) }
    }

    fun toggleBrandActive(brand: Brand) {
        val next = !brand.isActive
        write(if (next) "${brand.name} is active again" else "${brand.name} hidden from pickers") {
            repo.setBrandActive(brand.id, next)
        }
    }

    fun deleteBrand(brand: Brand) {
        write("Deleted ${brand.name}") { repo.deleteBrand(brand.id) }
    }
}

class CatalogLookupsViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = CatalogLookupsViewModel() as T
}