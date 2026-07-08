package com.snainfotech.tagscout.data.file

import com.snainfotech.tagscout.ui.screens.orderpicking.OrderPickingItem
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.dhatim.fastexcel.reader.Row
import java.io.InputStream
import java.io.OutputStream
import java.util.stream.Collectors

object OrderPickingExcelParser {

    private val REQUIRED_HEADERS = listOf(
        "product name", "product id", "epc code", "bin number", "order id"
    )

    sealed class ParseResult {
        data class Success(val items: List<OrderPickingItem>) : ParseResult()
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

                val missing = REQUIRED_HEADERS.filter { it !in headerIndex.keys }
                if (missing.isNotEmpty()) {
                    return ParseResult.Error(
                        "Missing required column(s): " +
                                missing.joinToString(", ") { name -> name.replaceFirstChar(Char::uppercase) }
                    )
                }

                val productNameCol = headerIndex.getValue("product name")
                val productIdCol = headerIndex.getValue("product id")
                val epcCol = headerIndex.getValue("epc code")
                val binCol = headerIndex.getValue("bin number")
                val orderIdCol = headerIndex.getValue("order id")

                val items = rows.drop(1).mapIndexedNotNull { idx, row ->
                    try {
                        val cellCount = row.cellCount
                        fun cellAt(col: Int): String =
                            if (col < cellCount) row.getCellText(col)?.trim().orEmpty() else ""

                        val epc = cellAt(epcCol)
                        if (epc.isBlank()) return@mapIndexedNotNull null

                        OrderPickingItem(
                            rowIndex = idx,
                            orderId = cellAt(orderIdCol),
                            productId = cellAt(productIdCol),
                            productName = cellAt(productNameCol),
                            epc = epc.uppercase(),
                            binNumber = cellAt(binCol)
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

    fun write(outputStream: OutputStream, items: List<OrderPickingItem>) {
        Workbook(outputStream, "TagScout", "1.0").use { workbook ->
            val sheet = workbook.newWorksheet("Order Picking Result")

            val headers = listOf("Order ID", "Product ID", "Product Name", "EPC Code", "Bin Number", "Picked")
            headers.forEachIndexed { col, title ->
                sheet.value(0, col, title)
                sheet.style(0, col).bold().set()
            }

            items.sortedBy { it.rowIndex }.forEachIndexed { i, item ->
                val row = i + 1
                sheet.value(row, 0, item.orderId)
                sheet.value(row, 1, item.productId)
                sheet.value(row, 2, item.productName)
                sheet.value(row, 3, item.epc)
                sheet.value(row, 4, item.binNumber)
                sheet.value(row, 5, if (item.picked) "Picked" else "Not Picked")
            }

            sheet.finish()
        }
    }
}