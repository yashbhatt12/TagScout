package com.snainfotech.tagscout.data.file

import com.snainfotech.tagscout.ui.screens.inventory.InventoryItem
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import java.io.InputStream
import java.io.OutputStream
import java.util.stream.Collectors

object InventoryExcelParser {

    sealed class ParseResult {
        data class Success(val items: List<InventoryItem>) : ParseResult()
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

                val headerRow = rows.first()
                val headerIndex = mutableMapOf<String, Int>()
                for (i in 0 until headerRow.cellCount) {
                    val text = headerRow.getCellText(i)?.trim()?.lowercase()
                    if (!text.isNullOrBlank()) {
                        headerIndex[text] = i
                    }
                }

                val epcCol = headerIndex["epc code"] ?: headerIndex["epc"]
                if (epcCol == null) {
                    return ParseResult.Error("Missing required column: EPC Code")
                }

                val tidCol = headerIndex["tid"]
                val nameCol = headerIndex["product name"]
                val binCol = headerIndex["bin number"]
                val groupingCol = headerIndex["product grouping"]

                val items = rows.drop(1).mapIndexedNotNull { idx, row ->
                    try {
                        val cellCount = row.cellCount
                        fun cellAt(col: Int?): String =
                            if (col != null && col < cellCount) row.getCellText(col)?.trim().orEmpty() else ""

                        val epc = cellAt(epcCol)
                        if (epc.isBlank()) return@mapIndexedNotNull null

                        InventoryItem(
                            id = idx + 1,
                            epc = epc.uppercase(),
                            tid = cellAt(tidCol),
                            productName = cellAt(nameCol),
                            binNumber = cellAt(binCol).ifBlank { null },
                            productGrouping = cellAt(groupingCol).ifBlank { null }
                        )
                    } catch (rowError: Exception) {
                        null
                    }
                }

                if (items.isEmpty()) {
                    ParseResult.Error("No valid data rows found under the header.")
                } else {
                    ParseResult.Success(items)
                }
            }
        } catch (e: Exception) {
            ParseResult.Error("Could not read the file: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun write(outputStream: OutputStream, items: List<InventoryItem>) {
        Workbook(outputStream, "TagScout", "1.0").use { workbook ->
            val sheet = workbook.newWorksheet("Inventory Result")

            val headers = listOf("EPC Code", "TID", "Product Name", "Bin Number", "Product Grouping", "Status")
            headers.forEachIndexed { col, title ->
                sheet.value(0, col, title)
                sheet.style(0, col).bold().set()
            }

            items.forEachIndexed { i, item ->
                val row = i + 1
                sheet.value(row, 0, item.epc)
                sheet.value(row, 1, item.tid)
                sheet.value(row, 2, item.productName)
                sheet.value(row, 3, item.binNumber ?: "")
                sheet.value(row, 4, item.productGrouping ?: "")
                sheet.value(row, 5, if (item.isFound) "Found" else "Missing")
            }

            sheet.finish()
        }
    }
}