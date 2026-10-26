package com.snainfotech.tagscout.ui.screens.products

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.snainfotech.tagscout.data.wms.Brand
import com.snainfotech.tagscout.data.wms.CatalogLookupsRepository
import com.snainfotech.tagscout.data.wms.Category
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.theme.BorderGray
import com.snainfotech.tagscout.ui.theme.CardBg
import com.snainfotech.tagscout.ui.theme.DarkText
import com.snainfotech.tagscout.ui.theme.ErrorRed
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray
import com.snainfotech.tagscout.ui.theme.Primary

/** What dialog, if any, is open. */
private sealed interface LookupDialog {
    data class EnterName(
        val title: String,
        val initial: String,
        val confirmLabel: String,
        val onConfirm: (String) -> Unit
    ) : LookupDialog

    data class ConfirmDelete(
        val title: String,
        val body: String,
        val onConfirm: () -> Unit
    ) : LookupDialog
}

/** Manage categories (per product type) and brands. */
@Composable
fun CatalogLookupsScreen(
    viewModel: CatalogLookupsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<LookupDialog?>(null) }

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
            title = "Categories & brands",
            showBackButton = true,
            onBackClick = onBack,
            showMenu = false
        )

        val tabs = LookupsTab.entries
        TabRow(
            selectedTabIndex = tabs.indexOf(state.tab),
            containerColor = CardBg,
            contentColor = Primary,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(positions[tabs.indexOf(state.tab)]),
                    color = Primary
                )
            }
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = state.tab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = {
                        Text(
                            text = if (tab == LookupsTab.CATEGORIES) "Categories" else "Brands",
                            fontWeight = if (state.tab == tab) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (state.tab == tab) Primary else MediumGray
                        )
                    }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
                state.tab == LookupsTab.CATEGORIES -> CategoriesTab(state, viewModel) { dialog = it }
                else -> BrandsTab(state, viewModel) { dialog = it }
            }

            FloatingActionButton(
                onClick = {
                    dialog = if (state.tab == LookupsTab.CATEGORIES) {
                        val typeLabel = state.config.typeDef(state.selectedType).label
                        LookupDialog.EnterName("New $typeLabel category", "", "Add") { viewModel.addCategory(it) }
                    } else {
                        LookupDialog.EnterName("New brand", "", "Add") { viewModel.addBrand(it) }
                    }
                },
                containerColor = Primary,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            ) {
                Text("+", fontSize = 26.sp, fontWeight = FontWeight.Light)
            }

            if (state.busy) {
                CircularProgressIndicator(
                    color = Primary,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                )
            }
        }
    }

    when (val d = dialog) {
        is LookupDialog.EnterName -> NameDialog(
            spec = d,
            onDismiss = { dialog = null }
        )
        is LookupDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(d.title, fontWeight = FontWeight.SemiBold) },
            text = { Text(d.body, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { d.onConfirm(); dialog = null }) { Text("Delete", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("Cancel", color = MediumGray) }
            }
        )
        null -> Unit
    }
}

// ── Categories tab ────────────────────────────────────────────────

@Composable
private fun CategoriesTab(
    state: CatalogLookupsState,
    vm: CatalogLookupsViewModel,
    openDialog: (LookupDialog) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.typeOptions.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                items(state.typeOptions, key = { it.key.key }) { def ->
                    FilterChip(
                        selected = state.selectedType == def.key,
                        onClick = { vm.selectType(def.key) },
                        label = { Text(def.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary,
                            selectedLabelColor = Color.White,
                            containerColor = CardBg
                        )
                    )
                }
            }
        }

        val tree = state.tree
        if (tree.isEmpty()) {
            EmptyState("No categories for ${state.config.typeDef(state.selectedType).label} yet.\nTap + to add one.")
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(tree, key = { it.category.id }) { node ->
                ListCard {
                    CategoryRow(node.category, isSub = false, vm = vm, openDialog = openDialog)
                    node.children.forEach { child ->
                        HorizontalDivider(color = BorderGray)
                        CategoryRow(child, isSub = true, vm = vm, openDialog = openDialog)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    isSub: Boolean,
    vm: CatalogLookupsViewModel,
    openDialog: (LookupDialog) -> Unit
) {
    val actions = buildList {
        if (!isSub) add("Add sub-category" to {
            openDialog(LookupDialog.EnterName("New sub-category in ${category.name}", "", "Add") {
                vm.addCategory(it, parentId = category.id)
            })
        })
        add("Rename" to {
            openDialog(LookupDialog.EnterName("Rename category", category.name, "Save") {
                vm.renameCategory(category, it)
            })
        })
        add((if (category.isActive) "Hide" else "Show") to { vm.toggleCategoryActive(category) })
        add("Delete" to {
            openDialog(LookupDialog.ConfirmDelete(
                title = "Delete ${category.name}?",
                body = "Only possible if no products use it and it has no sub-categories. Otherwise, hide it instead."
            ) { vm.deleteCategory(category) })
        })
    }
    ItemRow(
        name = category.name,
        active = category.isActive,
        indent = isSub,
        bold = !isSub,
        actions = actions
    )
}

// ── Brands tab ────────────────────────────────────────────────────

@Composable
private fun BrandsTab(
    state: CatalogLookupsState,
    vm: CatalogLookupsViewModel,
    openDialog: (LookupDialog) -> Unit
) {
    if (state.brands.isEmpty()) {
        EmptyState("No brands yet.\nTap + to add one, or type a new brand on a product.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(state.brands, key = { it.id }) { brand ->
            ListCard {
                BrandRow(brand, vm, openDialog)
            }
        }
    }
}

@Composable
private fun BrandRow(brand: Brand, vm: CatalogLookupsViewModel, openDialog: (LookupDialog) -> Unit) {
    val actions = listOf(
        "Rename" to {
            openDialog(LookupDialog.EnterName("Rename brand", brand.name, "Save") { vm.renameBrand(brand, it) })
        },
        (if (brand.isActive) "Hide" else "Show") to { vm.toggleBrandActive(brand) },
        "Delete" to {
            openDialog(LookupDialog.ConfirmDelete(
                title = "Delete ${brand.name}?",
                body = "Only possible if no products use it. Otherwise, hide it instead."
            ) { vm.deleteBrand(brand) })
        }
    )
    ItemRow(name = brand.name, active = brand.isActive, indent = false, bold = false, actions = actions)
}

// ── Shared pieces ─────────────────────────────────────────────────

@Composable
private fun ListCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = BorderStroke(1.dp, BorderGray),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column { content() }
    }
}

/** One name with a ⋮ menu. Hidden items are greyed and badged. */
@Composable
private fun ItemRow(
    name: String,
    active: Boolean,
    indent: Boolean,
    bold: Boolean,
    actions: List<Pair<String, () -> Unit>>
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 32.dp else 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
    ) {
        if (indent) {
            Text("└", fontSize = 13.sp, color = MediumGray)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = name,
            fontSize = 14.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) DarkText else MediumGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (!active) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(BorderGray, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("Hidden", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MediumGray)
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Text("⋮", fontSize = 20.sp, color = MediumGray)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                actions.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label, color = if (label == "Delete") ErrorRed else DarkText) },
                        onClick = {
                            menuOpen = false
                            action()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NameDialog(spec: LookupDialog.EnterName, onDismiss: () -> Unit) {
    var text by remember(spec) { mutableStateOf(spec.initial) }
    val valid = text.isNotBlank() && text.trim().length <= CatalogLookupsRepository.MAX_NAME_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.title, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= CatalogLookupsRepository.MAX_NAME_LENGTH) text = it },
                singleLine = true,
                label = { Text("Name") },
                supportingText = { Text("${text.length}/${CatalogLookupsRepository.MAX_NAME_LENGTH}") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    focusedLabelColor = Primary,
                    cursorColor = Primary
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { spec.onConfirm(text); onDismiss() },
                enabled = valid
            ) { Text(spec.confirmLabel, color = if (valid) Primary else MediumGray) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MediumGray) }
        }
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MediumGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp)
        )
    }
}