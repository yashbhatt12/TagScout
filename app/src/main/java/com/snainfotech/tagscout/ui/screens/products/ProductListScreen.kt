package com.snainfotech.tagscout.ui.screens.products

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.time.LocalDate
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.data.wms.ProductStatus
import com.snainfotech.tagscout.data.wms.productStatus
import com.snainfotech.tagscout.data.wms.uom
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.CardBg
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary
import com.snainfotech.tagscout.ui.theme.WarningBg
import com.snainfotech.tagscout.ui.theme.WarningText

/**
 * The company's product catalog. Shared by every module that manages products.
 * Tap a row to edit, long-press to delete, "+" to add.
 */
@Composable
fun ProductListScreen(
    viewModel: ProductListViewModel,
    onBack: () -> Unit,
    onAddProduct: () -> Unit,
    onEditProduct: (productId: String) -> Unit,
    onManageLookups: () -> Unit = {},
    onImportProducts: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<Product?>(null) }

    // Reload whenever the list comes back into view (e.g. after saving in the form).
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LIST_XLSX_MIME)
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportTo { context.contentResolver.openOutputStream(uri) }
    }
    val sampleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LIST_XLSX_MIME)
    ) { uri: Uri? ->
        if (uri != null) viewModel.writeSample { context.contentResolver.openOutputStream(uri) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = "Products",
            showBackButton = true,
            onBackClick = onBack,
            showMenu = false
        )

        Box(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                SearchAndFilters(
                    state = state,
                    vm = viewModel,
                    onManageLookups = onManageLookups,
                    onExcelImport = onImportProducts,
                    onExcelExport = { exportLauncher.launch("TagScout products ${LocalDate.now()}.xlsx") },
                    onExcelSample = { sampleLauncher.launch("TagScout products sample.xlsx") }
                )

                when {
                    state.loading -> CenteredBox { CircularProgressIndicator(color = Primary) }

                    state.rows.isEmpty() -> CenteredBox {
                        EmptyText("No products yet.\nTap + to add your first one.")
                    }

                    state.visibleRows.isEmpty() -> CenteredBox {
                        EmptyText("No products match your search.")
                    }

                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.visibleRows, key = { it.product.id }) { row ->
                            ProductRowCard(
                                row = row,
                                onClick = { onEditProduct(row.product.id) },
                                onLongClick = { pendingDelete = row.product }
                            )
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = onAddProduct,
                containerColor = Primary,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            ) {
                Text("+", fontSize = 26.sp, fontWeight = FontWeight.Light)
            }
        }
    }

    pendingDelete?.let { product ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${product.sku}?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "This removes the product from the catalog. It can't be deleted while any tagged units still use it.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(product)
                    pendingDelete = null
                }) { Text("Delete", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel", color = MediumGray) }
            }
        )
    }
}

@Composable
private fun SearchAndFilters(
    state: ProductListState,
    vm: ProductListViewModel,
    onManageLookups: () -> Unit,
    onExcelImport: () -> Unit,
    onExcelExport: () -> Unit,
    onExcelSample: () -> Unit
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQuery,
            placeholder = { Text("Search SKU, name, barcode, brand") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                cursorColor = Primary,
                unfocusedContainerColor = CardBg,
                focusedContainerColor = CardBg
            ),
            modifier = Modifier.fillMaxWidth()
        )

        val filters = state.typeFilters
        if (filters.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                item {
                    TypeChip("All", selected = state.typeFilter == null) { vm.onTypeFilter(null) }
                }
                items(filters, key = { it.key.key }) { def ->
                    TypeChip(def.label, selected = state.typeFilter == def.key) { vm.onTypeFilter(def.key) }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${state.visibleRows.size} of ${state.rows.size}",
                fontSize = 12.sp,
                color = MediumGray,
                modifier = Modifier.weight(1f)
            )
            ExcelMenu(
                exporting = state.exporting,
                onImport = onExcelImport,
                onExport = onExcelExport,
                onSample = onExcelSample
            )
            TextButton(onClick = onManageLookups) {
                Text("Categories & brands ›", fontSize = 12.sp, color = Primary)
            }
            TextButton(onClick = onManageLookups) {
                Text("Categories & brands ›", fontSize = 12.sp, color = Primary)
            }
        }

        if (state.discontinuedCount > 0) {
            TextButton(
                onClick = { vm.onShowDiscontinued(!state.showDiscontinued) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = if (state.showDiscontinued) "Hide discontinued"
                    else "Show discontinued (${state.discontinuedCount})",
                    fontSize = 12.sp,
                    color = Primary
                )
            }
        }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Primary,
            selectedLabelColor = Color.White,
            containerColor = CardBg
        )
    )
}

// ── Rows ──────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProductRowCard(row: ProductRow, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = row.product
    val discontinued = p.productStatus() == ProductStatus.DISCONTINUED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Thumbnail(imageUrl = p.imageUrl, fallbackText = p.title.ifBlank { p.sku })
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = p.sku,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    Badge(row.typeLabel, background = Primary.copy(alpha = 0.08f), text = Primary)
                    if (discontinued) {
                        Spacer(Modifier.width(6.dp))
                        Badge("Discontinued", background = WarningBg, text = WarningText)
                    }
                }

                Text(
                    text = p.title,
                    fontSize = 13.sp,
                    color = DarkText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )

                val meta = listOf(row.categoryName, row.brandName, p.uom().symbol).filter { it.isNotEmpty() }
                Text(
                    text = meta.joinToString("  ·  "),
                    fontSize = 11.sp,
                    color = MediumGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** 48 dp product photo, or the name's first letter on grey when there's none. */
@Composable
private fun Thumbnail(imageUrl: String, fallbackText: String) {
    val shape = RoundedCornerShape(8.dp)
    if (imageUrl.isNotBlank()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(LightGray)
        )
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(LightGray),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fallbackText.take(1).uppercase(),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MediumGray
            )
        }
    }
}

@Composable
private fun Badge(label: String, background: Color, text: Color) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = text)
    }
}

// ── Small helpers ─────────────────────────────────────────────────

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MediumGray,
        modifier = Modifier.padding(32.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}
private const val LIST_XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

@Composable
private fun ExcelMenu(
    exporting: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSample: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = !exporting) {
            Text(if (exporting) "Exporting…" else "Excel ▾", fontSize = 12.sp, color = Primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Import from Excel") },
                onClick = { open = false; onImport() }
            )
            DropdownMenuItem(
                text = { Text("Export to Excel") },
                onClick = { open = false; onExport() }
            )
            DropdownMenuItem(
                text = { Text("Download sample") },
                onClick = { open = false; onSample() }
            )
        }
    }
}