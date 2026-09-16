package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp

/**
 * SKU-level aggregate as maintained by the updateSkuAggregate Cloud Function.
 *
 * Stored at /companies/{companyId}/inventory_sku/{productId}
 *
 * Never written by the Android app — always derived from the movements ledger.
 * The app reads from here for fast dashboard views without having to scan
 * through individual inventory_units records.
 *
 * byLocation keys are "warehouseId/binCode" strings mapping to a running count
 * of units in that bin. Zero-count entries are removed by the function.
 */
data class InventorySkuAggregate(
    val productId: String = "",
    val sku: String = "",
    val totalQuantity: Int = 0,
    val byLocation: Map<String, Int> = emptyMap(),
    val lastComputedAt: Timestamp? = null,

    // These are enriched from the Product doc when displaying, not stored here
    val productTitle: String = ""
)