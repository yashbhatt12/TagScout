package com.snainfotech.tagscout.ui.components

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.config.FieldDef
import com.snainfotech.tagscout.data.config.FieldType
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.CardBg
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared form building blocks for data-entry screens (products, enrollment,
 * unit details, imports). One copy, so a fix lands everywhere.
 *
 * Dates are always "YYYY-MM-DD" strings in and out.
 */

/** Content width cap: forms stay readable on tablets instead of stretching edge to edge. */
val FORM_MAX_WIDTH: Dp = 640.dp

// ── Layout ────────────────────────────────────────────────────────────────

/** Scrolling content capped at [FORM_MAX_WIDTH] (centred on tablets) above a fixed action bar. */
@Composable
fun FormPane(
    bar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = FORM_MAX_WIDTH)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
        if (bar != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBg)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .widthIn(max = FORM_MAX_WIDTH)
                        .fillMaxWidth()
                ) { bar() }
            }
        }
    }
}

/** White card with a small uppercase title. */
@Composable
fun FormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MediumGray
            )
            content()
        }
    }
}

/** Read-only label / value line for detail views. */
@Composable
fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MediumGray,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value.ifBlank { "—" },
            fontSize = 13.sp,
            color = DarkText,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
fun FormHint(text: String) {
    Text(text = text, fontSize = 12.sp, color = MediumGray)
}

@Composable
fun FormError(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = ErrorRed,
        modifier = Modifier.padding(start = 16.dp, top = 2.dp)
    )
}

// ── Inputs ────────────────────────────────────────────────────────────────

@Composable
fun FormTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    required: Boolean = false,
    error: String? = null,
    supporting: String? = null,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    suffix: String? = null,
    modifier: Modifier = Modifier
) {
    val helper = error ?: supporting
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(if (required) "$label *" else label) },
        isError = error != null,
        supportingText = helper?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it, color = MediumGray) } },
        singleLine = singleLine,
        minLines = minLines,
        readOnly = readOnly,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            focusedLabelColor = Primary,
            cursorColor = Primary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
    )
}

/** One custom field, drawn by its type. Values are form text; dates "YYYY-MM-DD", booleans "true"/"false". */
@Composable
fun CustomFieldInput(def: FieldDef, value: String, error: String?, onChange: (String) -> Unit) {
    when (def.type) {
        FieldType.TEXT -> FormTextField(
            value = value, onChange = onChange, label = def.label,
            required = def.required, error = error
        )

        FieldType.NUMBER -> FormTextField(
            value = value, onChange = onChange, label = def.label,
            required = def.required, error = error,
            keyboardType = KeyboardType.Decimal,
            suffix = def.unit.ifEmpty { null }
        )

        FieldType.DROPDOWN -> {
            // "" = no value (only offered when optional). A stored value that's
            // no longer an option stays listed so the record can still be saved.
            val legacy = value.takeIf { it.isNotEmpty() && def.options.none { o -> o == it } }
            val options = buildList {
                if (!def.required) add("")
                addAll(def.options)
                legacy?.let { add(it) }
            }
            Column {
                LabeledDropdown(
                    label = if (def.required) "${def.label} *" else def.label,
                    options = options,
                    selected = value.takeIf { it.isNotEmpty() },
                    optionText = { if (it.isEmpty()) "—" else it },
                    onSelect = onChange
                )
                error?.let { FormError(it) }
            }
        }

        FieldType.BOOLEAN -> Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (def.required) "${def.label} *" else def.label,
                    fontSize = 14.sp,
                    color = DarkText,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = value == "true",
                    onCheckedChange = { onChange(it.toString()) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Primary)
                )
            }
            error?.let { FormError(it) }
        }

        FieldType.DATE -> DateField(
            label = def.label,
            required = def.required,
            value = value,
            error = error,
            onChange = onChange
        )
    }
}

/** Read-only box showing "31 Mar 2027"; tapping opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    required: Boolean,
    value: String,
    error: String?,
    enabled: Boolean = true,
    onChange: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            FormTextField(
                value = displayIsoDate(value),
                onChange = {},
                label = label,
                required = required,
                error = error,
                readOnly = true,
                enabled = enabled,
                supporting = if (value.isBlank() && enabled) "Tap to pick a date" else null
            )
            if (enabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { open = true }
                )
            }
        }
        if (enabled && value.isNotBlank() && !required) {
            TextButton(onClick = { onChange("") }) { Text("Clear", color = Primary) }
        }
    }

    if (open) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = isoToUtcMillis(value))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onChange(utcMillisToIso(it)) }
                    open = false
                }) { Text("OK", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text("Cancel", color = MediumGray) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

/** "2027-03-31" → "31 Mar 2027"; anything unparseable is shown as is. */
fun displayIsoDate(iso: String): String =
    if (iso.isBlank()) "" else runCatching { LocalDate.parse(iso).format(DISPLAY_DATE) }.getOrDefault(iso)

/** The date picker works in UTC midnight millis. */
private fun isoToUtcMillis(iso: String): Long? = runCatching {
    LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

private fun utcMillisToIso(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

// ── Buttons ───────────────────────────────────────────────────────────────

@Composable
fun PrimaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    loading: Boolean = false,
    color: Color = Primary
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = modifier.height(48.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SecondaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (enabled) Primary else BorderGray),
        modifier = modifier.height(48.dp)
    ) {
        Text(label, color = if (enabled) Primary else MediumGray, fontWeight = FontWeight.SemiBold)
    }
}

// ── Desktop reader (USB, keyboard mode) ──────────────────────────────────

/**
 * Collects characters typed by a USB reader in keyboard mode. The reader
 * "types" the EPC and ends with Enter (or Tab on some models). A pause
 * longer than [GAP_MS] starts a fresh code, so a half-typed read or a stray
 * key press is dropped. Callers still validate the EPC format.
 *
 * Use with Modifier.focusRequester(...).focusable().onPreviewKeyEvent { ... }
 * on the screen root, requesting focus while the screen is listening.
 */
class KeyboardWedgeBuffer {
    private val buffer = StringBuilder()
    private var lastKeyAt = 0L

    fun clear() {
        buffer.setLength(0)
    }

    /** Returns a complete code when Enter/Tab arrives, else null. */
    fun onKey(event: KeyEvent): String? {
        if (event.type != KeyEventType.KeyDown) return null
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyAt > GAP_MS) buffer.setLength(0)
        lastKeyAt = now

        if (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Tab) {
            val code = buffer.toString()
            buffer.setLength(0)
            return code.ifEmpty { null }
        }
        val ch = event.utf16CodePoint.toChar()
        if (ch.isLetterOrDigit()) buffer.append(ch)
        if (buffer.length > MAX_CODE_LENGTH) buffer.setLength(0)
        return null
    }

    private companion object {
        const val GAP_MS = 1000L
        const val MAX_CODE_LENGTH = 256
    }
}