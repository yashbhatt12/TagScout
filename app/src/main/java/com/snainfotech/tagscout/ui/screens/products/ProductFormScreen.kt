package com.snainfotech.tagscout.ui.screens.products

import android.widget.Toast
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.config.FieldDef
import com.snainfotech.tagscout.data.config.FieldType
import com.snainfotech.tagscout.data.config.FieldValidator
import com.snainfotech.tagscout.data.wms.Brand
import com.snainfotech.tagscout.data.wms.CatalogLookupsRepository
import com.snainfotech.tagscout.data.wms.Category
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductStatus
import com.snainfotech.tagscout.data.wms.UnitOfMeasure
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.LabeledDropdown
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.CardBg
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Create / edit one product. Shared by every module that manages products.
 *
 * [onSaved] fires once with the product id after a successful save; the
 * caller usually pops back to the list.
 */
@Composable
fun ProductFormScreen(
    viewModel: ProductFormViewModel,
    onBack: () -> Unit,
    onSaved: (productId: String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.savedProductId) {
        state.savedProductId?.let(onSaved)
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = if (state.isNew) "New product" else "Edit product",
            showBackButton = true,
            onBackClick = onBack,
            showMenu = false
        )

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BasicsSection(state, viewModel)
                ClassificationSection(state, viewModel)
                DetailsSection(state, viewModel)
                CustomFieldsSection(state, viewModel)
            }

            SaveBar(
                label = if (state.isNew) "Create product" else "Save changes",
                saving = state.saving,
                onSave = viewModel::save
            )
        }
    }
}

// ── Sections ──────────────────────────────────────────────────────

@Composable
private fun BasicsSection(state: ProductFormState, vm: ProductFormViewModel) {
    SectionCard("Basics") {
        LabeledDropdown(
            label = "Product type *",
            options = state.typeOptions,
            selected = state.typeOptions.firstOrNull { it.key == state.productType },
            optionText = { it.label },
            onSelect = { vm.onType(it.key) }
        )
        FormTextField(
            value = state.sku,
            onChange = vm::onSku,
            label = "SKU / item code",
            required = true,
            error = state.coreErrors["sku"],
            capitalization = KeyboardCapitalization.Characters,
            supporting = if (!state.isNew) "Changing the SKU also updates it on every unit" else null
        )
        FormTextField(
            value = state.title,
            onChange = vm::onTitle,
            label = "Full name",
            required = true,
            error = state.coreErrors["title"],
            capitalization = KeyboardCapitalization.Words
        )
        FormTextField(
            value = state.shortName,
            onChange = { if (it.length <= Product.SHORT_NAME_MAX) vm.onShortName(it) },
            label = "Short name",
            error = state.coreErrors["shortName"],
            supporting = "For small labels · ${state.shortName.length}/${Product.SHORT_NAME_MAX}"
        )
    }
}

@Composable
private fun ClassificationSection(state: ProductFormState, vm: ProductFormViewModel) {
    SectionCard("Classification") {
        if (state.topCategories.isEmpty()) {
            HintText("No categories set up for this product type yet.")
        } else {
            CategoryPicker(
                label = "Category",
                options = state.topCategories,
                selectedId = state.categoryId,
                onSelect = vm::onCategory
            )
            if (state.subCategories.isNotEmpty()) {
                CategoryPicker(
                    label = "Sub-category",
                    options = state.subCategories,
                    selectedId = state.subCategoryId,
                    onSelect = vm::onSubCategory
                )
            }
        }
        BrandField(
            value = state.brandName,
            brands = state.brands,
            onChange = vm::onBrandName
        )
    }
}

@Composable
private fun DetailsSection(state: ProductFormState, vm: ProductFormViewModel) {
    SectionCard("Details") {
        LabeledDropdown(
            label = "Unit of measure *",
            options = UnitOfMeasure.entries.toList(),
            selected = state.uom,
            optionText = { "${it.singular} (${it.symbol})" },
            onSelect = vm::onUom
        )
        FormTextField(
            value = state.barcode,
            onChange = vm::onBarcode,
            label = "Barcode / QR"
        )
        FormTextField(
            value = state.description,
            onChange = vm::onDescription,
            label = "Description",
            singleLine = false,
            minLines = 2
        )
        if (!state.isNew) {
            LabeledDropdown(
                label = "Status",
                options = ProductStatus.entries.toList(),
                selected = state.status,
                optionText = { it.displayName },
                onSelect = vm::onStatus
            )
            if (state.status == ProductStatus.DISCONTINUED) {
                HintText("Discontinued: existing units stay valid; no new units can be added.")
            }
        }
    }
}

@Composable
private fun CustomFieldsSection(state: ProductFormState, vm: ProductFormViewModel) {
    val fields = state.productFields
    if (fields.isEmpty()) return
    val typeLabel = state.config.typeDef(state.productType).label
    SectionCard("$typeLabel details") {
        fields.forEach { def ->
            CustomFieldInput(
                def = def,
                value = state.customValues[def.key] ?: "",
                error = state.fieldErrors[def.key],
                onChange = { vm.onCustomValue(def.key, it) }
            )
        }
    }
}

// ── Pickers ───────────────────────────────────────────────────────

/** Category dropdown with a "None" choice first. */
@Composable
private fun CategoryPicker(
    label: String,
    options: List<Category>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    val choices: List<Category?> = listOf<Category?>(null) + options
    LabeledDropdown(
        label = label,
        options = choices,
        selected = options.firstOrNull { it.id == selectedId },
        optionText = { c -> c?.let { if (it.isActive) it.name else "${it.name} (inactive)" } ?: "None" },
        onSelect = { onSelect(it?.id ?: "") }
    )
}

/** Free-text brand with suggestions from the company's list; unknown names are added on save. */
@Composable
private fun BrandField(value: String, brands: List<Brand>, onChange: (String) -> Unit) {
    val typedKey = CatalogLookupsRepository.nameKeyOf(value)
    val exact = brands.firstOrNull { it.nameKey == typedKey }
    val suggestions = if (typedKey.isEmpty() || exact != null) emptyList() else
        brands.filter { it.isActive && it.nameKey.contains(typedKey) }.take(5)

    Column {
        FormTextField(
            value = value,
            onChange = onChange,
            label = "Brand",
            capitalization = KeyboardCapitalization.Words,
            supporting = when {
                typedKey.isEmpty() -> null
                exact != null -> null
                else -> "New brand — it will be added when you save"
            }
        )
        suggestions.forEach { brand ->
            Text(
                text = brand.name,
                fontSize = 13.sp,
                color = Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChange(brand.name) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

// ── Custom field inputs ───────────────────────────────────────────

@Composable
private fun CustomFieldInput(def: FieldDef, value: String, error: String?, onChange: (String) -> Unit) {
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
                error?.let { ErrorText(it) }
            }
        }

        FieldType.BOOLEAN -> Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
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
            error?.let { ErrorText(it) }
        }

        FieldType.DATE -> DateFieldInput(def, value, error, onChange)
    }
}

/** Read-only box showing "31 Mar 2027"; tapping opens a date picker. Value is "YYYY-MM-DD". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFieldInput(def: FieldDef, value: String, error: String?, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = if (value.isBlank()) "" else FieldValidator.display(def, value)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            FormTextField(
                value = shown,
                onChange = {},
                label = def.label,
                required = def.required,
                error = error,
                readOnly = true,
                supporting = if (value.isBlank()) "Tap to pick a date" else null
            )
            // Transparent layer so a tap anywhere on the field opens the picker.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { open = true }
            )
        }
        if (value.isNotBlank() && !def.required) {
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

/** The date picker works in UTC midnight millis; stored dates are plain "YYYY-MM-DD". */
private fun isoToUtcMillis(iso: String): Long? = runCatching {
    LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

private fun utcMillisToIso(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

// ── Building blocks ───────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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

@Composable
private fun FormTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    required: Boolean = false,
    error: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    suffix: String? = null
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
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Primary,
            focusedLabelColor = Primary,
            cursorColor = Primary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SaveBar(label: String, saving: Boolean, onSave: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg)
            .padding(16.dp)
    ) {
        Button(
            onClick = onSave,
            enabled = !saving,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            if (saving) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(label, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text = text, fontSize = 12.sp, color = MediumGray)
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = ErrorRed,
        modifier = Modifier.padding(start = 16.dp, top = 2.dp)
    )
}