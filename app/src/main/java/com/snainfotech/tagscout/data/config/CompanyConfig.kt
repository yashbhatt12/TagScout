package com.snainfotech.tagscout.data.config

/**
 * Company-level configuration, read from:
 *   /companies/{companyId}/settings/config
 *
 * Lives under the COMPANY (not the user) so multi-user can be added later
 * without migration. Client is read-only; only admin (console / Admin SDK /
 * Cloud Functions) writes it.
 *
 * Model:
 *   - Every product has ONE product type (it_asset, non_it_asset, apparel,
 *     jewellery, food, general). A company enables the types it uses.
 *   - Attribute groups are reusable bundles of field definitions
 *     (e.g. "Pricing and tax"). A type lists the groups it switches on,
 *     plus its own type-specific fields.
 *   - A group may carry a capability (batch tracking, retail sale, ...),
 *     which switches on app LOGIC for products of that type. Groups without
 *     a capability are data only.
 *   - Field values live on the entity: products/{id}.customFields.{key} for
 *     level=product, inventory_units/{epc}.customFields.{key} for level=unit.
 *
 * Expected Firestore shape (every field optional — missing = default):
 *
 *   schemaVersion: 1
 *   plan:          "standard"
 *   template:      "jewellery"              // which preset seeded this company; informational
 *   features:      { quickScan: true, managerAlerts: false, ... }
 *   limits:        { maxDevices: 10 }
 *   labels:        { bin: "Tray" }  or  { bin: { singular: "Tray", plural: "Trays" } }
 *   attributeGroups: [
 *     { id: "pricingTax", label: "Pricing and tax", order: 1,
 *       fields: [ { key: "mrp", label: "MRP", type: "number", level: "product", unit: "INR" } ] },
 *     { id: "batch", label: "Batch tracking", capability: "batchTracking",
 *       fields: [ { key: "grnNo", label: "GRN number", type: "text", level: "unit" } ] }
 *   ]
 *   productTypes: {
 *     jewellery: { label: "Retail jewellery", enabled: true,
 *                  groups: ["pricingTax", "supplierOrigin", "retailSale"],
 *                  fields: [ { key: "grossWeight", label: "Gross weight", type: "number",
 *                              level: "unit", unit: "g", required: true } ],
 *                  required: { huid: true },                      // per-type overrides
 *                  statuses: ["in_stock", "sold", "returned", "in_repair", "lost"] }
 *   }
 *   capabilities: {
 *     batchTracking: { alertDays: 30, pickRule: "FEFO", blockExpiredDispatch: true, autoExpire: true },
 *     nesting:       { levels: ["carton", "pallet"] }
 *   }
 *
 * Parsing is deliberately forgiving: a bad value falls back to its default,
 * a malformed field or group is dropped on its own. A typo in the console
 * must never crash the app or blank the Home screen.
 */
data class CompanyConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val plan: String = DEFAULT_PLAN,
    val template: String = "",
    val features: Map<Feature, Boolean> = DEFAULT_FEATURES,
    val limits: Limits = Limits(),
    val labels: Labels = Labels(),
    val attributeGroups: Map<String, AttributeGroup> = emptyMap(),
    val productTypes: Map<ProductTypeKey, ProductTypeDef> = DEFAULT_PRODUCT_TYPES,
    val capabilityOptions: CapabilityOptions = CapabilityOptions(),
    val source: ConfigSource = ConfigSource.DEFAULTS
) {
    // ── Features ────────────────────────────────────────────────

    fun isEnabled(feature: Feature): Boolean =
        features[feature] ?: DEFAULT_FEATURES[feature] ?: false

    /** Tag Operations tile shows if any of its sub-screens is enabled. */
    fun anyTagOperationEnabled(): Boolean = Feature.TAG_OPERATIONS.any { isEnabled(it) }

    // ── Product types ───────────────────────────────────────────

    /** Types offered when creating a product, in fixed enum order. */
    val enabledTypes: List<ProductTypeDef>
        get() = ProductTypeKey.entries.mapNotNull { productTypes[it] }.filter { it.enabled }

    /**
     * Definition for a product's type. Products created before types existed
     * have no type and are treated as GENERAL. A disabled type still resolves,
     * so existing products of that type keep rendering.
     */
    fun typeDef(type: ProductTypeKey?): ProductTypeDef {
        val key = type ?: ProductTypeKey.GENERAL
        return productTypes[key] ?: ProductTypeDef(key = key, enabled = false)
    }

    /** Groups a type switches on, in the order the type lists them. Unknown ids are skipped. */
    fun groupsFor(type: ProductTypeKey?): List<AttributeGroup> =
        typeDef(type).groupIds.mapNotNull { attributeGroups[it] }

    fun hasCapability(type: ProductTypeKey?, capability: Capability): Boolean =
        groupsFor(type).any { it.capability == capability }

    /**
     * Active fields for one level of one type, in display order:
     * the type's own fields first (by order), then each group's fields
     * (groups in the type's order, fields by order). Per-type required
     * overrides are applied. A key appearing twice keeps its first definition.
     */
    fun fieldsFor(type: ProductTypeKey?, level: FieldLevel): List<FieldDef> {
        val def = typeDef(type)
        val own = def.fields.sortedBy { it.order }
        val fromGroups = groupsFor(type).flatMap { group -> group.fields.sortedBy { it.order } }
        return (own + fromGroups)
            .filter { it.active && it.level == level }
            .distinctBy { it.key.lowercase() }
            .map { field -> def.requiredOverrides[field.key]?.let { field.copy(required = it) } ?: field }
    }

    /** Statuses a unit of this type can be set to by a person (system-only ones excluded). */
    fun selectableStatuses(type: ProductTypeKey?): List<UnitStatus> =
        typeDef(type).statuses.filterNot { it.systemOnly }

    // ── Core field requirements ──────────────────────────────────────────

    /**
     * Whether a core (fixed-in-code) unit field is required for this type.
     * Read from the type's `required` map in settings, e.g.
     *   it_asset: { required: { serialNumber: true } }
     *   food:     { required: { batchNo: true, mfgDate: true, expiryDate: true } }
     *
     * Batch fields only count when the type has batch tracking, so a stray
     * `batchNo: true` on a type without it never blocks a save.
     * With no settings doc nothing core is required — today's behaviour.
     */
    fun isCoreRequired(type: ProductTypeKey?, field: CoreField): Boolean {
        field.capability?.let { if (!hasCapability(type, it)) return false }
        return typeDef(type).requiredOverrides.entries
            .firstOrNull { it.key.equals(field.key, ignoreCase = true) }
            ?.value ?: false
    }

    /** All core fields required for this type, for validation and form asterisks. */
    fun requiredCoreFields(type: ProductTypeKey?): Set<CoreField> =
        CoreField.entries.filterTo(mutableSetOf()) { isCoreRequired(type, it) }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_PLAN = "standard"

        /**
         * Defaults = every customer feature ON, so a company with no settings
         * doc behaves exactly as today. Admin-only tools stay OFF.
         */
        val DEFAULT_FEATURES: Map<Feature, Boolean> = Feature.entries.associateWith { it.defaultOn }

        /**
         * All six types are defined; only GENERAL (every existing WMS product)
         * and JEWELLERY (the pilot) are enabled until a settings doc says otherwise.
         */
        val DEFAULT_PRODUCT_TYPES: Map<ProductTypeKey, ProductTypeDef> =
            ProductTypeKey.entries.associateWith { key ->
                ProductTypeDef(
                    key = key,
                    enabled = key == ProductTypeKey.GENERAL || key == ProductTypeKey.JEWELLERY
                )
            }

        val DEFAULT = CompanyConfig()
    }
}

/** Where the current config came from — lets UI show "offline / defaults" if wanted. */
enum class ConfigSource { DEFAULTS, CACHE, SERVER }

// ── Features ──────────────────────────────────────────────────────

/**
 * Flaggable screens. [key] is the Firestore map key — never rename a key
 * once a customer doc uses it. Connect Device and Device Config are not
 * flaggable (the app is useless without them).
 *
 * [defaultOn] is the value when a company's settings don't mention the
 * flag. Admin-only tools default to off, so a new company never sees them
 * until SNA switches them on in the console.
 */
enum class Feature(val key: String, val displayName: String, val defaultOn: Boolean = true) {
    QUICK_SCAN("quickScan", "Quick Scan"),
    INVENTORY_SCAN("inventoryScan", "Inventory"),
    ORDER_PICKING("orderPicking", "Order Picking"),
    WRITE_TAG("writeTag", "Write Tag"),
    KILL_TAG("killTag", "Kill Tag"),
    LOCATE_TAG("locateTag", "Locate Tag"),
    WMS("wms", "Warehouse Management"),
    RETAIL("retail", "Retail Management"),
    MANAGER_ALERTS("managerAlerts", "Manager Alerts"),
    SHOP("shop", "Order Supplies"),

    /** Bulk product import / export by Excel, under Company Set Up. Admin-only, off by default. */
    CATALOG_EXCEL("catalogExcel", "Product catalog (Excel)", defaultOn = false);

    companion object {
        val TAG_OPERATIONS = listOf(QUICK_SCAN, INVENTORY_SCAN, ORDER_PICKING, WRITE_TAG, KILL_TAG, LOCATE_TAG)
        fun fromKey(key: String): Feature? = entries.firstOrNull { it.key == key }
    }
}

// ── Limits ────────────────────────────────────────────────────────

data class Limits(
    /** Replaces DeviceRepository.MAX_SAVED_DEVICES (hardcoded 10). */
    val maxDevices: Int = DEFAULT_MAX_DEVICES
) {
    companion object {
        const val DEFAULT_MAX_DEVICES = 10
    }
}

// ── Labels ────────────────────────────────────────────────────────

enum class LabelKey(val key: String, val defaultSingular: String, val defaultPlural: String) {
    WAREHOUSE("warehouse", "Warehouse", "Warehouses"),
    RACK("rack", "Rack", "Racks"),
    BIN("bin", "Bin", "Bins"),
    PRODUCT("product", "Product", "Products"),
    SKU("sku", "SKU", "SKUs");

    companion object {
        fun fromKey(key: String): LabelKey? = entries.firstOrNull { it.key == key }
    }
}

data class LabelText(val singular: String, val plural: String)

data class Labels(val overrides: Map<LabelKey, LabelText> = emptyMap()) {
    fun singular(key: LabelKey): String = overrides[key]?.singular ?: key.defaultSingular
    fun plural(key: LabelKey): String = overrides[key]?.plural ?: key.defaultPlural
}

// ── Product types ─────────────────────────────────────────────────

/**
 * Fixed list; extending it is a code change because types can drive screens.
 * [key] is stored on products as `productType` — never rename a key.
 */
enum class ProductTypeKey(
    val key: String,
    val defaultLabel: String,
    val defaultStatuses: List<UnitStatus>
) {
    IT_ASSET(
        "it_asset", "IT asset",
        listOf(UnitStatus.IN_STOCK, UnitStatus.ASSIGNED, UnitStatus.IN_REPAIR, UnitStatus.RETURNED,
            UnitStatus.LOST, UnitStatus.DISPOSED, UnitStatus.RETAGGED)
    ),
    NON_IT_ASSET(
        "non_it_asset", "Non-IT asset",
        listOf(UnitStatus.IN_STOCK, UnitStatus.ASSIGNED, UnitStatus.IN_REPAIR, UnitStatus.RETURNED,
            UnitStatus.LOST, UnitStatus.DISPOSED, UnitStatus.RETAGGED)
    ),
    APPAREL(
        "apparel", "Retail apparel",
        listOf(UnitStatus.IN_STOCK, UnitStatus.PICKED, UnitStatus.DISPATCHED, UnitStatus.SOLD,
            UnitStatus.RETURNED, UnitStatus.LOST, UnitStatus.RETAGGED)
    ),
    JEWELLERY(
        "jewellery", "Retail jewellery",
        listOf(UnitStatus.IN_STOCK, UnitStatus.SOLD, UnitStatus.RETURNED, UnitStatus.IN_REPAIR,
            UnitStatus.LOST, UnitStatus.RETAGGED)
    ),
    FOOD(
        "food", "Food",
        listOf(UnitStatus.IN_STOCK, UnitStatus.PICKED, UnitStatus.DISPATCHED, UnitStatus.EXPIRED,
            UnitStatus.RETURNED, UnitStatus.LOST, UnitStatus.DISPOSED, UnitStatus.RETAGGED)
    ),
    GENERAL(
        "general", "General",
        listOf(UnitStatus.IN_STOCK, UnitStatus.PICKED, UnitStatus.DISPATCHED, UnitStatus.SOLD,
            UnitStatus.RETURNED, UnitStatus.EXPIRED, UnitStatus.LOST, UnitStatus.DISPOSED,
            UnitStatus.RETAGGED)
    );

    companion object {
        fun fromKey(key: String?): ProductTypeKey? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

data class ProductTypeDef(
    val key: ProductTypeKey,
    val label: String = key.defaultLabel,
    val enabled: Boolean = false,
    /** Attribute group ids, in display order. */
    val groupIds: List<String> = emptyList(),
    /** Fields only this type has. */
    val fields: List<FieldDef> = emptyList(),
    /** Per-type required flag overrides, by field key (groups are shared, so their defaults live here). */
    val requiredOverrides: Map<String, Boolean> = emptyMap(),
    val statuses: List<UnitStatus> = key.defaultStatuses
)

// ── Unit status ───────────────────────────────────────────────────

/**
 * [key] is the value stored in inventory_units.status — existing data uses
 * in_stock / picked / dispatched / sold, so those keys must never change.
 * [systemOnly] statuses are set by the app or Cloud Functions, never picked by hand.
 */
enum class UnitStatus(val key: String, val displayName: String, val systemOnly: Boolean = false) {
    IN_STOCK("in_stock", "In stock"),
    PICKED("picked", "Picked"),
    DISPATCHED("dispatched", "Dispatched"),
    ASSIGNED("assigned", "Assigned"),
    SOLD("sold", "Sold"),
    IN_REPAIR("in_repair", "In repair"),
    RETURNED("returned", "Returned"),
    /** Set nightly from in_stock when expiryDate passes (batch tracking, autoExpire). */
    EXPIRED("expired", "Expired", systemOnly = true),
    LOST("lost", "Lost"),
    DISPOSED("disposed", "Disposed"),
    /** Old doc after a re-tag; points to the new EPC via replacedBy. */
    RETAGGED("retagged", "Re-tagged", systemOnly = true);

    companion object {
        fun fromKey(key: String?): UnitStatus? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

// ── Attribute groups and capabilities ─────────────────────────────

/**
 * Capabilities switch on app logic for products of a type whose groups
 * include them. Their core fields (e.g. batchNo, expiryDate, parentEpc)
 * are fixed in code and stored top-level on the unit, not in customFields;
 * a group's own [AttributeGroup.fields] can add extra data fields alongside.
 */
enum class Capability(val key: String) {
    BATCH_TRACKING("batchTracking"),   // batch master, expiry, FEFO, auto-expire, recall
    RETAIL_SALE("retailSale"),         // sold lifecycle, sale date / invoice, exit alerts
    ASSIGNMENT("assignment"),          // assign / return to a person or department
    AUDIT("audit"),                    // audit log subcollection: condition, GPS, photo
    DEPRECIATION("depreciation"),      // computed book value, disposal
    NESTING("nesting");                // carton / pallet parent-child tags

    companion object {
        fun fromKey(key: String?): Capability? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

data class AttributeGroup(
    val id: String,
    val label: String,
    val capability: Capability? = null,
    val fields: List<FieldDef> = emptyList(),
    val order: Int = 0
)

enum class PickRule(val key: String) {
    FEFO("FEFO"), FIFO("FIFO");

    companion object {
        fun fromKey(key: String?): PickRule? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

enum class ContainerKind(val key: String) {
    CARTON("carton"), PALLET("pallet");

    companion object {
        fun fromKey(key: String?): ContainerKind? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

data class BatchTrackingOptions(
    /** Expiry warning window for alerts. */
    val alertDays: Int = 30,
    val pickRule: PickRule = PickRule.FEFO,
    val blockExpiredDispatch: Boolean = true,
    /** Nightly function flips in_stock → expired once expiryDate passes. */
    val autoExpire: Boolean = true
)

data class NestingOptions(
    val levels: List<ContainerKind> = listOf(ContainerKind.CARTON)
)

/** Company-wide options for capabilities; whether a capability applies is decided per type via groups. */
data class CapabilityOptions(
    val batchTracking: BatchTrackingOptions = BatchTrackingOptions(),
    val nesting: NestingOptions = NestingOptions()
)

// ── Field definitions ─────────────────────────────────────────────

enum class FieldType(val key: String) {
    TEXT("text"), NUMBER("number"), DATE("date"), DROPDOWN("dropdown"), BOOLEAN("boolean");

    companion object {
        fun fromKey(key: String?): FieldType? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

/** Rule: a field that can differ between two pieces of the same product lives on the unit. */
enum class FieldLevel(val key: String) {
    PRODUCT("product"), UNIT("unit");

    companion object {
        fun fromKey(key: String?): FieldLevel? =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

/**
 * One field definition. Values are stored as customFields.{key} on the
 * product (level=product) or the unit (level=unit).
 *
 * [key] is the storage key — immutable once data exists. [label] is free to change.
 * [unit] is a display suffix only (g, ct, INR, °C).
 * [excelHeaders] are accepted import column names (matched case/space-insensitively);
 * the first one is the export header. Defaults to [label].
 */
data class FieldDef(
    val key: String,
    val label: String,
    val type: FieldType,
    val level: FieldLevel,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
    val unit: String = "",
    val order: Int = 0,
    val excelHeaders: List<String> = listOf(label),
    val active: Boolean = true
) {
    val exportHeader: String get() = excelHeaders.firstOrNull() ?: label

    companion object {
        /** lowerCamel / snake, starts with a letter, max 40 chars. Firestore-path safe. */
        val KEY_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]{0,39}$")

        /** Core and capability field names a custom field may not shadow (case-insensitive). */
        val RESERVED_KEYS = setOf(
            // product core
            "id", "sku", "skukey", "name", "title", "shortname", "producttype", "categoryid",
            "subcategoryid", "brandid", "description", "barcode", "unitofmeasure", "uom",
            "imageurl", "status", "isactive", "trackingmode",
            "createdat", "createdby", "updatedat", "updatedby", "customfields",
            // unit core
            "epc", "tid", "productid", "serialnumber", "price", "itemcode", "category",
            "warehouseid", "rackid", "binid", "bincode",
            "currentwarehouseid", "currentrackid", "currentbinid", "currentbincode",
            "inwardedat", "lastmovedat", "dispatchedat", "lastseenat", "lastseenby",
            "enrolledat", "enrolledby", "previousepcs", "replacedby",
            // capability fields
            "batchid", "batchno", "mfgdate", "expirydate", "soldat", "soldby",
            "parentepc", "unitkind", "childcount", "quantity", "assignedto"
        )

        fun isValidKey(key: String): Boolean =
            KEY_PATTERN.matches(key) && key.lowercase() !in RESERVED_KEYS
    }
}

// ── Parser ────────────────────────────────────────────────────────

object CompanyConfigParser {

    /** [data] = DocumentSnapshot.data. Null (doc missing) → defaults. */
    fun parse(data: Map<String, Any?>?, source: ConfigSource): CompanyConfig {
        if (data == null) return CompanyConfig.DEFAULT.copy(source = ConfigSource.DEFAULTS)
        return CompanyConfig(
            schemaVersion = (data["schemaVersion"] as? Number)?.toInt() ?: CompanyConfig.CURRENT_SCHEMA_VERSION,
            plan = string(data["plan"]) ?: CompanyConfig.DEFAULT_PLAN,
            template = string(data["template"]) ?: "",
            features = parseFeatures(data["features"]),
            limits = parseLimits(data["limits"]),
            labels = parseLabels(data["labels"]),
            attributeGroups = parseGroups(data["attributeGroups"]),
            productTypes = parseProductTypes(data["productTypes"]),
            capabilityOptions = parseCapabilityOptions(data["capabilities"]),
            source = source
        )
    }

    // ── helpers ──

    private fun string(raw: Any?): String? = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun stringList(raw: Any?): List<String> =
        (raw as? List<*>)?.mapNotNull { string(it) }?.distinct().orEmpty()

    // ── sections ──

    private fun parseFeatures(raw: Any?): Map<Feature, Boolean> {
        val map = raw as? Map<*, *> ?: return CompanyConfig.DEFAULT_FEATURES
        val result = CompanyConfig.DEFAULT_FEATURES.toMutableMap()
        map.forEach { (k, v) ->
            val feature = (k as? String)?.let { Feature.fromKey(it) } ?: return@forEach
            (v as? Boolean)?.let { result[feature] = it }
        }
        return result
    }

    private fun parseLimits(raw: Any?): Limits {
        val map = raw as? Map<*, *> ?: return Limits()
        val maxDevices = (map["maxDevices"] as? Number)?.toInt()?.takeIf { it >= 1 }
            ?: Limits.DEFAULT_MAX_DEVICES
        return Limits(maxDevices = maxDevices)
    }

    private fun parseLabels(raw: Any?): Labels {
        val map = raw as? Map<*, *> ?: return Labels()
        val overrides = mutableMapOf<LabelKey, LabelText>()
        map.forEach { (k, v) ->
            val key = (k as? String)?.let { LabelKey.fromKey(it) } ?: return@forEach
            val text = when (v) {
                is String -> string(v)?.let { LabelText(it, "${it}s") }
                is Map<*, *> -> string(v["singular"])?.let { LabelText(it, string(v["plural"]) ?: "${it}s") }
                else -> null
            }
            text?.let { overrides[key] = it }
        }
        return Labels(overrides)
    }

    private fun parseGroups(raw: Any?): Map<String, AttributeGroup> {
        val list = raw as? List<*> ?: return emptyMap()
        return list.mapNotNull { parseGroup(it) }
            .distinctBy { it.id }
            .associateBy { it.id }
    }

    private fun parseGroup(raw: Any?): AttributeGroup? {
        val m = raw as? Map<*, *> ?: return null
        val id = string(m["id"]) ?: return null
        if (!FieldDef.KEY_PATTERN.matches(id)) return null
        return AttributeGroup(
            id = id,
            label = string(m["label"]) ?: id,
            capability = Capability.fromKey(m["capability"] as? String),
            fields = parseFieldList(m["fields"]),
            order = (m["order"] as? Number)?.toInt() ?: 0
        )
    }

    private fun parseProductTypes(raw: Any?): Map<ProductTypeKey, ProductTypeDef> {
        val map = raw as? Map<*, *> ?: return CompanyConfig.DEFAULT_PRODUCT_TYPES
        val result = CompanyConfig.DEFAULT_PRODUCT_TYPES.toMutableMap()
        map.forEach { (k, v) ->
            val key = ProductTypeKey.fromKey(k as? String) ?: return@forEach
            val m = v as? Map<*, *> ?: return@forEach
            result[key] = parseType(key, m)
        }
        return result
    }

    private fun parseType(key: ProductTypeKey, m: Map<*, *>): ProductTypeDef {
        val overrides = (m["required"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) ->
                val fieldKey = string(k) ?: return@mapNotNull null
                val flag = v as? Boolean ?: return@mapNotNull null
                fieldKey to flag
            }
            ?.toMap()
            .orEmpty()

        val statuses = stringList(m["statuses"])
            .mapNotNull { UnitStatus.fromKey(it) }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { if (UnitStatus.RETAGGED in it) it else it + UnitStatus.RETAGGED }
            ?: key.defaultStatuses

        return ProductTypeDef(
            key = key,
            label = string(m["label"]) ?: key.defaultLabel,
            // A type mentioned in the doc is on unless explicitly disabled.
            enabled = m["enabled"] as? Boolean ?: true,
            groupIds = stringList(m["groups"]),
            fields = parseFieldList(m["fields"]),
            requiredOverrides = overrides,
            statuses = statuses
        )
    }

    private fun parseCapabilityOptions(raw: Any?): CapabilityOptions {
        val map = raw as? Map<*, *> ?: return CapabilityOptions()

        val bt = map["batchTracking"] as? Map<*, *>
        val batchDefaults = BatchTrackingOptions()
        val batch = if (bt == null) batchDefaults else BatchTrackingOptions(
            alertDays = (bt["alertDays"] as? Number)?.toInt()?.takeIf { it in 0..3650 } ?: batchDefaults.alertDays,
            pickRule = PickRule.fromKey(bt["pickRule"] as? String) ?: batchDefaults.pickRule,
            blockExpiredDispatch = bt["blockExpiredDispatch"] as? Boolean ?: batchDefaults.blockExpiredDispatch,
            autoExpire = bt["autoExpire"] as? Boolean ?: batchDefaults.autoExpire
        )

        val ns = map["nesting"] as? Map<*, *>
        val nesting = if (ns == null) NestingOptions() else NestingOptions(
            levels = stringList(ns["levels"])
                .mapNotNull { ContainerKind.fromKey(it) }
                .distinct()
                .ifEmpty { listOf(ContainerKind.CARTON) }
        )

        return CapabilityOptions(batchTracking = batch, nesting = nesting)
    }

    private fun parseFieldList(raw: Any?): List<FieldDef> =
        (raw as? List<*>)?.mapNotNull { parseField(it) }?.distinctBy { it.key.lowercase() }.orEmpty()

    private fun parseField(raw: Any?): FieldDef? {
        val m = raw as? Map<*, *> ?: return null
        val key = string(m["key"]) ?: return null
        if (!FieldDef.isValidKey(key)) return null
        val type = FieldType.fromKey(m["type"] as? String) ?: return null
        val level = FieldLevel.fromKey(m["level"] as? String) ?: return null
        val label = string(m["label"]) ?: key

        val options = stringList(m["options"])
        // A dropdown with no options can't be filled — drop it rather than block saves.
        if (type == FieldType.DROPDOWN && options.isEmpty()) return null

        val headers = stringList(m["excelHeaders"]).ifEmpty { listOf(label) }

        return FieldDef(
            key = key,
            label = label,
            type = type,
            level = level,
            required = m["required"] as? Boolean ?: false,
            options = options,
            unit = string(m["unit"]) ?: "",
            order = (m["order"] as? Number)?.toInt() ?: 0,
            excelHeaders = headers,
            active = m["active"] as? Boolean ?: true
        )
    }
}

// ── Core unit fields ─────────────────────────────────────────────────────

/**
 * Unit fields stored top-level on inventory_units (not in customFields)
 * that a type's `required` map may make mandatory. [key] matches the
 * InventoryUnit property name and the settings key — never rename one.
 * [capability] means the field only exists when the type has it.
 */
enum class CoreField(
    val key: String,
    val label: String,
    val capability: Capability? = null
) {
    SERIAL_NUMBER("serialNumber", "Serial number"),
    TID("tid", "TID"),
    PRICE("price", "Price"),
    BATCH_NO("batchNo", "Batch number", Capability.BATCH_TRACKING),
    MFG_DATE("mfgDate", "Manufacturing date", Capability.BATCH_TRACKING),
    EXPIRY_DATE("expiryDate", "Expiry date", Capability.BATCH_TRACKING);

    companion object {
        fun fromKey(key: String?): CoreField? =
            CoreField.entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}