package com.snainfotech.tagscout.ui.screens.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.FieldDef
import com.snainfotech.tagscout.data.config.FieldLevel
import com.snainfotech.tagscout.data.config.FieldType
import com.snainfotech.tagscout.data.config.FieldValidator
import com.snainfotech.tagscout.data.config.ProductTypeDef
import com.snainfotech.tagscout.data.config.ProductTypeKey
import com.snainfotech.tagscout.data.wms.Brand
import com.snainfotech.tagscout.data.wms.CatalogLookupsRepository
import com.snainfotech.tagscout.data.wms.Category
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductRepository
import com.snainfotech.tagscout.data.wms.ProductStatus
import com.snainfotech.tagscout.data.wms.UnitOfMeasure
import com.snainfotech.tagscout.data.wms.isTopLevel
import com.snainfotech.tagscout.data.wms.productStatus
import com.snainfotech.tagscout.data.wms.type
import com.snainfotech.tagscout.data.wms.uom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Everything the product form shows. Text inputs are held as the user typed
 * them; values are cleaned and type-checked only on save.
 *
 * [customValues] holds product-level custom field inputs as strings:
 * numbers as typed, dates as "YYYY-MM-DD", booleans as "true"/"false",
 * dropdowns as the option text. Keys not shown for the current type are
 * kept, so switching type back and forth doesn't lose what was entered.
 */
data class ProductFormState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val productId: String = "",
    val original: Product? = null,

    // Core fields
    val sku: String = "",
    val title: String = "",
    val shortName: String = "",
    val productType: ProductTypeKey = ProductTypeKey.GENERAL,
    val categoryId: String = "",
    val subCategoryId: String = "",
    val brandName: String = "",
    val description: String = "",
    val barcode: String = "",
    val uom: UnitOfMeasure = UnitOfMeasure.DEFAULT,
    val status: ProductStatus = ProductStatus.ACTIVE,

    // Custom fields (product level)
    val customValues: Map<String, String> = emptyMap(),

    // Lookups and settings
    val config: CompanyConfig = CompanyConfig.DEFAULT,
    val categories: List<Category> = emptyList(),   // all types, active + any in use by this product
    val brands: List<Brand> = emptyList(),

    // Feedback
    val coreErrors: Map<String, String> = emptyMap(),   // keys: sku, title, shortName
    val fieldErrors: Map<String, String> = emptyMap(),  // custom field key → message
    val saving: Boolean = false,
    val savedProductId: String? = null,                 // set once a save succeeds; screen navigates back
    val message: String? = null
) {
    /** Types offered in the picker; a product's own type stays listed even if since disabled. */
    val typeOptions: List<ProductTypeDef>
        get() {
            val enabled = config.enabledTypes
            return if (enabled.any { it.key == productType }) enabled
            else enabled + config.typeDef(productType)
        }

    val productFields: List<FieldDef>
        get() = config.fieldsFor(productType, FieldLevel.PRODUCT)

    val topCategories: List<Category>
        get() = categories.filter {
            it.productType == productType.key && it.isTopLevel() && (it.isActive || it.id == categoryId)
        }

    val subCategories: List<Category>
        get() = if (categoryId.isEmpty()) emptyList() else categories.filter {
            it.parentId == categoryId && (it.isActive || it.id == subCategoryId)
        }

    val hasErrors: Boolean get() = coreErrors.isNotEmpty() || fieldErrors.isNotEmpty()
}

/**
 * Create or edit one product. [productId] = null → new product.
 * Shared by every module that manages products (WMS, Retail, ...).
 */
class ProductFormViewModel(
    private val productId: String?,
    private val productRepo: ProductRepository = ProductRepository(),
    private val lookupsRepo: CatalogLookupsRepository = CatalogLookupsRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(ProductFormState(isNew = productId == null))
    val state: StateFlow<ProductFormState> = _state.asStateFlow()

    init {
        // Follow live settings: field definitions and enabled types can change while the form is open.
        viewModelScope.launch {
            ConfigRepository.config.collect { cfg -> _state.update { it.copy(config = cfg) } }
        }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val categories = lookupsRepo.getCategories(includeInactive = true).getOrElse { emptyList() }
            val brands = lookupsRepo.getBrands(includeInactive = true).getOrElse { emptyList() }

            if (productId == null) {
                // New product: default to the first enabled type.
                val firstType = ConfigRepository.current.enabledTypes.firstOrNull()?.key
                    ?: ProductTypeKey.GENERAL
                _state.update {
                    it.copy(loading = false, categories = categories, brands = brands, productType = firstType)
                }
                return@launch
            }

            productRepo.getProduct(productId).fold(
                onSuccess = { product ->
                    if (product == null) {
                        _state.update { it.copy(loading = false, message = "Product not found") }
                        return@fold
                    }
                    val brandName = brands.firstOrNull { b -> b.id == product.brandId }?.name ?: ""
                    _state.update {
                        it.copy(
                            loading = false,
                            isNew = false,
                            productId = product.id,
                            original = product,
                            sku = product.sku,
                            title = product.title,
                            shortName = product.shortName,
                            productType = product.type(),
                            categoryId = product.categoryId,
                            subCategoryId = product.subCategoryId,
                            brandName = brandName,
                            description = product.description,
                            barcode = product.barcode,
                            uom = product.uom(),
                            status = product.productStatus(),
                            customValues = product.customFields.mapValues { (_, v) -> toInputText(v) },
                            categories = categories,
                            brands = brands
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(loading = false, message = "Could not load product: ${e.message}") }
                }
            )
        }
    }

    /** Stored value → text for an input box. */
    private fun toInputText(value: Any?): String = when (value) {
        null -> ""
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.ENGLISH, "%.3f", value).trimEnd('0').trimEnd('.')
        else -> value.toString()
    }

    // ── Edits ─────────────────────────────────────────────────

    fun onSku(v: String) = _state.update { it.copy(sku = v, coreErrors = it.coreErrors - "sku") }
    fun onTitle(v: String) = _state.update { it.copy(title = v, coreErrors = it.coreErrors - "title") }
    fun onShortName(v: String) = _state.update { it.copy(shortName = v, coreErrors = it.coreErrors - "shortName") }
    fun onDescription(v: String) = _state.update { it.copy(description = v) }
    fun onBarcode(v: String) = _state.update { it.copy(barcode = v) }
    fun onBrandName(v: String) = _state.update { it.copy(brandName = v) }
    fun onUom(v: UnitOfMeasure) = _state.update { it.copy(uom = v) }
    fun onStatus(v: ProductStatus) = _state.update { it.copy(status = v) }

    /** Changing type clears category (categories are per type) and re-scopes the custom fields. */
    fun onType(v: ProductTypeKey) = _state.update {
        if (v == it.productType) it
        else it.copy(productType = v, categoryId = "", subCategoryId = "", fieldErrors = emptyMap())
    }

    /** Changing category clears the sub-category. */
    fun onCategory(id: String) = _state.update {
        if (id == it.categoryId) it else it.copy(categoryId = id, subCategoryId = "")
    }

    fun onSubCategory(id: String) = _state.update { it.copy(subCategoryId = id) }

    fun onCustomValue(key: String, value: String) = _state.update {
        it.copy(customValues = it.customValues + (key to value), fieldErrors = it.fieldErrors - key)
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ── Save ──────────────────────────────────────────────────

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return

        // 1. Core checks (repository re-checks; these give per-field messages).
        val coreErrors = buildMap {
            if (s.sku.isBlank()) put("sku", "SKU is required")
            if (s.title.isBlank()) put("title", "Full name is required")
            if (s.shortName.trim().length > Product.SHORT_NAME_MAX) {
                put("shortName", "At most ${Product.SHORT_NAME_MAX} characters")
            }
        }

        // 2. Custom fields against the live definitions for this type.
        val input: Map<String, Any?> = s.productFields.associate { def -> def.key to s.customValues[def.key] }
        val checked = FieldValidator.validate(
            config = s.config,
            type = s.productType,
            level = FieldLevel.PRODUCT,
            input = input,
            previous = s.original?.customFields ?: emptyMap()
        )

        if (coreErrors.isNotEmpty() || !checked.isValid) {
            _state.update {
                it.copy(
                    coreErrors = coreErrors,
                    fieldErrors = checked.errors,
                    message = "Please fix the highlighted fields"
                )
            }
            return
        }

        _state.update { it.copy(saving = true, coreErrors = emptyMap(), fieldErrors = emptyMap()) }

        viewModelScope.launch {
            // 3. Brand: find or auto-create from the typed name.
            val brandId = if (s.brandName.isBlank()) "" else {
                lookupsRepo.findOrCreateBrand(s.brandName).getOrElse { e ->
                    _state.update { it.copy(saving = false, message = "Could not save brand: ${e.message}") }
                    return@launch
                }.id
            }

            val product = (s.original ?: Product()).copy(
                sku = s.sku.trim(),
                title = s.title.trim(),
                shortName = s.shortName.trim(),
                productType = s.productType.key,
                categoryId = s.categoryId,
                subCategoryId = s.subCategoryId,
                brandId = brandId,
                description = s.description.trim(),
                barcode = s.barcode.trim(),
                unitOfMeasure = s.uom.key,
                status = s.status.key,
                customFields = checked.values
            )

            val result: Result<String> = if (s.isNew) {
                productRepo.createProduct(product)
            } else {
                productRepo.updateProduct(product).fold(
                    onSuccess = {
                        // SKU edit goes last: it moves the index and rewrites units.
                        if (s.original != null && s.original.sku != product.sku) {
                            productRepo.changeSku(product.id, product.sku).map { product.id }
                        } else Result.success(product.id)
                    },
                    onFailure = { Result.failure(it) }
                )
            }

            result.fold(
                onSuccess = { id -> _state.update { it.copy(saving = false, savedProductId = id) } },
                onFailure = { e ->
                    val skuClash = e is ProductRepository.DuplicateSkuException
                    _state.update {
                        it.copy(
                            saving = false,
                            coreErrors = if (skuClash) mapOf("sku" to (e.message ?: "SKU already exists")) else it.coreErrors,
                            message = if (skuClash) null else "Could not save: ${e.message}"
                        )
                    }
                }
            )
        }
    }
}

class ProductFormViewModelFactory(private val productId: String?) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ProductFormViewModel(productId) as T
}

/** Field types whose input is a free-text box in the form (vs picker / switch / date dialog). */
internal fun FieldDef.isTypedInput(): Boolean = type == FieldType.TEXT || type == FieldType.NUMBER