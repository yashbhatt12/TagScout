package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp
import com.snainfotech.tagscout.data.config.ProductTypeKey

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
 * A product (SKU) in the catalog — written once, shared by every unit of it.
 * Stored at /companies/{companyId}/products/{productId}
 *
 * Every product has one [productType]; the type decides which attribute
 * groups and custom fields apply (settings/config → productTypes).
 * Type-specific values live in [customFields], keyed by field definition key.
 *
 * Field notes:
 *   - [title] is the product's full name (kept as "title" — existing data uses it).
 *   - [skuKey] = sku trimmed + lowercased; queried for case-insensitive
 *     uniqueness so a create never has to read the whole catalog.
 *   - [categoryId], [subCategoryId], [brandId] point at docs in the company's
 *     categories / brands collections, so renames never touch products.
 *   - [unitOfMeasure] holds a UnitOfMeasure.key; older products hold free
 *     text ("pcs") which [uom] still reads correctly.
 *   - [status] replaces nothing: "discontinued" blocks new enrolments but
 *     keeps existing units valid. [isActive] stays as the legacy flag that
 *     the catalog list already filters on.
 *
 * Every field has a default, so documents written before this model read
 * cleanly with no migration.
 */
data class Product(
    val id: String = "",
    val sku: String = "",               // unique within the company, e.g. "SKU-1001"
    val skuKey: String = "",            // sku.trim().lowercase(), for uniqueness queries
    val title: String = "",             // full name, e.g. "Widget Model X"
    val shortName: String = "",         // optional, max SHORT_NAME_MAX chars, for small labels
    val productType: String = ProductTypeKey.GENERAL.key,
    val categoryId: String = "",
    val subCategoryId: String = "",
    val brandId: String = "",
    val description: String = "",
    val barcode: String = "",           // one barcode / QR value per product for now
    val unitOfMeasure: String = UnitOfMeasure.DEFAULT.key,
    val imageUrl: String = "",          // Firebase Storage download URL
    val status: String = ProductStatus.ACTIVE.key,
    val trackingMode: String = "unit",  // "unit" for now, "sku" later
    val isActive: Boolean = true,       // legacy soft-delete flag
    val customFields: Map<String, Any?> = emptyMap(),
    val createdAt: Timestamp? = null,
    val createdBy: String = "",
    val updatedAt: Timestamp? = null,
    val updatedBy: String = ""
) {
    companion object {
        const val SHORT_NAME_MAX = 20

        /** The normalised form stored in [skuKey]. */
        fun skuKeyOf(sku: String): String = sku.trim().lowercase()
    }
}

/** Typed view of [Product.productType]; unknown or missing → GENERAL. */
fun Product.type(): ProductTypeKey =
    ProductTypeKey.fromKey(productType) ?: ProductTypeKey.GENERAL

/** Typed view of [Product.unitOfMeasure]; legacy free text maps leniently. */
fun Product.uom(): UnitOfMeasure = UnitOfMeasure.parseOrDefault(unitOfMeasure)

/** Typed view of [Product.status]; unknown or missing → ACTIVE. */
fun Product.productStatus(): ProductStatus = ProductStatus.fromKey(status) ?: ProductStatus.ACTIVE

/** [key] is the value stored in products.status — never rename. */
enum class ProductStatus(val key: String, val displayName: String) {
    ACTIVE("active", "Active"),
    DISCONTINUED("discontinued", "Discontinued");

    companion object {
        fun fromKey(key: String?): ProductStatus? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

/**
 * A single physical unit — one RFID tag.
 * Stored at /companies/{companyId}/inventory_units/{epc}
 *
 * EPC is the document ID: lookups by EPC (during scanning) are a single
 * `get()`, and Firestore enforces EPC uniqueness for free.
 *
 * Re-tagging (damaged or replaced tag): the unit is copied to a new doc
 * under the new EPC with the old EPC appended to [previousEpcs]; the old
 * doc is set to status "retagged" with [replacedBy] = new EPC. History
 * (movements, audits) stays on the old EPC and is reachable both ways.
 *
 * status values (UnitStatus keys; each product type shows only its own set):
 *   in_stock, picked, dispatched, assigned, sold, in_repair, returned,
 *   expired (system, nightly from in_stock), lost, disposed,
 *   retagged (system, old doc after a re-tag)
 *
 * Field groups:
 *   core          — epc, tid, productId/sku, location, status, timestamps
 *   customFields  — unit-level values defined in settings/config, by key
 *   batch tracking— batchId + denormalised batchNo/mfgDate/expiryDate
 *                   (dates copied so FEFO and expiry queries need no join)
 *   nesting       — unitKind, parentEpc, childCount
 *   packs         — quantity (pieces under this one tag; 1 by default)
 *   assignment    — assignedTo / assignedAt (who holds an asset now)
 *   retail sale   — price (informational tag price), itemCode, soldAt/soldBy
 *
 * Every field has a default, so documents written before this model —
 * including jewellery pilot units with no productId — read cleanly.
 */
data class InventoryUnit(
    // ── Core ──
    val epc: String = "",               // the RFID tag EPC (also the document ID)
    val tid: String = "",               // tag TID, captured at encoding; helps spot cloned tags
    val productId: String = "",
    val sku: String = "",               // denormalised from the product
    val serialNumber: String = "",      // optional physical serial
    val currentWarehouseId: String = "",
    val currentRackId: String = "",
    val currentBinId: String = "",
    val currentBinCode: String = "",    // denormalised for fast display
    val status: String = "in_stock",
    val inwardedAt: Timestamp? = null,
    val lastMovedAt: Timestamp? = null,
    val dispatchedAt: Timestamp? = null,
    val enrolledAt: Timestamp? = null,
    val enrolledBy: String = "",        // userId, or "import" for Excel-imported units
    val lastSeenAt: Timestamp? = null,  // written when a stock take / cycle count is saved, not on every read
    val lastSeenBy: String = "",

    // ── Re-tagging ──
    val previousEpcs: List<String> = emptyList(),
    val replacedBy: String = "",        // set on the OLD doc: the EPC that replaced it

    // ── Custom fields (unit level) ──
    val customFields: Map<String, Any?> = emptyMap(),

    // ── Batch tracking ──
    val batchId: String = "",
    val batchNo: String = "",
    val mfgDate: String = "",           // "YYYY-MM-DD"
    val expiryDate: String = "",        // "YYYY-MM-DD"; sorts correctly as text

    // ── Nesting ──
    val unitKind: String = UnitKind.ITEM.key,
    val parentEpc: String = "",         // EPC of the carton/pallet this unit sits in; empty = loose
    val childCount: Int = 0,            // containers only

    // ── Packs ──
    val quantity: Int = 1,              // pieces represented by this tag (Product.unitsPerTag at inward)

    // ── Assignment ──
    val assignedTo: String = "",
    val assignedAt: Timestamp? = null,

    // ── Retail sale (existing jewellery fields; nullable so old docs are unchanged) ──
    val category: String? = null,       // legacy marker: "jewellery" on pilot units
    val itemCode: String? = null,       // customer's internal item code, e.g. "DR-2451"
    val price: Double? = null,          // informational tag price in INR, entered at enrolment / update
    val soldAt: Timestamp? = null,
    val soldBy: String? = null
)

/** Typed view of [InventoryUnit.status]; unknown or missing → IN_STOCK. */
fun InventoryUnit.unitStatus(): com.snainfotech.tagscout.data.config.UnitStatus =
    com.snainfotech.tagscout.data.config.UnitStatus.fromKey(status)
        ?: com.snainfotech.tagscout.data.config.UnitStatus.IN_STOCK

/** Typed view of [InventoryUnit.unitKind]; unknown or missing → ITEM. */
fun InventoryUnit.kind(): UnitKind = UnitKind.fromKey(unitKind) ?: UnitKind.ITEM

/** Cartons and pallets are packaging, never stock: they carry no productId. */
fun InventoryUnit.isContainer(): Boolean = kind() != UnitKind.ITEM

/** [key] is the value stored in inventory_units.unitKind — never rename. */
enum class UnitKind(val key: String, val displayName: String) {
    ITEM("item", "Item"),
    CARTON("carton", "Carton"),
    PALLET("pallet", "Pallet");

    companion object {
        fun fromKey(key: String?): UnitKind? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

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
    val notes: String = "",
    val batchId: String = "",           // batch tracking: a recall traces every movement of a batch
    val parentEpc: String = ""          // nesting: set on child movements generated by a carton move
)