package com.snainfotech.tagscout.data.config

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Date
import java.util.Locale

/** Result of checking one value: the cleaned value to store (null = absent) or an error. */
data class FieldCheck(val value: Any?, val error: String?) {
    val isValid: Boolean get() = error == null
}

/**
 * Result of checking a whole customFields map.
 * [values] is the complete map to write (cleaned values + untouched values of
 * inactive or unknown fields); [errors] is field key → message.
 */
data class FieldsValidation(val values: Map<String, Any>, val errors: Map<String, String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Checks and normalises custom field values against their definitions.
 *
 * Input can come from a form (strings), Excel (strings, numbers, dates) or
 * Firestore (stored types). Output is always the stored form:
 *
 *   text     → trimmed String
 *   number   → Long when whole, else Double
 *   date     → "YYYY-MM-DD" String
 *   dropdown → the option exactly as defined (case-insensitive match)
 *   boolean  → Boolean
 *
 * An empty value is an absent key, never "". Pure Kotlin — no Android or
 * Firebase types; convert a Firestore Timestamp with toDate() before passing it.
 */
object FieldValidator {

    const val MAX_TEXT_LENGTH = 1000

    /** Excel's day 0 in the 1900 date system (accounts for its 1900 leap-year bug). */
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    private val DATE_INPUT_FORMATS: List<DateTimeFormatter> = listOf(
        "uuuu-MM-dd", "dd-MM-uuuu", "dd/MM/uuuu", "dd.MM.uuuu",
        "d-M-uuuu", "d/M/uuuu", "d.M.uuuu",
        "dd MMM uuuu", "d MMM uuuu", "dd-MMM-uuuu", "d-MMM-uuuu"
    ).map { pattern ->
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .toFormatter(Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT)
    }

    private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

    private val TRUE_WORDS = setOf("true", "yes", "y", "1")
    private val FALSE_WORDS = setOf("false", "no", "n", "0")

    // ── One value ─────────────────────────────────────────────

    /**
     * Checks one value. [previous] is what's stored now: a dropdown value that
     * is no longer in the options list is accepted if it hasn't changed, so
     * removing an option never makes old records unsaveable.
     */
    fun check(def: FieldDef, raw: Any?, previous: Any? = null): FieldCheck {
        if (isBlank(raw)) {
            return if (def.required) FieldCheck(null, "${def.label} is required") else FieldCheck(null, null)
        }
        return when (def.type) {
            FieldType.TEXT -> checkText(def, raw!!)
            FieldType.NUMBER -> checkNumber(def, raw!!)
            FieldType.DATE -> checkDate(def, raw!!)
            FieldType.DROPDOWN -> checkDropdown(def, raw!!, previous)
            FieldType.BOOLEAN -> checkBoolean(def, raw!!)
        }
    }

    private fun isBlank(raw: Any?): Boolean = raw == null || (raw is String && raw.isBlank())

    private fun checkText(def: FieldDef, raw: Any): FieldCheck {
        val text = raw.toString().trim()
        return if (text.length > MAX_TEXT_LENGTH) {
            FieldCheck(null, "${def.label} can be at most $MAX_TEXT_LENGTH characters")
        } else FieldCheck(text, null)
    }

    private fun checkNumber(def: FieldDef, raw: Any): FieldCheck {
        val number: Double? = when (raw) {
            is Number -> raw.toDouble()
            else -> {
                var s = raw.toString().trim()
                // Allow the field's own unit typed after the number: "6.42 g", "500INR"
                if (def.unit.isNotEmpty() && s.endsWith(def.unit, ignoreCase = true)) {
                    s = s.dropLast(def.unit.length).trim()
                }
                // Allow thousands separators: "1,25,000" or "125,000"
                s.replace(",", "").replace(" ", "").toDoubleOrNull()
            }
        }
        if (number == null || number.isNaN() || number.isInfinite()) {
            return FieldCheck(null, "${def.label} must be a number")
        }
        val stored: Any = if (number % 1.0 == 0.0 && kotlin.math.abs(number) < 1e15) number.toLong() else number
        return FieldCheck(stored, null)
    }

    private fun checkDate(def: FieldDef, raw: Any): FieldCheck {
        val date: LocalDate? = when (raw) {
            is LocalDate -> raw
            is Date -> raw.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            is Number -> excelSerialToDate(raw.toDouble())
            else -> parseDateText(raw.toString().trim())
        }
        return if (date == null) {
            FieldCheck(null, "${def.label} must be a date, e.g. 31-03-2027")
        } else FieldCheck(date.format(DateTimeFormatter.ISO_LOCAL_DATE), null)
    }

    private fun parseDateText(text: String): LocalDate? {
        // A bare 5-digit number is an Excel serial that arrived as text.
        text.toIntOrNull()?.takeIf { it in 20000..80000 }?.let { return excelSerialToDate(it.toDouble()) }
        for (format in DATE_INPUT_FORMATS) {
            try {
                return LocalDate.parse(text, format)
            } catch (_: DateTimeParseException) {
                // try the next format
            }
        }
        return null
    }

    /** Excel stores dates as days since 1899-12-30; the fraction is time of day. */
    private fun excelSerialToDate(serial: Double): LocalDate? =
        if (serial >= 1 && serial < 2958466) EXCEL_EPOCH.plusDays(serial.toLong()) else null

    private fun checkDropdown(def: FieldDef, raw: Any, previous: Any?): FieldCheck {
        val text = raw.toString().trim()
        def.options.firstOrNull { it.equals(text, ignoreCase = true) }?.let { return FieldCheck(it, null) }
        if (previous is String && previous == text) return FieldCheck(text, null)
        return FieldCheck(null, "${def.label} must be one of: ${def.options.joinToString(", ")}")
    }

    private fun checkBoolean(def: FieldDef, raw: Any): FieldCheck {
        if (raw is Boolean) return FieldCheck(raw, null)
        val word = raw.toString().trim().lowercase()
        return when (word) {
            in TRUE_WORDS -> FieldCheck(true, null)
            in FALSE_WORDS -> FieldCheck(false, null)
            else -> FieldCheck(null, "${def.label} must be Yes or No")
        }
    }

    // ── Whole map ─────────────────────────────────────────────

    /**
     * Checks every active field of [type] at [level].
     *
     * For each field, the value in [input] is used if the key is present
     * (even as blank, which clears it); otherwise the stored value in
     * [previous] is re-checked. Keys in [previous] that aren't active fields
     * of this type (deactivated fields, fields of another type) are kept
     * untouched, so deactivating a field never loses data.
     */
    fun validate(
        config: CompanyConfig,
        type: ProductTypeKey?,
        level: FieldLevel,
        input: Map<String, Any?>,
        previous: Map<String, Any?> = emptyMap()
    ): FieldsValidation {
        val defs = config.fieldsFor(type, level)
        val activeKeys = defs.map { it.key }.toSet()

        val values = mutableMapOf<String, Any>()
        previous.forEach { (k, v) -> if (k !in activeKeys && v != null) values[k] = v }

        val errors = mutableMapOf<String, String>()
        for (def in defs) {
            val raw = if (input.containsKey(def.key)) input[def.key] else previous[def.key]
            val result = check(def, raw, previous[def.key])
            when {
                result.error != null -> errors[def.key] = result.error
                result.value != null -> values[def.key] = result.value
            }
        }
        return FieldsValidation(values, errors)
    }

    // ── Display and headers ───────────────────────────────────

    /** Human-readable value for lists and detail screens; "" when absent. */
    fun display(def: FieldDef, value: Any?): String {
        if (value == null) return ""
        val text = when (def.type) {
            FieldType.NUMBER -> (value as? Number)?.let { formatNumber(it.toDouble()) } ?: value.toString()
            FieldType.DATE -> runCatching { LocalDate.parse(value.toString()).format(DISPLAY_DATE) }
                .getOrDefault(value.toString())
            FieldType.BOOLEAN -> if (value == true) "Yes" else "No"
            FieldType.TEXT, FieldType.DROPDOWN -> value.toString()
        }
        return if (def.unit.isNotEmpty() && def.type == FieldType.NUMBER) "$text ${def.unit}" else text
    }

    private fun formatNumber(n: Double): String =
        if (n % 1.0 == 0.0) n.toLong().toString()
        else String.format(Locale.ENGLISH, "%.3f", n).trimEnd('0').trimEnd('.')

    /** Lenient header form: lowercase with spaces, "_", "-" and "." removed. "Gross Wt" == "gross_wt". */
    fun headerKey(header: String): String =
        header.trim().lowercase().replace(Regex("[\\s_.\\-]+"), "")

    /** Whether an Excel column header belongs to this field. */
    fun matchesHeader(def: FieldDef, header: String): Boolean {
        val key = headerKey(header)
        return key.isNotEmpty() && (def.excelHeaders + def.label + def.key).any { headerKey(it) == key }
    }
}