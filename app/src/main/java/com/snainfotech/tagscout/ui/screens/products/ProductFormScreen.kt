package com.snainfotech.tagscout.ui.screens.products

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.snainfotech.tagscout.data.wms.Brand
import com.snainfotech.tagscout.data.wms.CatalogLookupsRepository
import com.snainfotech.tagscout.data.wms.Category
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductStatus
import com.snainfotech.tagscout.data.wms.UnitOfMeasure
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.CustomFieldInput
import com.snainfotech.tagscout.ui.components.FormHint
import com.snainfotech.tagscout.ui.components.FormSection
import com.snainfotech.tagscout.ui.components.FormTextField
import com.snainfotech.tagscout.ui.components.LabeledDropdown
import com.snainfotech.tagscout.ui.theme.CardBg
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import java.io.File

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
                PhotoSection(state, viewModel)
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
    FormSection("Basics") {
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
            value = state.hsnCode,
            onChange = vm::onHsn,
            label = "HSN code",
            required = true,
            error = state.coreErrors["hsnCode"],
            keyboardType = KeyboardType.Number,
            supporting = "4, 6 or 8 digits"
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
    FormSection("Classification") {
        if (state.topCategories.isEmpty()) {
            FormHint("No categories set up for this product type yet.")
        } else {
            CategoryPicker(
                label = "Category",
                options = state.topCategories,
                selectedId = state.categoryId,
                onSelect = vm::onCategory
            )
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
    FormSection("Details") {
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
                FormHint("Discontinued: existing units stay valid; no new units can be added.")
            }
        }
    }
}

@Composable
private fun CustomFieldsSection(state: ProductFormState, vm: ProductFormViewModel) {
    val fields = state.productFields
    if (fields.isEmpty()) return
    val typeLabel = state.config.typeDef(state.productType).label
    FormSection("$typeLabel details") {
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
@Composable
private fun PhotoSection(state: ProductFormState, vm: ProductFormViewModel) {
    val context = LocalContext.current
    // Saveable: the camera app can push us out of memory while it's open.
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onPhotoPicked(uri, isTemp = false)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (uri != null) {
            if (ok) vm.onPhotoPicked(uri, isTemp = true)
            else runCatching { context.contentResolver.delete(uri, null, null) }
        }
        cameraUri = null
    }

    FormSection("Photo") {
        val shown = state.shownImage
        if (shown != null) {
            AsyncImage(
                model = shown,
                contentDescription = "Product photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LightGray)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(LightGray),
                contentAlignment = Alignment.Center
            ) {
                Text("No photo", color = MediumGray, fontSize = 13.sp)
            }
        }

        when {
            state.pendingImage != null -> FormHint("New photo, uploads when you save.")
            state.removeImage && state.imageUrl.isNotBlank() -> FormHint("Photo will be removed when you save.")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Choose photo", color = Primary) }

            TextButton(onClick = {
                val uri = newCameraUri(context)
                cameraUri = uri
                runCatching { takePhoto.launch(uri) }.onFailure {
                    cameraUri = null
                    Toast.makeText(context, "No camera app found on this device", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Take photo", color = Primary) }

            if (shown != null) {
                TextButton(onClick = vm::onRemovePhoto) { Text("Remove", color = ErrorRed) }
            }
        }
    }
}

/** A fresh temp file in cache/camera/ (see res/xml/file_paths.xml), shared with the camera app. */
private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "product_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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

// ── Building blocks ───────────────────────────────────────────────

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