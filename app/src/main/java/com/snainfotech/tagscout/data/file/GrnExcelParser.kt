package com.snainfotech.tagscout.data.file

import com.snainfotech.tagscout.data.config.Capability
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.CoreField
import com.snainfotech.tagscout.data.config.FieldLevel
import org.dhatim.fastexcel.Workbook
import java.io.OutputStream
/**
 * The "Download GRN template" workbook for the Inward workflow.
 *
 * Reading uploaded GRN files happens on the server (functions/src/grn:
 * same columns — SKU, EPC, Bin Code, Warehouse Name required; Serial Number,
 * Notes, tag price, batch and per-piece field columns optional).
 */
object GrnExcelParser {

    /** One row from the file, as raw parsed data. */
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
}
