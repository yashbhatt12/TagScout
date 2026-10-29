package com.snainfotech.tagscout.ui.screens.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snainfotech.tagscout.R
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.Feature
import com.snainfotech.tagscout.data.config.LabelKey
import com.snainfotech.tagscout.ui.components.AppHeader
import com.snainfotech.tagscout.ui.components.FeatureButton
import com.snainfotech.tagscout.ui.theme.LightGray
import com.snainfotech.tagscout.ui.theme.MediumGray

/**
 * Company Set Up — the one place to set up a company, whatever its business
 * type: locations (warehouses / stores / sites), products, and categories &
 * brands. Opened from Home and never feature-gated, so a retail-only or
 * asset-only company doesn't need Warehouse Management switched on.
 *
 * Location names follow the company's labels (a jeweller sees Store /
 * Counter / Tray). Catalog names are the same for every company.
 */
@Composable
fun CompanySetupScreen(
    onBackClick: () -> Unit,
    onLocationsClick: () -> Unit,
    onProductsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onCatalogExcelClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Live company config. Previews can't start Firebase, so they use defaults.
    val config: CompanyConfig = if (LocalInspectionMode.current) {
        CompanyConfig.DEFAULT
    } else {
        ConfigRepository.config.collectAsState().value
    }
    val labels = config.labels
    val warehouse = labels.singular(LabelKey.WAREHOUSE)
    val warehouses = labels.plural(LabelKey.WAREHOUSE).lowercase()
    val racks = labels.plural(LabelKey.RACK).lowercase()
    val bins = labels.plural(LabelKey.BIN).lowercase()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LightGray)
    ) {
        AppHeader(
            title = "Company Set Up",
            showBackButton = true,
            onBackClick = onBackClick,
            showMenu = false
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionLabel("LOCATIONS")
            FeatureButton(
                icon = R.drawable.ic_config,
                title = "$warehouse setup",
                description = "Create $warehouses, $racks and $bins",
                enabled = true,
                onClick = onLocationsClick
            )

            Spacer(modifier = Modifier.height(4.dp))

            SectionLabel("CATALOG")
            FeatureButton(
                icon = R.drawable.ic_inventory,
                title = "Product Catalog",
                description = "Add, edit and search products",
                enabled = true,
                onClick = onProductsClick
            )
            FeatureButton(
                icon = R.drawable.ic_inventory,
                title = "Categories & brands",
                description = "Categories for each product type, and brands",
                enabled = true,
                onClick = onCategoriesClick
            )

            if (config.isEnabled(Feature.CATALOG_EXCEL)) {
                FeatureButton(
                    icon = R.drawable.ic_inventory,
                    title = "Bulk Catalog Creation",
                    description = "Import, export and sample Excel file",
                    enabled = true,
                    onClick = onCatalogExcelClick
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MediumGray,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
    )
}

@Preview(showBackground = true, heightDp = 700)
@Composable
fun CompanySetupScreenPreview() {
    CompanySetupScreen(
        onBackClick = {},
        onLocationsClick = {},
        onProductsClick = {},
        onCategoriesClick = {}
    )
}
