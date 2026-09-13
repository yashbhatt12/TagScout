package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp

/**
 * Warehouse Management System — data models.
 *
 * All entities live under /companies/{companyId}/... in Firestore.
 * For now companyId = userId (single-tenant). When Feature 1 (admin console)
 * lands, users will join a shared companyId — no schema migration needed.
 *
 * Design principles:
 *   - Plain strings, numbers, booleans, Timestamps only (web-SDK friendly)
 *   - EPCs are unique across the whole company (used as document IDs)
 *   - Movements ledger is append-only; SKU aggregates are derived by Cloud Function
 */

/**
 * Physical warehouse (a single-tenant company will typically have one).
 * Stored at /companies/{companyId}/warehouses/{warehouseId}
 */
data class Warehouse(
    val id: String = "",
    val name: String = "",              // e.g. "Mumbai Main"
    val address: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

/**
 * A rack inside a warehouse.
 * Stored at /companies/{companyId}/warehouses/{warehouseId}/racks/{rackId}
 */
data class Rack(
    val id: String = "",
    val name: String = "",              // e.g. "RACK-A"
    val warehouseId: String = "",
    val createdAt: Timestamp? = null
)

/**
 * A bin inside a rack — the smallest addressable location.
 * Stored at /companies/{companyId}/warehouses/{warehouseId}/racks/{rackId}/bins/{binId}
 *
 * A future barcode-labeled bin will have its barcode value = binCode, so scanning
 * a printed bin label immediately resolves to this bin.
 */
data class Bin(
    val id: String = "",
    val binCode: String = "",           // e.g. "BIN-A-03" — matches the printed barcode
    val name: String = "",              // display-friendly name if different
    val rackId: String = "",
    val warehouseId: String = "",
    val createdAt: Timestamp? = null
)

/**
 * A product (SKU) in the catalog.
 * Stored at /companies/{companyId}/products/{productId}
 *
 * trackingMode="unit" means every physical unit has its own RFID tag.
 * "sku" (future) would mean only quantity is tracked, no per-unit records.
 */
data class Product(
    val id: String = "",
    val sku: String = "",               // unique within the company, e.g. "SKU-1001"
    val title: String = "",             // e.g. "Widget Model X"
    val description: String = "",
    val unitOfMeasure: String = "pcs",  // "pcs", "kg", "meters", etc.
    val trackingMode: String = "unit",  // "unit" for now, "sku" later
    val isActive: Boolean = true,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

/**
 * A single physical unit — one RFID tag, one item.
 * Stored at /companies/{companyId}/inventory_units/{epc}
 *
 * EPC is used as the document ID so lookups by EPC (during scanning) are
 * a single `get()` with no filter. Also enforces uniqueness at the DB level.
 *
 * status values:
 *   "in_stock"    — sitting in a bin, available
 *   "picked"      — allocated to a pick list but not yet dispatched
 *   "dispatched"  — left the warehouse
 */
data class InventoryUnit(
    val epc: String = "",               // the RFID tag EPC (also the document ID)
    val productId: String = "",
    val sku: String = "",
    val serialNumber: String = "",      // optional physical serial
    val currentWarehouseId: String = "",
    val currentRackId: String = "",
    val currentBinId: String = "",
    val currentBinCode: String = "",    // denormalized for fast display
    val status: String = "in_stock",
    val inwardedAt: Timestamp? = null,
    val lastMovedAt: Timestamp? = null,
    val dispatchedAt: Timestamp? = null
)

/**
 * The append-only movement ledger — every inward, outward, relocation.
 * Stored at /companies/{companyId}/movements/{movementId}
 *
 * NEVER edited or deleted after creation. A Cloud Function listens to writes
 * here and updates /companies/{companyId}/inventory_sku/{productId} aggregates.
 *
 * type values:
 *   "inward"           — goods received (fromLocation empty, toLocation set)
 *   "outward"          — goods dispatched (fromLocation set, toLocation empty)
 *   "relocation"       — moved between bins (both set)
 *   "cyclecount_adjust"— stock take reconciliation
 *
 * referenceType values: "grn", "pick_list", "dispatch", "cyclecount", "manual"
 */
data class Movement(
    val id: String = "",
    val type: String = "",              // "inward" | "outward" | "relocation" | "cyclecount_adjust"
    val productId: String = "",
    val sku: String = "",
    val epc: String = "",
    val quantity: Int = 0,              // +1 for inward, -1 for outward, 0 for relocation
    val fromWarehouseId: String = "",
    val fromRackId: String = "",
    val fromBinId: String = "",
    val fromBinCode: String = "",
    val toWarehouseId: String = "",
    val toRackId: String = "",
    val toBinId: String = "",
    val toBinCode: String = "",
    val referenceType: String = "",     // "grn" | "pick_list" | "dispatch" | "cyclecount" | "manual"
    val referenceId: String = "",       // e.g. GRN number, pick list ID
    val userId: String = "",
    val timestamp: Timestamp? = null,
    val notes: String = ""
)