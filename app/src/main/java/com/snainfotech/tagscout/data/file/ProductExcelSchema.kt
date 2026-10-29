package com.snainfotech.tagscout.data.file

import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.FieldDef
import com.snainfotech.tagscout.data.config.FieldLevel
import com.snainfotech.tagscout.data.config.FieldType
import com.snainfotech.tagscout.data.config.FieldValidator
import com.snainfotech.tagscout.data.config.ProductTypeDef
import com.snainfotech.tagscout.data.config.ProductTypeKey
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductStatus
import com.snainfotech.tagscout.data.wms.UnitOfMeasure
import org.dhatim.fastexcel.Workbook
import java.io.OutputStream

/**
 * Column layout shared by the product import, export and sample workbook.
 *
 * Layout: one sheet per product type, named after the type's label
 * ("Retail jewellery"), so each sheet only has the columns that type uses.
 * Columns = core product columns, then the type's product-level custom fields.
 * A sheet that isn't named after a type can still be imported if it has a
 * "Product type" column.
 *
 * Header matching ignores case, spaces, "_", "-", "." and a trailing "*"
 * (the sample marks required headers with "*").
 */
object ProductExcelSchema {

    const val INSTRUCTIONS_SHEET = "Instructions"
    private const val SHEET_NAME_MAX = 31

    /** Core product columns, in export order. */
    enum class Core(
        val header: String,
        val aliases: List<String>,
        val required: Boolean,
        val hint: String
    ) {
        SKU(
            "SKU", listOf("SKU code", "Product code", "Design no", "Design number"), true,
            "Unique code for the product. Not case-sensitive: SKU-1 and sku-1 are the same product."
        ),
        NAME(
            "Product name", listOf("Name", "Title", "Product"), true,
            "Full name of the product."
        ),
        HSN(
            "HSN code", listOf("HSN", "HSN no", "HSN number", "HSN/SAC"), true,
            "GST HSN code, 4, 6 or 8 digits. Format the column as Text so leading zeros are kept (0401)."
        ),
        SHORT_NAME(
            "Short name", listOf("Label name"), false,
            "Up to ${Product.SHORT_NAME_MAX} characters, for small labels."
        ),
        CATEGORY(
            "Category", emptyList(), false,
            "Must match an existing category for this product type (Company Set Up → Categories & brands)."
        ),
        BRAND(
            "Brand", emptyList(), false,
            "New brand names are added automatically."
        ),
        DESCRIPTION("Description", listOf("Details"), false, "Free text."),
        BARCODE("Barcode", listOf("EAN", "UPC", "QR code"), false, "One barcode or QR value per product."),
        UOM(
            "Unit of measure", listOf("UoM", "Unit", "Units"), false,
            "For example ${UnitOfMeasure.entries.take(6).joinToString(", ") { it.symbol }}. Blank = ${UnitOfMeasure.DEFAULT.symbol}."
        ),
        STATUS("Status", emptyList(), false, "Active or Discontinued. Blank = Active."),
        PRODUCT_TYPE(
            "Product type", listOf("Type"), false,
            "Only needed on a sheet that isn't named after a product type."
        );

        val allHeaders: List<String> get() = listOf(header) + aliases
    }

    /** One column: either a core column or a custom field. */
    data class Column(
        val header: String,
        val required: Boolean,
        val format: String,
        val hint: String,
        val core: Core? = null,
        val field: FieldDef? = null
    ) {
        /** Header as written in the sample: required ones end with " *". */
        val sampleHeader: String get() = if (required) "$header *" else header

        fun matches(rawHeader: String): Boolean {
            val key = normalise(rawHeader)
            if (key.isEmpty()) return false
            core?.let { c -> return c.allHeaders.any { normalise(it) == key } }
            field?.let { f -> return FieldValidator.matchesHeader(f, stripRequiredMark(rawHeader)) }
            return false
        }
    }

    /** Result of reading a sheet's header row against the expected columns. */
    data class HeaderMapping(
        val indexOf: Map<Column, Int>,
        val missingRequired: List<Column>,
        val unknownHeaders: List<String>
    ) {
        fun index(core: Core): Int? = indexOf.entries.firstOrNull { it.key.core == core }?.value
        fun index(field: FieldDef): Int? = indexOf.entries.firstOrNull { it.key.field?.key == field.key }?.value
    }

    // ── Columns ───────────────────────────────────────────────

    /**
     * Columns for one product type. [includeType] adds the "Product type"
     * column (only for sheets not named after a type).
     */
    fun columnsFor(config: CompanyConfig, type: ProductTypeKey, includeType: Boolean = false): List<Column> {
        val core = Core.entries
            .filter { includeType || it != Core.PRODUCT_TYPE }
            .map { c -> Column(c.header, c.required, coreFormat(c), c.hint, core = c) }
        val fields = config.fieldsFor(type, FieldLevel.PRODUCT)
            .filterNot { it.key == Product.HSN_FIELD_KEY }
            .map { f -> Column(f.exportHeader, f.required, fieldFormat(f), "", field = f) }
        return core + fields
    }

    /** Columns for a sheet with a "Product type" column: core columns plus every enabled type's fields. */
    fun mixedColumns(config: CompanyConfig): List<Column> {
        val core = columnsFor(config, ProductTypeKey.GENERAL, includeType = true).filter { it.core != null }
        val fields = config.enabledTypes
            .flatMap { config.fieldsFor(it.key, FieldLevel.PRODUCT) }
            .filterNot { it.key == Product.HSN_FIELD_KEY }
            .distinctBy { it.key.lowercase() }
            .map { f -> Column(f.exportHeader, false, fieldFormat(f), "", field = f) }
        return core + fields
    }

    private fun coreFormat(c: Core): String = when (c) {
        Core.STATUS -> "Active / Discontinued"
        Core.UOM -> "Unit"
        Core.PRODUCT_TYPE -> "Type name"
        Core.HSN -> "4, 6 or 8 digits"
        else -> "Text"
    }

    fun fieldFormat(f: FieldDef): String = when (f.type) {
        FieldType.TEXT -> "Text"
        FieldType.NUMBER -> if (f.unit.isNotEmpty()) "Number (${f.unit})" else "Number"
        FieldType.DATE -> "Date, for example 31-03-2027"
        FieldType.DROPDOWN -> "One of: ${f.options.joinToString(", ")}"
        FieldType.BOOLEAN -> "Yes or No"
    }

    // ── Headers ───────────────────────────────────────────────

    /** Maps a header row onto [columns]. The first matching header wins; repeats are reported as unknown. */
    fun mapHeaders(headers: List<String>, columns: List<Column>): HeaderMapping {
        val indexOf = LinkedHashMap<Column, Int>()
        val unknown = mutableListOf<String>()
        headers.forEachIndexed { i, raw ->
            if (raw.isBlank()) return@forEachIndexed
            val col = columns.firstOrNull { it.matches(raw) && it !in indexOf }
            if (col != null) indexOf[col] = i else unknown += raw.trim()
        }
        val missing = columns.filter { it.required && it !in indexOf }
        return HeaderMapping(indexOf, missing, unknown)
    }

    private fun stripRequiredMark(header: String): String = header.trim().removeSuffix("*").trim()

    private fun normalise(header: String): String = FieldValidator.headerKey(stripRequiredMark(header))

    // ── Sheets ────────────────────────────────────────────────

    /** Excel sheet names: max 31 chars, none of : \ / ? * [ ] */
    fun sheetName(def: ProductTypeDef): String =
        def.label.replace(Regex("[:\\\\/?*\\[\\]]"), " ").trim().take(SHEET_NAME_MAX).ifBlank { def.key.key }

    /**
     * Which product type a sheet name refers to. Matches the type's label,
     * default label or key; then a looser "label contains name" match, so a
     * sheet called "Jewellery" finds "Retail jewellery".
     */
    fun typeForSheet(sheetName: String, config: CompanyConfig): ProductTypeKey? {
        val key = normalise(sheetName)
        if (key.isEmpty()) return null
        val candidates = ProductTypeKey.entries.map { config.typeDef(it) }
        candidates.firstOrNull { def ->
            listOf(def.label, def.key.defaultLabel, def.key.key).any { normalise(it) == key }
        }?.let { return it.key }
        return candidates.firstOrNull { def ->
            normalise(def.label).contains(key) || normalise(def.key.defaultLabel).contains(key)
        }?.key
    }

    /** Product type from a "Product type" cell: label, default label or key. */
    fun typeFromCell(value: String, config: CompanyConfig): ProductTypeKey? {
        val key = normalise(value)
        if (key.isEmpty()) return null
        return ProductTypeKey.entries.firstOrNull { t ->
            val def = config.typeDef(t)
            listOf(def.label, t.defaultLabel, t.key).any { normalise(it) == key }
        }
    }

    fun parseStatus(value: String): ProductStatus? {
        val key = normalise(value)
        if (key.isEmpty()) return ProductStatus.ACTIVE
        return ProductStatus.entries.firstOrNull { normalise(it.displayName) == key || normalise(it.key) == key }
            ?: when (key) {
                "inactive", "no", "n", "0" -> ProductStatus.DISCONTINUED
                "yes", "y", "1" -> ProductStatus.ACTIVE
                else -> null
            }
    }

    // ── Sample workbook ───────────────────────────────────────

    /**
     * Writes the "Download sample" workbook: an Instructions sheet, then one
     * empty sheet per enabled product type with its headers (required ones
     * marked "*"). No example rows, so nothing sample-only is ever imported.
     */
    fun writeSample(out: OutputStream, config: CompanyConfig) {
        val types = config.enabledTypes.ifEmpty { listOf(config.typeDef(ProductTypeKey.GENERAL)) }

        Workbook(out, "TagScout", "1.0").use { wb ->
            // Instructions first, so it's what opens.
            val info = wb.newWorksheet(INSTRUCTIONS_SHEET)
            var r = 0
            info.value(r, 0, "TagScout product import")
            info.style(r, 0).bold().fontSize(14).set()
            r += 2
            listOf(
                "Fill in one sheet per product type. Each sheet only has the columns that type uses.",
                "Columns marked * are required. Leave optional cells blank if they don't apply.",
                "One row per product. Rows with an SKU that already exists are shown in the preview, and you choose whether to update them.",
                "Dates can be written as 31-03-2027, 31/03/2027 or as Excel dates.",
                "Don't rename the sheets. You can delete sheets for types you don't need."
            ).forEach { line ->
                info.value(r, 0, line)
                r++
            }
            r++
            listOf("Sheet", "Column", "Required", "Format", "Notes").forEachIndexed { c, h ->
                info.value(r, c, h)
                info.style(r, c).bold().fillColor("E8EEF7").set()
            }
            r++
            types.forEach { def ->
                columnsFor(config, def.key).forEach { col ->
                    info.value(r, 0, sheetName(def))
                    info.value(r, 1, col.header)
                    info.value(r, 2, if (col.required) "Yes" else "")
                    info.value(r, 3, col.format)
                    info.value(r, 4, col.hint)
                    r++
                }
            }
            info.width(0, 22.0)
            info.width(1, 26.0)
            info.width(2, 10.0)
            info.width(3, 36.0)
            info.width(4, 70.0)
            info.finish()

            types.forEach { def ->
                val ws = wb.newWorksheet(sheetName(def))
                columnsFor(config, def.key).forEachIndexed { c, col ->
                    ws.value(0, c, col.sampleHeader)
                    ws.style(0, c).bold().fillColor(if (col.required) "FBF1E3" else "F4F5F7").set()
                    ws.width(c, (col.sampleHeader.length + 6).coerceIn(12, 40).toDouble())
                }
                ws.finish()
            }
        }
    }
}