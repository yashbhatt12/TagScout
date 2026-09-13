package com.snainfotech.tagscout.ui.screens.wms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.data.wms.Bin
import com.snainfotech.tagscout.data.wms.Rack
import com.snainfotech.tagscout.data.wms.Warehouse
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.Amber
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary

@Composable
fun WarehouseSetupScreen(
    state: WarehouseSetupState,
    onBackClick: () -> Unit,
    onSelectWarehouse: (Warehouse) -> Unit,
    onSelectRack: (Rack) -> Unit,
    onCreateWarehouse: (String, String) -> Unit,
    onCreateRack: (String) -> Unit,
    onCreateBin: (String, String) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddWarehouseDialog by remember { mutableStateOf(false) }
    var showAddRackDialog by remember { mutableStateOf(false) }
    var showAddBinDialog by remember { mutableStateOf(false) }

    // Add Warehouse dialog
    if (showAddWarehouseDialog) {
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddWarehouseDialog = false },
            title = { Text("Add Warehouse", fontWeight = FontWeight.SemiBold, color = DarkText) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Warehouse Name") },
                        placeholder = { Text("e.g. Mumbai Main") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = address, onValueChange = { address = it },
                        label = { Text("Address (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onCreateWarehouse(name, address); showAddWarehouseDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddWarehouseDialog = false }) { Text("Cancel", color = MediumGray) }
            }
        )
    }

    // Add Rack dialog
    if (showAddRackDialog) {
        var rackName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddRackDialog = false },
            title = { Text("Add Rack", fontWeight = FontWeight.SemiBold, color = DarkText) },
            text = {
                Column {
                    Text(
                        text = "In: ${state.selectedWarehouse?.name ?: ""}",
                        fontSize = 12.sp, color = MediumGray,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = rackName, onValueChange = { rackName = it },
                        label = { Text("Rack Name") },
                        placeholder = { Text("e.g. RACK-A") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onCreateRack(rackName); showAddRackDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddRackDialog = false }) { Text("Cancel", color = MediumGray) }
            }
        )
    }

    // Add Bin dialog
    if (showAddBinDialog) {
        var binCode by remember { mutableStateOf("") }
        var binName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddBinDialog = false },
            title = { Text("Add Bin", fontWeight = FontWeight.SemiBold, color = DarkText) },
            text = {
                Column {
                    Text(
                        text = "In: ${state.selectedWarehouse?.name ?: ""} / ${state.selectedRack?.name ?: ""}",
                        fontSize = 12.sp, color = MediumGray,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = binCode, onValueChange = { binCode = it },
                        label = { Text("Bin Code (matches printed barcode)") },
                        placeholder = { Text("e.g. BIN-A-03") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = binName, onValueChange = { binName = it },
                        label = { Text("Display Name (optional)") },
                        placeholder = { Text("Defaults to Bin Code") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, cursorColor = Primary, focusedLabelColor = Primary)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onCreateBin(binCode, binName); showAddBinDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddBinDialog = false }) { Text("Cancel", color = MediumGray) }
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
                title = "Warehouse Setup",
                showBackButton = true,
                onBackClick = onBackClick,
                showMenu = false
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── WAREHOUSES SECTION ─────────────
                SectionCard(
                    title = "Warehouses",
                    count = state.warehouses.size,
                    onAdd = { showAddWarehouseDialog = true },
                    addLabel = "Add Warehouse"
                ) {
                    if (state.warehouses.isEmpty()) {
                        EmptyHint("No warehouses yet. Add one to get started.")
                    } else {
                        state.warehouses.forEach { wh ->
                            SelectableRow(
                                title = wh.name,
                                subtitle = wh.address.ifBlank { null },
                                selected = state.selectedWarehouse?.id == wh.id,
                                onClick = { onSelectWarehouse(wh) }
                            )
                        }
                    }
                }

                // ── RACKS SECTION ──────────────────
                if (state.selectedWarehouse != null) {
                    SectionCard(
                        title = "Racks in ${state.selectedWarehouse.name}",
                        count = state.racks.size,
                        onAdd = { showAddRackDialog = true },
                        addLabel = "Add Rack"
                    ) {
                        if (state.racks.isEmpty()) {
                            EmptyHint("No racks yet in this warehouse.")
                        } else {
                            state.racks.forEach { rack ->
                                SelectableRow(
                                    title = rack.name,
                                    subtitle = null,
                                    selected = state.selectedRack?.id == rack.id,
                                    onClick = { onSelectRack(rack) }
                                )
                            }
                        }
                    }
                }

                // ── BINS SECTION ───────────────────
                if (state.selectedRack != null) {
                    SectionCard(
                        title = "Bins in ${state.selectedRack.name}",
                        count = state.bins.size,
                        onAdd = { showAddBinDialog = true },
                        addLabel = "Add Bin"
                    ) {
                        if (state.bins.isEmpty()) {
                            EmptyHint("No bins yet in this rack.")
                        } else {
                            state.bins.forEach { bin ->
                                BinRow(bin)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
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
private fun SectionCard(
    title: String,
    count: Int,
    onAdd: () -> Unit,
    addLabel: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkText,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = count.toString(),
                    fontSize = 12.sp, color = MediumGray,
                    modifier = Modifier.padding(end = 8.dp)
                )
                OutlinedButton(
                    onClick = onAdd,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("+ $addLabel", fontSize = 11.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SelectableRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) Amber else BorderGray
    val bgColor = if (selected) Amber.copy(alpha = 0.08f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DarkText)
            if (!subtitle.isNullOrBlank()) {
                Text(text = subtitle, fontSize = 10.sp, color = MediumGray)
            }
        }
        if (selected) {
            Text(text = "SELECTED", fontSize = 9.sp, color = Amber, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BinRow(bin: Bin) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .border(1.dp, BorderGray, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = bin.binCode, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DarkText)
            if (bin.name.isNotBlank() && bin.name != bin.binCode) {
                Text(text = bin.name, fontSize = 10.sp, color = MediumGray)
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text, fontSize = 12.sp, color = MediumGray,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}