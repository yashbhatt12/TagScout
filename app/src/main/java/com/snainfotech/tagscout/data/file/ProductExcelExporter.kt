package com.snainfotech.tagscout.data.file

import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.FieldType
import com.snainfotech.tagscout.data.config.ProductTypeKey
import com.snainfotech.tagscout.data.wms.CatalogLookupsRepository
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductRepository
import com.snainfotech.tagscout.data.wms.productStatus
import com.snainfotech.tagscout.data.wms.type
import com.snainfotech.tagscout.data.wms.uom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import java.io.OutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Writes the product catalog in the same layout [ProductExcelImporter] reads:
 * one sheet per product type, columns from [ProductExcelSchema]. An exported
 * file can be edited and imported straight back.
 */
class ProductExcelExporter(
    private val productRepo: ProductRepository = ProductRepository(),
    private val lookupsRepo: CatalogLookupsRepository = CatalogLookupsRepository()
) {

    data class Summary(val products: Int, val sheets: Int)

    private val dateOut: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    suspend fun export(out: OutputStream, config: CompanyConfig): Result<Summary> = runCatching {
        val products = productRepo.getProducts().getOrThrow()
        if (products.isEmpty()) throw IllegalStateException("No products to export yet. Use Download sample instead.")

        val categories = lookupsRepo.getCategories(includeInactive = true).getOrElse { emptyList() }
            .associateBy { it.id }
        val brands = lookupsRepo.getBrands(includeInactive = true).getOrElse { emptyList() }
            .associateBy { it.id }

        val byType: List<Pair<ProductTypeKey, List<Product>>> = ProductTypeKey.entries
            .mapNotNull { t ->
                val list = products.filter { it.type() == t }.sortedBy { it.sku.lowercase() }
                if (list.isEmpty()) null else t to list
            }

        withContext(Dispatchers.IO) {
            Workbook(out, "TagScout", "1.0").use { wb ->
                val usedNames = HashSet<String>()
                byType.forEach { (type, list) ->
                    val def = config.typeDef(type)
                    val name = uniqueSheetName(ProductExcelSchema.sheetName(def), usedNames)
                    val ws = wb.newWorksheet(name)
                    val columns = ProductExcelSchema.columnsFor(config, type)

                    columns.forEachIndexed { c, col ->
                        ws.value(0, c, col.header)
                        ws.style(0, c).bold().fillColor("F4F5F7").set()
                        ws.width(c, (col.header.length + 6).coerceIn(12, 40).toDouble())
                    }

                    list.forEachIndexed { i, p ->
                        val r = i + 1
                        columns.forEachIndexed { c, col ->
                            when {
                                col.core != null -> ws.value(r, c, coreValue(col.core, p, config, categories, brands))
                                col.field != null -> writeField(ws, r, c, col.field.type, p.customFields[col.field.key])
                            }
                        }
                    }
                    ws.finish()
                }
            }
        }

        Summary(products = products.size, sheets = byType.size)
    }

    private fun coreValue(
        core: ProductExcelSchema.Core,
        p: Product,
        config: CompanyConfig,
        categories: Map<String, com.snainfotech.tagscout.data.wms.Category>,
        brands: Map<String, com.snainfotech.tagscout.data.wms.Brand>
    ): String = when (core) {
        ProductExcelSchema.Core.SKU -> p.sku
        ProductExcelSchema.Core.NAME -> p.title
        ProductExcelSchema.Core.HSN -> p.hsnCode
        ProductExcelSchema.Core.SHORT_NAME -> p.shortName
        ProductExcelSchema.Core.CATEGORY -> categories[p.categoryId]?.name.orEmpty()
        ProductExcelSchema.Core.BRAND -> brands[p.brandId]?.name.orEmpty()
        ProductExcelSchema.Core.DESCRIPTION -> p.description
        ProductExcelSchema.Core.BARCODE -> p.barcode
        ProductExcelSchema.Core.UOM -> p.uom().symbol
        ProductExcelSchema.Core.STATUS -> p.productStatus().displayName
        ProductExcelSchema.Core.PRODUCT_TYPE -> config.typeDef(p.type()).label
    }

    /** Numbers as numbers; dates as dd-MM-yyyy text (the importer reads it back); booleans as Yes / No. */
    private fun writeField(ws: Worksheet, r: Int, c: Int, type: FieldType, value: Any?) {
        if (value == null) return
        when (type) {
            FieldType.NUMBER -> {
                val n = (value as? Number) ?: value.toString().toDoubleOrNull()
                if (n != null) ws.value(r, c, n) else ws.value(r, c, value.toString())
            }
            FieldType.DATE -> {
                val text = value.toString()
                val shown = runCatching { LocalDate.parse(text).format(dateOut) }.getOrDefault(text)
                ws.value(r, c, shown)
            }
            FieldType.BOOLEAN -> ws.value(r, c, if (value == true || value.toString() == "true") "Yes" else "No")
            FieldType.TEXT, FieldType.DROPDOWN -> ws.value(r, c, value.toString())
        }
    }

    /** Two types with the same label (after trimming to 31 chars) get "(2)", "(3)"… */
    private fun uniqueSheetName(base: String, used: MutableSet<String>): String {
        var name = base
        var n = 2
        while (!used.add(name.lowercase())) {
            val suffix = " ($n)"
            name = base.take(31 - suffix.length) + suffix
            n++
        }
        return name
    }
}