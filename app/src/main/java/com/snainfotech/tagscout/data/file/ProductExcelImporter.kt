package com.snainfotech.tagscout.data.file

import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.FieldLevel
import com.snainfotech.tagscout.data.config.FieldValidator
import com.snainfotech.tagscout.data.config.ProductTypeKey
import com.snainfotech.tagscout.data.wms.Brand
import com.snainfotech.tagscout.data.wms.CatalogLookupsRepository
import com.snainfotech.tagscout.data.wms.Category
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductRepository
import com.snainfotech.tagscout.data.wms.ProductStatus
import com.snainfotech.tagscout.data.wms.UnitOfMeasure
import com.snainfotech.tagscout.data.wms.isTopLevel
import com.snainfotech.tagscout.data.wms.type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import java.io.InputStream
import java.util.stream.Collectors

/**
 * Product catalog import, in two steps:
 *
 *   1. [prepare] reads the workbook and checks every row against settings and
 *      the live catalog, without writing anything. The result is a preview.
 *   2. [commit] saves the preview's new products (and updates, if chosen).
 *
 * Updates never erase: a blank cell keeps the product's current value.
 */
class ProductExcelImporter(
    private val productRepo: ProductRepository = ProductRepository(),
    private val lookupsRepo: CatalogLookupsRepository = CatalogLookupsRepository()
) {

    enum class Action { CREATE, UPDATE, ERROR }

    data class ImportRow(
        val sheet: String,
        val rowNumber: Int,
        val type: ProductTypeKey?,
        val sku: String,
        val title: String,
        val action: Action,
        /** Ready to save: new product, or the existing one with changes applied (keeps its id). */
        val product: Product? = null,
        /** Brand to resolve at commit; "" = none / keep the current one. */
        val brandName: String = "",
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    data class Preview(
        val rows: List<ImportRow>,
        /** Problems that skipped a whole sheet, or columns that were ignored. */
        val sheetNotes: List<String>
    ) {
        val creates: Int get() = rows.count { it.action == Action.CREATE }
        val updates: Int get() = rows.count { it.action == Action.UPDATE }
        val errors: Int get() = rows.count { it.action == Action.ERROR }
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    data class Outcome(
        val created: Int,
        val updated: Int,
        val failed: List<Pair<ImportRow, String>>
    )

    companion object {
        const val MAX_ROWS = 5000
        private const val PARALLELISM = 8
    }

    // ── 1. Prepare ────────────────────────────────────────────

    suspend fun prepare(input: InputStream, config: CompanyConfig): Result<Preview> = runCatching {
        val sheets = withContext(Dispatchers.IO) { readSheets(input) }

        val existing = productRepo.getProducts().getOrThrow()
        val bySkuKey = existing.associateBy { Product.skuKeyOf(it.sku) }
        val categories = lookupsRepo.getCategories(includeInactive = true).getOrElse { emptyList() }
        val brands = lookupsRepo.getBrands(includeInactive = true).getOrElse { emptyList() }
        val brandKeys = brands.map { CatalogLookupsRepository.nameKeyOf(it.name) }.toSet()

        val rows = mutableListOf<ImportRow>()
        val notes = mutableListOf<String>()
        val seenSkus = HashMap<String, String>()   // skuKey → "Sheet, row n"

        for ((sheetName, sheetRows) in sheets) {
            if (FieldValidator.headerKey(sheetName) == FieldValidator.headerKey(ProductExcelSchema.INSTRUCTIONS_SHEET)) continue
            if (sheetRows.isEmpty()) continue

            val headers = headerTexts(sheetRows.first())
            val sheetType = ProductExcelSchema.typeForSheet(sheetName, config)
            val columns = if (sheetType != null) {
                ProductExcelSchema.columnsFor(config, sheetType, includeType = true)
            } else {
                ProductExcelSchema.mixedColumns(config)
            }
            val mapping = ProductExcelSchema.mapHeaders(headers, columns)

            if (sheetType == null && mapping.index(ProductExcelSchema.Core.PRODUCT_TYPE) == null) {
                notes += "Sheet \"$sheetName\" skipped: its name isn't a product type and it has no Product type column."
                continue
            }
            if (sheetType != null && !config.typeDef(sheetType).enabled) {
                notes += "Sheet \"$sheetName\" skipped: ${config.typeDef(sheetType).label} isn't enabled for your company."
                continue
            }
            if (mapping.missingRequired.isNotEmpty()) {
                notes += "Sheet \"$sheetName\" skipped: missing column(s) ${mapping.missingRequired.joinToString(", ") { it.header }}."
                continue
            }
            if (mapping.unknownHeaders.isNotEmpty()) {
                notes += "Sheet \"$sheetName\": ignored column(s) ${mapping.unknownHeaders.joinToString(", ")}."
            }

            for (row in sheetRows.drop(1)) {
                if (rows.size >= MAX_ROWS) {
                    notes += "Stopped at $MAX_ROWS rows. Split the file and import the rest separately."
                    break
                }
                val cells = cellsOf(row, mapping)
                if (cells.values.all { it.isBlank() }) continue

                rows += checkRow(
                    sheetName = sheetName,
                    rowNumber = row.rowNum,
                    sheetType = sheetType,
                    cells = cells,
                    mapping = mapping,
                    config = config,
                    bySkuKey = bySkuKey,
                    categories = categories,
                    brandKeys = brandKeys,
                    seenSkus = seenSkus
                )
            }
        }

        if (rows.isEmpty() && notes.isEmpty()) notes += "No product rows found in the file."
        Preview(rows, notes)
    }

    private fun readSheets(input: InputStream): List<Pair<String, List<Row>>> =
        ReadableWorkbook(input).use { wb ->
            wb.sheets.collect(Collectors.toList()).map { sheet ->
                sheet.name to sheet.openStream().use { it.collect(Collectors.toList()) }
            }
        }

    private fun headerTexts(row: Row): List<String> =
        (0 until row.cellCount).map { i -> row.getCellText(i)?.trim().orEmpty() }

    /** Cell text by column, for the columns this sheet has. */
    private fun cellsOf(row: Row, mapping: ProductExcelSchema.HeaderMapping): Map<ProductExcelSchema.Column, String> =
        mapping.indexOf.mapValues { (_, i) ->
            if (i < row.cellCount) row.getCellText(i)?.trim().orEmpty() else ""
        }

    private fun checkRow(
        sheetName: String,
        rowNumber: Int,
        sheetType: ProductTypeKey?,
        cells: Map<ProductExcelSchema.Column, String>,
        mapping: ProductExcelSchema.HeaderMapping,
        config: CompanyConfig,
        bySkuKey: Map<String, Product>,
        categories: List<Category>,
        brandKeys: Set<String>,
        seenSkus: HashMap<String, String>
    ): ImportRow {
        fun core(c: ProductExcelSchema.Core): String =
            cells.entries.firstOrNull { it.key.core == c }?.value.orEmpty()

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val sku = core(ProductExcelSchema.Core.SKU)
        val title = core(ProductExcelSchema.Core.NAME)
        val skuKey = Product.skuKeyOf(sku)

        // Type: from the sheet, or this row's Product type cell.
        val typeCell = core(ProductExcelSchema.Core.PRODUCT_TYPE)
        val type: ProductTypeKey? = when {
            sheetType != null -> {
                if (typeCell.isNotBlank() && ProductExcelSchema.typeFromCell(typeCell, config) != sheetType) {
                    warnings += "Product type \"$typeCell\" ignored: this sheet is ${config.typeDef(sheetType).label}"
                }
                sheetType
            }
            typeCell.isBlank() -> { errors += "Product type is blank"; null }
            else -> ProductExcelSchema.typeFromCell(typeCell, config).also {
                when {
                    it == null -> errors += "Unknown product type \"$typeCell\""
                    !config.typeDef(it).enabled -> errors += "${config.typeDef(it).label} isn't enabled for your company"
                }
            }
        }

        if (sku.isBlank()) errors += "SKU is blank"
        val firstSeen = if (skuKey.isNotEmpty()) seenSkus[skuKey] else null
        if (firstSeen != null) errors += "SKU $sku appears twice in this file (also $firstSeen)"
        else if (skuKey.isNotEmpty()) seenSkus[skuKey] = "$sheetName, row $rowNumber"

        val current = bySkuKey[skuKey]
        if (current == null && title.isBlank()) errors += "Product name is blank"
        if (current != null && type != null && current.type() != type) {
            errors += "SKU $sku already exists as ${config.typeDef(current.type()).label}. Change its type in the app first."
        }

        // Short name
        val shortName = core(ProductExcelSchema.Core.SHORT_NAME)
        if (shortName.length > Product.SHORT_NAME_MAX) errors += "Short name is longer than ${Product.SHORT_NAME_MAX} characters"

        // Unit of measure
        val uomCell = core(ProductExcelSchema.Core.UOM)
        val uom = if (uomCell.isBlank()) null else UnitOfMeasure.parse(uomCell)
        if (uomCell.isNotBlank() && uom == null) errors += "Unknown unit of measure \"$uomCell\""

        // Status
        val statusCell = core(ProductExcelSchema.Core.STATUS)
        val status = if (statusCell.isBlank()) null else ProductExcelSchema.parseStatus(statusCell)
        if (statusCell.isNotBlank() && status == null) errors += "Status must be Active or Discontinued"

        // Category / sub-category, per type
        val categoryCell = core(ProductExcelSchema.Core.CATEGORY)
        val subCell = core(ProductExcelSchema.Core.SUB_CATEGORY)
        var categoryId: String? = null
        var subCategoryId: String? = null
        if (type != null && categoryCell.isNotBlank()) {
            val cat = categories.firstOrNull {
                it.isTopLevel() && it.productType == type.key && it.nameKey == CatalogLookupsRepository.nameKeyOf(categoryCell)
            }
            if (cat == null) {
                errors += "Unknown category \"$categoryCell\" for ${config.typeDef(type).label}"
            } else {
                if (!cat.isActive) warnings += "Category \"${cat.name}\" is hidden"
                categoryId = cat.id
                if (subCell.isNotBlank()) {
                    val sub = categories.firstOrNull {
                        it.parentId == cat.id && it.nameKey == CatalogLookupsRepository.nameKeyOf(subCell)
                    }
                    if (sub == null) errors += "Unknown sub-category \"$subCell\" under ${cat.name}"
                    else subCategoryId = sub.id
                } else if (current == null || categoryId != current.categoryId) {
                    subCategoryId = ""
                }
            }
        } else if (subCell.isNotBlank() && categoryCell.isBlank()) {
            errors += "Sub-category needs a category in the same row"
        }

        // Brand: resolved at commit; new names are created then.
        val brandCell = core(ProductExcelSchema.Core.BRAND)
        if (brandCell.isNotBlank() && CatalogLookupsRepository.nameKeyOf(brandCell) !in brandKeys) {
            warnings += "New brand \"$brandCell\" will be added"
        }

        // Custom fields: only filled-in cells are input; blanks keep the current value.
        val customInput: Map<String, Any?> = if (type == null) emptyMap() else {
            config.fieldsFor(type, FieldLevel.PRODUCT).mapNotNull { def ->
                val col = mapping.indexOf.keys.firstOrNull { it.field?.key == def.key } ?: return@mapNotNull null
                val text = cells[col].orEmpty()
                if (text.isBlank()) null else def.key to text
            }.toMap()
        }
        val custom = if (type == null) null else FieldValidator.validate(
            config = config,
            type = type,
            level = FieldLevel.PRODUCT,
            input = customInput,
            previous = current?.customFields.orEmpty()
        )
        custom?.errors?.values?.let { errors += it }

        if (errors.isNotEmpty() || type == null || custom == null) {
            return ImportRow(sheetName, rowNumber, type, sku, title, Action.ERROR, errors = errors, warnings = warnings)
        }

        val product = if (current == null) {
            Product(
                sku = sku,
                title = title,
                shortName = shortName,
                productType = type.key,
                categoryId = categoryId.orEmpty(),
                subCategoryId = subCategoryId.orEmpty(),
                description = core(ProductExcelSchema.Core.DESCRIPTION),
                barcode = core(ProductExcelSchema.Core.BARCODE),
                unitOfMeasure = (uom ?: UnitOfMeasure.DEFAULT).key,
                status = (status ?: ProductStatus.ACTIVE).key,
                customFields = custom.values
            )
        } else {
            current.copy(
                title = title.ifBlank { current.title },
                shortName = shortName.ifBlank { current.shortName },
                categoryId = categoryId ?: current.categoryId,
                subCategoryId = subCategoryId ?: current.subCategoryId,
                description = core(ProductExcelSchema.Core.DESCRIPTION).ifBlank { current.description },
                barcode = core(ProductExcelSchema.Core.BARCODE).ifBlank { current.barcode },
                unitOfMeasure = uom?.key ?: current.unitOfMeasure,
                status = status?.key ?: current.status,
                customFields = custom.values
            )
        }

        return ImportRow(
            sheet = sheetName,
            rowNumber = rowNumber,
            type = type,
            sku = sku,
            title = product.title,
            action = if (current == null) Action.CREATE else Action.UPDATE,
            product = product,
            brandName = brandCell,
            warnings = warnings
        )
    }

    // ── 2. Commit ─────────────────────────────────────────────

    /**
     * Saves new products, and updates too when [updateExisting] is on.
     * Brands are resolved (created if new) once each, before any product is written.
     */
    suspend fun commit(
        preview: Preview,
        updateExisting: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Outcome {
        val toSave = preview.rows.filter {
            it.product != null && (it.action == Action.CREATE || (updateExisting && it.action == Action.UPDATE))
        }
        val total = toSave.size
        if (total == 0) return Outcome(0, 0, emptyList())

        val brandIds = HashMap<String, Brand>()
        val brandErrors = HashMap<String, String>()
        toSave.map { it.brandName.trim() }.filter { it.isNotEmpty() }
            .distinctBy { CatalogLookupsRepository.nameKeyOf(it) }
            .forEach { name ->
                val key = CatalogLookupsRepository.nameKeyOf(name)
                lookupsRepo.findOrCreateBrand(name).fold(
                    onSuccess = { brandIds[key] = it },
                    onFailure = { brandErrors[key] = it.message ?: "Could not add brand" }
                )
            }

        var done = 0
        val results: List<Pair<ImportRow, String?>> = coroutineScope {
            toSave.chunked(PARALLELISM).flatMap { chunk ->
                chunk.map { row ->
                    async {
                        val brandKey = CatalogLookupsRepository.nameKeyOf(row.brandName)
                        val error: String? = when {
                            row.brandName.isNotBlank() && brandKey in brandErrors ->
                                "Brand \"${row.brandName}\": ${brandErrors[brandKey]}"
                            else -> {
                                val base = row.product!!
                                val product = brandIds[brandKey]?.let { base.copy(brandId = it.id) } ?: base
                                val result = if (row.action == Action.CREATE) {
                                    productRepo.createProduct(product).map { }
                                } else {
                                    productRepo.updateProduct(product)
                                }
                                result.exceptionOrNull()?.let { it.message ?: "Save failed" }
                            }
                        }
                        row to error
                    }
                }.awaitAll().also {
                    done += it.size
                    onProgress(done, total)
                }
            }
        }

        val failed = results.mapNotNull { (row, err) -> err?.let { row to it } }
        val ok = results.filter { it.second == null }.map { it.first }
        return Outcome(
            created = ok.count { it.action == Action.CREATE },
            updated = ok.count { it.action == Action.UPDATE },
            failed = failed
        )
    }
}