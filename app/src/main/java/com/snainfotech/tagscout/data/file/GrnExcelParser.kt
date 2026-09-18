package com.snainfotech.tagscout.data.file

import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import java.io.InputStream
import java.util.stream.Collectors

/**
 * Parses a GRN (Goods Received Note) Excel file for the Inward workflow.
 *
 * Required columns (case-insensitive, order-independent):
 *   - SKU
 *   - EPC (or "EPC Code")
 *   - Serial Number
 *   - Bin Code
 *   - Warehouse Name
 *
 * Optional column:
 *   - Notes
 *
 * Each data row becomes a GrnRow. Structural problems (missing header,
 * empty file) return a top-level Error. Row-level content problems
 * (blank required cells, invalid data) are captured inside each
 * GrnRow as validation errors and handled later by the validator —
 * the parser itself is deliberately forgiving so the user can see
 * all row errors at once instead of one at a time.
 */
object GrnExcelParser {

    /** One row from the file, as raw parsed data. */
    data class GrnRow(
        val rowNumber: Int,            // 1-indexed matching the Excel row (header = row 1)
        val sku: String,
        val epc: String,
        val serialNumber: String,
        val binCode: String,
        val warehouseName: String,
        val notes: String,
        val parseErrors: List<String>  // filled if the row is malformed
    )

    sealed class ParseResult {
        data class Success(val rows: List<GrnRow>) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    fun parse(inputStream: InputStream): ParseResult {
        return try {
            ReadableWorkbook(inputStream).use { workbook ->
                val sheet = workbook.firstSheet
                val rows: List<Row> = sheet.openStream().use { it.collect(Collectors.toList()) }

                if (rows.isEmpty()) {
                    return ParseResult.Error("The file is empty.")
                }

                // Build a lowercase header → column-index map
                val headerRow = rows.first()
                val headerIndex = mutableMapOf<String, Int>()
                for (i in 0 until headerRow.cellCount) {
                    val text = headerRow.getCellText(i)?.trim()?.lowercase()
                    if (!text.isNullOrBlank()) {
                        headerIndex[text] = i
                    }
                }

                // Required columns — abort the whole parse if any are missing
                val skuCol = headerIndex["sku"]
                val epcCol = headerIndex["epc"] ?: headerIndex["epc code"]
                val serialCol = headerIndex["serial number"] ?: headerIndex["serial"]
                val binCol = headerIndex["bin code"] ?: headerIndex["bin"]
                val warehouseCol = headerIndex["warehouse name"] ?: headerIndex["warehouse"]
                val notesCol = headerIndex["notes"]  // optional

                val missing = buildList {
                    if (skuCol == null) add("SKU")
                    if (epcCol == null) add("EPC")
                    if (serialCol == null) add("Serial Number")
                    if (binCol == null) add("Bin Code")
                    if (warehouseCol == null) add("Warehouse Name")
                }
                if (missing.isNotEmpty()) {
                    return ParseResult.Error("Missing required column(s): ${missing.joinToString(", ")}")
                }

                if (rows.size < 2) {
                    return ParseResult.Error("No data rows found under the header.")
                }

                // Parse each data row; row-level issues are collected in parseErrors
                val parsedRows = rows.drop(1).mapIndexedNotNull { idx, row ->
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

                    // Skip rows that are entirely empty
                    if (sku.isBlank() && epc.isBlank() && serial.isBlank()
                        && bin.isBlank() && warehouse.isBlank()) return@mapIndexedNotNull null

                    // Collect blank-field errors — one per missing required field
                    val errors = buildList {
                        if (sku.isBlank()) add("SKU is blank")
                        if (epc.isBlank()) add("EPC is blank")
                        if (bin.isBlank()) add("Bin Code is blank")
                        if (warehouse.isBlank()) add("Warehouse Name is blank")
                        // Serial Number can be blank — some suppliers don't provide serials
                    }

                    GrnRow(
                        rowNumber = idx + 2,  // idx=0 is Excel row 2 (row 1 is header)
                        sku = sku,
                        epc = epc,
                        serialNumber = serial,
                        binCode = bin,
                        warehouseName = warehouse,
                        notes = notes,
                        parseErrors = errors
                    )
                }

                if (parsedRows.isEmpty()) {
                    return ParseResult.Error("No usable data rows found in the file.")
                }

                ParseResult.Success(parsedRows)
            }
        } catch (e: Exception) {
            ParseResult.Error("Could not read the file: ${e.message}")
        }
    }
}