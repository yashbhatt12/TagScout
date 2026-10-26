package com.snainfotech.tagscout

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.snainfotech.tagscout.data.config.CompanyConfig
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.ConfigSource
import com.snainfotech.tagscout.data.config.Feature

/**
 * Which feature each route belongs to. Routes not listed here (auth, Home,
 * Connect Device, Device Config, About) are always allowed.
 *
 * Add every new feature route here when it's created — the guard only
 * protects what it knows about.
 */
object FeatureRoutes {

    fun isAllowed(route: String?, config: CompanyConfig): Boolean {
        // Strip any nav arguments: "wms_inward/{grnId}" or "x?y=z" → base route
        val base = route?.substringBefore('/')?.substringBefore('?') ?: return true
        return when (base) {
            Routes.TAG_OPERATIONS -> config.anyTagOperationEnabled()
            Routes.QUICK_SCAN -> config.isEnabled(Feature.QUICK_SCAN)
            Routes.INVENTORY -> config.isEnabled(Feature.INVENTORY_SCAN)
            Routes.ORDER_PICKING, Routes.PICK_ORDER -> config.isEnabled(Feature.ORDER_PICKING)
            Routes.WRITE_TAG -> config.isEnabled(Feature.WRITE_TAG)
            Routes.KILL_TAG -> config.isEnabled(Feature.KILL_TAG)
            Routes.LOCATE_TAG -> config.isEnabled(Feature.LOCATE_TAG)
            Routes.SHOP, Routes.CART -> config.isEnabled(Feature.SHOP)

            Routes.WMS_MENU,
            Routes.WMS_WAREHOUSE_SETUP,
            Routes.WMS_DASHBOARD,
            Routes.WMS_CYCLE_COUNT,
            Routes.WMS_INWARD -> config.isEnabled(Feature.WMS)

            // Products are shared: any module that manages stock can open them.
            Routes.PRODUCTS,
            Routes.PRODUCT_NEW,
            Routes.PRODUCT_EDIT,
            Routes.PRODUCT_LOOKUPS,
            Routes.WMS_PRODUCT_CATALOG -> canManageProducts(config)

            Routes.JEWELLERY_MENU,
            Routes.JEWELLERY_ENROLL,
            Routes.JEWELLERY_CHECKOUT -> config.isEnabled(Feature.RETAIL)

            // Lives inside the Retail menu, so it needs both flags.
            Routes.MANAGER_ALERTS ->
                config.isEnabled(Feature.RETAIL) && config.isEnabled(Feature.MANAGER_ALERTS)

            else -> true
        }
    }

    /** Products open from WMS and from Retail, so either flag is enough. */
    fun canManageProducts(config: CompanyConfig): Boolean =
        config.isEnabled(Feature.WMS) || config.isEnabled(Feature.RETAIL)
}

/**
 * Place once, next to the NavHost. Whenever the current route or the
 * company config changes, a disallowed screen is closed back to Home.
 *
 * Defaults (no settings doc yet / not loaded) allow everything, so the
 * guard never fires before real settings arrive — no flicker on cold start.
 */
@Composable
fun FeatureNavGuard(navController: NavHostController) {
    val config by ConfigRepository.config.collectAsState()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val context = LocalContext.current

    LaunchedEffect(route, config) {
        if (config.source == ConfigSource.DEFAULTS) return@LaunchedEffect
        if (FeatureRoutes.isAllowed(route, config)) return@LaunchedEffect

        Toast.makeText(context, "This feature isn't part of your plan", Toast.LENGTH_SHORT).show()

        // Prefer popping to the existing Home entry; fall back to navigating there.
        val popped = navController.popBackStack(Routes.HOME, inclusive = false)
        if (!popped) {
            navController.navigate(Routes.HOME) {
                popUpTo(0)
                launchSingleTop = true
            }
        }
    }
}