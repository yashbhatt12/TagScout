package com.snainfotech.tagscout.data.file

import com.snainfotech.tagscout.ui.screens.quickscan.DetectedTag
import org.dhatim.fastexcel.Workbook
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a Quick Scan result set to an .xlsx file.
 *
 * Mirrors InventoryExcelParser's pattern (same fastexcel dependency,
 * same Workbook/Worksheet shape) so the two writers stay consistent.
 *
 * Column layout:
 *   A  EPC — the tag's Electronic Product Code, hex
 *   B  Signal (dBm) — RSSI, higher (less negative) means closer / stronger
 *   C  Read Count — number of times this EPC was seen in the session
 *   D  First Seen — timestamp of the first read of this EPC
 */
object QuickScanExcelParser {

    private val timestampFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun write(outputStream: OutputStream, tags: List<DetectedTag>) {
        Workbook(outputStream, "TagScout", "1.0").use { workbook ->
            val sheet = workbook.newWorksheet("Quick Scan Result")

            val headers = listOf("EPC", "Signal (dBm)", "Read Count", "First Seen")
            headers.forEachIndexed { col, title ->
                sheet.value(0, col, title)
                sheet.style(0, col).bold().set()
            }

            tags.forEachIndexed { i, tag ->
                val row = i + 1
                sheet.value(row, 0, tag.epc)
                sheet.value(row, 1, tag.signalStrength.toDouble())
                sheet.value(row, 2, tag.count.toDouble())
                sheet.value(row, 3, timestampFormat.format(Date(tag.timestamp)))
            }

            sheet.finish()
        }
    }
}