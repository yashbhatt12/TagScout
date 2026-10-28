package com.snainfotech.tagscout.data.file

import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import java.io.InputStream
import java.util.stream.Collectors
import com.snainfotech.tagscout.data.config.Capability
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.CoreField
import com.snainfotech.tagscout.data.config.FieldLevel
import org.dhatim.fastexcel.Workbook
import java.io.OutputStream
/**
 * Parses a GRN (Goods Received Note) Excel file for the Inward workflow.
 *
 * Required columns (case-insensitive, order-independent):
 *   - SKU
 *   - EPC (or "EPC Code")
 *   - Bin Code (or "Bin")
 *   - Warehouse Name (or "Warehouse")
 *
 * Optional columns:
 *   - Serial Number (or "Serial")
 *   - Notes
 *   - Anything else: kept per row in [GrnRow.extras], keyed by the header as
 *     written. GrnService matches these to the product type's unit fields,
 *     tag price and batch columns ("Batch number", "Mfg date", "Expiry date").
 *
 * The parser is deliberately forgiving: structural problems (missing
 * header, empty file) are a top-level Error; row-level problems are
 * collected per row so the user sees every issue at once.
 */
object GrnExcelParser {

    /** One row from the file, as raw parsed data. */
    data class GrnRow(
        val rowNumber: Int,            // matches the Excel row (header = row 1)
        val sku: String,
        val epc: String,
        val serialNumber: String,
        val binCode: String,
        val warehouseName: String,
        val notes: String,
        val parseErrors: List<String>, // filled if the row is malformed
        /** Every other column: header as written → cell text (blank cells omitted). */
        val extras: Map<String, String> = emptyMap()
    )

    sealed class ParseResult {
        data class Success(val rows: List<GrnRow>, val extraHeaders: List<String> = emptyList()) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    private val CORE_HEADERS = setOf(
        "sku", "epc", "epc code", "serial number", "serial",
        "bin code", "bin", "warehouse name", "warehouse", "notes"
    )


    // ── Template ──────────────────────────────────────────────

    private data class TemplateColumn(
        val header: String,
        val required: Boolean,
        val usedBy: String,
        val format: String
    )

    /**
     * Writes the "Download GRN template" workbook. The GRN sheet comes first
     * (parse() reads the first sheet), with headers only; an Instructions
     * sheet explains each column and which product types use it.
     */
    fun writeTemplate(out: OutputStream, config: CompanyConfig) {
        val types = config.enabledTypes
        val all = "All products"
        val columns = mutableListOf(
            TemplateColumn("SKU", true, all, "Product SKU, as in Products"),
            TemplateColumn("EPC", true, all, "Tag EPC, hex (e.g. E2801191A5030060B3F2C1D4)"),
            TemplateColumn("Bin Code", true, all, "Existing bin / tray code"),
            TemplateColumn("Warehouse Name", true, all, "Existing warehouse / store name"),
            TemplateColumn("Serial Number", false,
                types.filter { config.isCoreRequired(it.key, CoreField.SERIAL_NUMBER) }
                    .takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.label} (required)" } ?: all,
                "Text"),
            TemplateColumn("Notes", false, all, "Free text, saved on the inward record")
        )

        val priceTypes = types.filter {
            config.hasCapability(it.key, Capability.RETAIL_SALE) || config.isCoreRequired(it.key, CoreField.PRICE)
        }
        if (priceTypes.isNotEmpty()) {
            columns += TemplateColumn("Tag price", false, priceTypes.joinToString(", ") { it.label }, "Number, in ₹")
        }

        // Per-piece fields across enabled types, one column per distinct header.
        val fieldUse = LinkedHashMap<String, MutableList<String>>()
        val fieldInfo = LinkedHashMap<String, Pair<Boolean, String>>()
        types.forEach { t ->
            config.fieldsFor(t.key, FieldLevel.UNIT).forEach { f ->
                val h = f.exportHeader
                fieldUse.getOrPut(h) { mutableListOf() } += if (f.required) "${t.label} (required)" else t.label
                if (h !in fieldInfo) fieldInfo[h] = false to ProductExcelSchema.fieldFormat(f)
            }
        }
        fieldUse.forEach { (h, users) ->
            columns += TemplateColumn(h, fieldInfo[h]?.first ?: false, users.joinToString(", "), fieldInfo[h]?.second ?: "")
        }

        val batchTypes = types.filter { config.hasCapability(it.key, Capability.BATCH_TRACKING) }
        if (batchTypes.isNotEmpty()) {
            val users = batchTypes.joinToString(", ") { it.label }
            columns += TemplateColumn("Batch number", false, users, "Text. Same number = same batch")
            columns += TemplateColumn("Mfg date", false, users, "Date, e.g. 31-03-2026. Only for a new batch")
            columns += TemplateColumn("Expiry date", false, users, "Date, e.g. 31-03-2027. Only for a new batch")
        }

        Workbook(out, "TagScout", "1.0").use { wb ->
            val ws = wb.newWorksheet("GRN")
            columns.forEachIndexed { c, col ->
                val header = if (col.required) "${col.header} *" else col.header
                ws.value(0, c, header)
                ws.style(0, c).bold().fillColor(if (col.required) "FBF1E3" else "F4F5F7").set()
                ws.width(c, (header.length + 6).coerceIn(12, 40).toDouble())
            }
            ws.finish()

            val info = wb.newWorksheet("Instructions")
            var r = 0
            info.value(r, 0, "TagScout GRN import")
            info.style(r, 0).bold().fontSize(14).set()
            r += 2
            listOf(
                "Fill in the GRN sheet: one row per tag. Keep it as the first sheet.",
                "Columns marked * are required. Leave other cells blank where they don't apply to that product.",
                "Each row is checked against its own product's type before anything is saved.",
                "An existing batch keeps its stored dates; enter dates only for a new batch."
            ).forEach { info.value(r++, 0, it) }
            r++
            listOf("Column", "Required", "Used by", "Format").forEachIndexed { c, h ->
                info.value(r, c, h)
                info.style(r, c).bold().fillColor("E8EEF7").set()
            }
            r++
            columns.forEach { col ->
                info.value(r, 0, col.header)
                info.value(r, 1, if (col.required) "Yes" else "")
                info.value(r, 2, col.usedBy)
                info.value(r, 3, col.format)
                r++
            }
            info.width(0, 26.0)
            info.width(1, 10.0)
            info.width(2, 40.0)
            info.width(3, 50.0)
            info.finish()
        }
    }

    fun parse(inputStream: InputStream): ParseResult {
        return try {
            ReadableWorkbook(inputStream).use { workbook ->
                val sheet = workbook.firstSheet
                val rows: List<Row> = sheet.openStream().use { it.collect(Collectors.toList()) }

                if (rows.isEmpty()) {
                    return ParseResult.Error("The file is empty.")
                }

                // Header → column index (lowercase for core columns; as written for extras)
                val headerRow = rows.first()
                val headerIndex = mutableMapOf<String, Int>()
                val extraColumns = mutableListOf<Pair<String, Int>>()
                for (i in 0 until headerRow.cellCount) {
                    val raw = headerRow.getCellText(i)?.trim().orEmpty()
                    if (raw.isBlank()) continue
                    val lower = raw.lowercase().removeSuffix("*").trim()
                    if (lower in CORE_HEADERS) {
                        headerIndex.putIfAbsent(lower, i)
                    } else {
                        extraColumns += raw.removeSuffix("*").trim() to i
                    }
                }

                val skuCol = headerIndex["sku"]
                val epcCol = headerIndex["epc"] ?: headerIndex["epc code"]
                val serialCol = headerIndex["serial number"] ?: headerIndex["serial"]   // optional
                val binCol = headerIndex["bin code"] ?: headerIndex["bin"]
                val warehouseCol = headerIndex["warehouse name"] ?: headerIndex["warehouse"]
                val notesCol = headerIndex["notes"]                                     // optional

                val missing = buildList {
                    if (skuCol == null) add("SKU")
                    if (epcCol == null) add("EPC")
                    if (binCol == null) add("Bin Code")
                    if (warehouseCol == null) add("Warehouse Name")
                }
                if (missing.isNotEmpty()) {
                    return ParseResult.Error("Missing required column(s): ${missing.joinToString(", ")}")
                }

                if (rows.size < 2) {
                    return ParseResult.Error("No data rows found under the header.")
                }

                val parsedRows = rows.drop(1).mapNotNull { row ->
                    val cellCount = row.cellCount
                    fun cellAt(col: Int?): String {
                        if (col == null || col >= cellCount) return ""
                        return row.getCellText(col)?.trim().orEmpty()
                    }

                    val sku = cellAt(skuCol)
                    val epc = cellAt(epcCol)
                    val serial = cellAt(serialCol)
                    val bin = cellAt(binCol)
                    val warehouse = cellAt(warehouseCol)
                    val notes = cellAt(notesCol)
                    val extras = extraColumns
                        .mapNotNull { (header, col) -> cellAt(col).takeIf { it.isNotBlank() }?.let { header to it } }
                        .toMap()

                    // Skip rows that are entirely empty
                    if (sku.isBlank() && epc.isBlank() && serial.isBlank() &&
                        bin.isBlank() && warehouse.isBlank() && extras.isEmpty()
                    ) return@mapNotNull null

                    val errors = buildList {
                        if (sku.isBlank()) add("SKU is blank")
                        if (epc.isBlank()) add("EPC is blank")
                        if (bin.isBlank()) add("Bin Code is blank")
                        if (warehouse.isBlank()) add("Warehouse Name is blank")
                    }

                    GrnRow(
                        rowNumber = row.rowNum,
                        sku = sku,
                        epc = epc,
                        serialNumber = serial,
                        binCode = bin,
                        warehouseName = warehouse,
                        notes = notes,
                        parseErrors = errors,
                        extras = extras
                    )
                }

                if (parsedRows.isEmpty()) {
                    return ParseResult.Error("No usable data rows found in the file.")
                }

                ParseResult.Success(parsedRows, extraColumns.map { it.first })
            }
        } catch (e: Exception) {
            ParseResult.Error("Could not read the file: ${e.message}")
        }
    }
}