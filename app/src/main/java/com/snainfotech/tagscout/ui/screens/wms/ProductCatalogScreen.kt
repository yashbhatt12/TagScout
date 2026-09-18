package com.snainfotech.tagscout.ui.screens.wms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.wms.Product
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary

// Destructive-action red, used only for the Delete menu label and confirm button.
private val DestructiveRed = Color(0xFFD32F2F)

@Composable
fun ProductCatalogScreen(
    state: ProductCatalogState,
    onBackClick: () -> Unit,
    onCreateProduct: (sku: String, title: String, description: String, uom: String) -> Unit,
    onUpdateProduct: (id: String, title: String, description: String, uom: String) -> Unit,
    onDeleteProduct: (Product) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }

    // Non-null values mean the corresponding dialog is showing for that product.
    var editingProduct by remember { mutableStateOf<Product?>(null) }
    var deletingProduct by remember { mutableStateOf<Product?>(null) }

    // ── Add Product dialog ─────────────────────────────────────
    if (showAddDialog) {
        var sku by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var uom by remember { mutableStateOf("pcs") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Product", fontWeight = FontWeight.SemiBold, color = DarkText) },
            text = {
                Column {
                    OutlinedTextField(
                        value = sku, onValueChange = { sku = it },
                        label = { Text("SKU") },
                        placeholder = { Text("e.g. SKU-1001") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("Product Title") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text("Description (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uom, onValueChange = { uom = it },
                        label = { Text("Unit of Measure") },
                        placeholder = { Text("pcs, kg, m, etc.") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onCreateProduct(sku, title, description, uom); showAddDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel", color = MediumGray) }
            }
        )
    }

    // ── Edit Product dialog ────────────────────────────────────
    editingProduct?.let { editing ->
        var title by remember { mutableStateOf(editing.title) }
        var description by remember { mutableStateOf(editing.description) }
        var uom by remember { mutableStateOf(editing.unitOfMeasure) }

        AlertDialog(
            onDismissRequest = { editingProduct = null },
            title = { Text("Edit Product", fontWeight = FontWeight.SemiBold, color = DarkText) },
            text = {
                Column {
                    // SKU is immutable — shown here as context only.
                    Text(
                        text = "SKU: ${editing.sku}",
                        fontSize = 12.sp, color = MediumGray,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("Product Title") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text("Description (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uom, onValueChange = { uom = it },
                        label = { Text("Unit of Measure") },
                        placeholder = { Text("pcs, kg, m, etc.") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onUpdateProduct(editing.id, title, description, uom); editingProduct = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingProduct = null }) { Text("Cancel", color = MediumGray) }
            }
        )
    }

    // ── Delete Product confirmation ────────────────────────────
    deletingProduct?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingProduct = null },
            title = { Text("Delete product?", fontWeight = FontWeight.SemiBold, color = DarkText) },
            text = {
                Text(
                    "\"${target.title}\" (SKU: ${target.sku}) will be permanently deleted. " +
                            "This cannot be undone. If any inventory is still tagged to this SKU, the delete will be blocked."
                )
            },
            confirmButton = {
                Button(
                    onClick = { onDeleteProduct(target); deletingProduct = null },
                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingProduct = null }) { Text("Cancel", color = MediumGray) }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightGray)
        ) {
            AppHeader(
                title = "Product Catalog",
                showBackButton = true,
                onBackClick = onBackClick,
                showMenu = false
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${state.products.size} product${if (state.products.size != 1) "s" else ""}",
                    fontSize = 12.sp, color = MediumGray
                )
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("+ Add Product", fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                }
            }

            if (state.products.isEmpty() && !state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "📦", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = "No products yet", fontSize = 15.sp, color = MediumGray, fontWeight = FontWeight.Medium)
                        Text(text = "Tap Add Product to create your first SKU", fontSize = 12.sp, color = MediumGray)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.products.forEach { product ->
                        ProductRow(
                            product = product,
                            onEdit = { editingProduct = product },
                            onDelete = { deletingProduct = product }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Primary)
            }
        }

        if (state.message != null) {
            LaunchedEffect(state.message) {
                kotlinx.coroutines.delay(3000)
                onDismissMessage()
            }
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = { TextButton(onClick = onDismissMessage) { Text("OK", color = Color.White) } },
                containerColor = DarkText
            ) {
                Text(state.message, color = Color.White)
            }
        }
    }
}

@Composable
private fun ProductRow(
    product: Product,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = product.sku, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primary)
                    Text(
                        text = product.unitOfMeasure, fontSize = 10.sp, color = MediumGray,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                Text(text = product.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkText)
                if (product.description.isNotBlank()) {
                    Text(text = product.description, fontSize = 11.sp, color = MediumGray)
                }
            }
            RowOverflowMenu(onEdit = onEdit, onDelete = onDelete)
        }
    }
}

/**
 * The three-dot (⋮) menu shown at the right of every product row.
 */
@Composable
private fun RowOverflowMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Options",
                tint = MediumGray
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Edit", color = DarkText) },
                onClick = { expanded = false; onEdit() }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = DestructiveRed) },
                onClick = { expanded = false; onDelete() }
            )
        }
    }
}