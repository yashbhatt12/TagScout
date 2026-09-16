package com.snainfotech.tagscout.ui.screens.wms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import com.snainfotech.tagscout.data.wms.InventorySkuAggregate
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
fun WmsDashboardScreen(
    state: WmsDashboardState,
    onBackClick: () -> Unit,
    onSwitchView: (DashboardView) -> Unit,
    onSelectWarehouse: (Warehouse) -> Unit,
    onSelectRack: (Rack) -> Unit,
    onSelectBin: (Bin) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Track which product's detail dialog is currently open (by product ID).
    var expandedProductId by remember { mutableStateOf<String?>(null) }

    val expandedAggregate = state.aggregates.firstOrNull { it.productId == expandedProductId }
    if (expandedAggregate != null) {
        ProductLocationsDialog(
            aggregate = expandedAggregate,
            onDismiss = { expandedProductId = null }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightGray)
        ) {
            AppHeader(
                title = "Inventory Dashboard",
                showBackButton = true,
                onBackClick = onBackClick,
                showMenu = false
            )

            // ── Summary strip ─────────────
            SummaryStrip(
                skuCount = state.totalSkuCount,
                unitCount = state.totalUnitCount
            )

            // ── Segmented toggle ──────────
            ViewToggle(
                current = state.currentView,
                onSelect = onSwitchView
            )

            // ── Content ───────────────────
            when (state.currentView) {
                DashboardView.BY_PRODUCT -> ByProductView(
                    state = state,
                    onExpandProduct = { productId -> expandedProductId = productId }
                )
                DashboardView.BY_LOCATION -> ByLocationView(
                    state = state,
                    onSelectWarehouse = onSelectWarehouse,
                    onSelectRack = onSelectRack,
                    onSelectBin = onSelectBin
                )
            }
        }

        // Loading overlay
        if (state.isLoading && state.aggregates.isEmpty() && state.unitsInSelectedBin.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Primary)
            }
        }

        // Snackbar for errors/messages
        if (state.message != null) {
            LaunchedEffect(state.message) {
                kotlinx.coroutines.delay(3500)
                onDismissMessage()
            }
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = onDismissMessage) { Text("OK", color = Color.White) } },
                containerColor = DarkText
            ) {
                Text(state.message, color = Color.White)
            }
        }
    }
}

// ── Summary strip ─────────────────────────────────────────

@Composable
private fun SummaryStrip(skuCount: Int, unitCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatBlock(label = "Active SKUs", value = skuCount.toString(), color = Primary)
        StatBlock(label = "Total Units", value = unitCount.toString(), color = Amber)
    }
}

@Composable
private fun StatBlock(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, fontSize = 10.sp, color = MediumGray)
    }
}

// ── Segmented toggle ──────────────────────────────────────

@Composable
private fun ViewToggle(current: DashboardView, onSelect: (DashboardView) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .padding(bottom = 8.dp)
    ) {
        ToggleTab(
            label = "By Product",
            selected = current == DashboardView.BY_PRODUCT,
            onClick = { onSelect(DashboardView.BY_PRODUCT) },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        ToggleTab(
            label = "By Location",
            selected = current == DashboardView.BY_LOCATION,
            onClick = { onSelect(DashboardView.BY_LOCATION) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ToggleTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val bg = if (selected) Primary else Color.Transparent
    val fg = if (selected) Color.White else Primary
    val border = if (selected) Primary else BorderGray

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── By-Product view ───────────────────────────────────────

@Composable
private fun ByProductView(
    state: WmsDashboardState,
    onExpandProduct: (String) -> Unit
) {
    if (state.aggregates.isEmpty() && !state.isLoading) {
        EmptyState(
            emoji = "📦",
            title = "No inventory yet",
            hint = "Complete an Inward (GRN) to see products here."
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.aggregates.forEach { agg ->
            ProductAggregateCard(
                aggregate = agg,
                onClick = { onExpandProduct(agg.productId) }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ProductAggregateCard(
    aggregate: InventorySkuAggregate,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = aggregate.sku,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primary
                )
                Text(
                    text = aggregate.productTitle.ifBlank { "(untitled product)" },
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkText
                )
                Text(
                    text = "Across ${aggregate.byLocation.size} bin${if (aggregate.byLocation.size != 1) "s" else ""}",
                    fontSize = 11.sp, color = MediumGray,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = aggregate.totalQuantity.toString(),
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Amber
                )
                Text(text = "units", fontSize = 10.sp, color = MediumGray)
            }
        }
    }
}

@Composable
private fun ProductLocationsDialog(
    aggregate: InventorySkuAggregate,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = aggregate.sku,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primary
                )
                Text(
                    text = aggregate.productTitle.ifBlank { "(untitled)" },
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DarkText
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Total: ${aggregate.totalQuantity} unit${if (aggregate.totalQuantity != 1) "s" else ""}",
                    fontSize = 13.sp, color = MediumGray,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider(color = BorderGray)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "By Location",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MediumGray,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                aggregate.byLocation.entries
                    .sortedByDescending { it.value }
                    .forEach { (locationKey, qty) ->
                        // locationKey is "warehouseId/binCode" — split for display
                        val binCode = locationKey.substringAfter("/", locationKey)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = binCode, fontSize = 13.sp, color = DarkText)
                            Text(
                                text = qty.toString(),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Amber
                            )
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Primary)
            }
        }
    )
}

// ── By-Location view ──────────────────────────────────────

@Composable
private fun ByLocationView(
    state: WmsDashboardState,
    onSelectWarehouse: (Warehouse) -> Unit,
    onSelectRack: (Rack) -> Unit,
    onSelectBin: (Bin) -> Unit
) {
    if (state.warehouses.isEmpty()) {
        EmptyState(
            emoji = "🏢",
            title = "No warehouse set up yet",
            hint = "Create a warehouse and bins under WMS → Warehouse Setup first."
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Warehouse picker (only visible if there's more than one)
        if (state.warehouses.size > 1) {
            HorizontalChipPicker(
                label = "Warehouse",
                items = state.warehouses.map { it.name },
                selectedIndex = state.warehouses.indexOfFirst { it.id == state.selectedWarehouse?.id },
                onSelect = { i -> onSelectWarehouse(state.warehouses[i]) }
            )
        }

        // Rack picker
        if (state.racks.isNotEmpty()) {
            HorizontalChipPicker(
                label = "Rack",
                items = state.racks.map { it.name },
                selectedIndex = state.racks.indexOfFirst { it.id == state.selectedRack?.id },
                onSelect = { i -> onSelectRack(state.racks[i]) }
            )
        }

        // Bin picker
        if (state.bins.isNotEmpty()) {
            HorizontalChipPicker(
                label = "Bin",
                items = state.bins.map { it.binCode },
                selectedIndex = state.bins.indexOfFirst { it.id == state.selectedBin?.id },
                onSelect = { i -> onSelectBin(state.bins[i]) }
            )
        }

        HorizontalDivider(color = BorderGray, modifier = Modifier.padding(vertical = 4.dp))

        // Contents of the selected bin
        val bin = state.selectedBin
        if (bin == null) {
            EmptyHint("Pick a bin above to see what's inside.")
        } else {
            Text(
                text = "Contents of ${bin.binCode}",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkText,
                modifier = Modifier.padding(top = 4.dp)
            )

            val grouped = state.unitsInBinGroupedBySku
            if (grouped.isEmpty()) {
                EmptyHint("This bin is empty.")
            } else {
                Text(
                    text = "${state.unitsInSelectedBin.size} unit${if (state.unitsInSelectedBin.size != 1) "s" else ""} across ${grouped.size} SKU${if (grouped.size != 1) "s" else ""}",
                    fontSize = 11.sp, color = MediumGray,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                grouped.forEach { (sku, count) ->
                    BinContentRow(sku = sku, count = count)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun HorizontalChipPicker(
    label: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column {
        Text(
            text = label,
            fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MediumGray,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEachIndexed { i, text ->
                val selected = i == selectedIndex
                val bg = if (selected) Amber else Color.White
                val fg = if (selected) Color.White else DarkText
                val border = if (selected) Amber else BorderGray
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(16.dp))
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = text, fontSize = 12.sp, color = fg, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun BinContentRow(sku: String, count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = sku,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "× $count",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Amber
            )
        }
    }
}

// ── Reusable ──────────────────────────────────────────────

@Composable
private fun EmptyState(emoji: String, title: String, hint: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = emoji, fontSize = 48.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = DarkText)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hint, fontSize = 12.sp, color = MediumGray,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text, fontSize = 12.sp, color = MediumGray,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}