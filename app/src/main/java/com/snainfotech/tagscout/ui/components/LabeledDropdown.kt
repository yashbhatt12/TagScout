package com.snainfotech.tagscout.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.snainfotech.tagscout.ui.theme.Primary

/**
 * A labelled dropdown that picks one item from a list.
 *
 * label      — text shown above the field.
 * options    — items to choose from. If empty, the field is disabled and shows emptyText.
 * selected   — currently selected item (nullable).
 * optionText — how to render each option on screen.
 * onSelect   — called with the chosen item.
 * emptyText  — placeholder when options is empty (e.g. "No warehouses with inventory").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LabeledDropdown(
    label: String,
    options: List<T>,
    selected: T?,
    optionText: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "No options available"
) {
    var expanded by remember { mutableStateOf(false) }
    val isEnabled = options.isNotEmpty()
    val display = selected?.let(optionText) ?: if (isEnabled) "" else emptyText

    ExposedDropdownMenuBox(
        expanded = expanded && isEnabled,
        onExpandedChange = { if (isEnabled) expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            enabled = isEnabled,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && isEnabled)
            },
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                focusedLabelColor = Primary,
                cursorColor = Primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        androidx.compose.material3.DropdownMenu(
            expanded = expanded && isEnabled,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(optionText(opt)) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}