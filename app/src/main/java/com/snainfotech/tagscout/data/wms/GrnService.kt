package com.snainfotech.tagscout.data.wms

import com.snainfotech.tagscout.data.config.Capability
import com.snainfotech.tagscout.data.config.ConfigRepository
import com.snainfotech.tagscout.data.config.CoreField
import com.snainfotech.tagscout.data.config.FieldDef
import com.snainfotech.tagscout.data.config.FieldLevel
import com.snainfotech.tagscout.data.config.FieldType
import com.snainfotech.tagscout.data.config.FieldValidator
import com.snainfotech.tagscout.data.file.GrnExcelParser

/**
 * Orchestrates the Inward (GRN) workflow.
 *
 * Two phases:
 *   1. [validate] resolves every row (SKU, warehouse, bin, EPC, per-piece
 *      fields, tag price, batch) and checks it, writing nothing.
 *   2. [commit] saves the clean rows through InventoryRepository.enrollUnits,
 *      the same path as scan-based enrollment: same unit fields, same
 *      duplicate protection, same movement records (referenceType "grn").
 *
 * Extra columns: any column the parser didn't treat as core is matched to
 * the row's product type — its unit fields (by FieldDef header), tag price,
 * and batch columns when the type tracks batches. Unmatched columns are ignored.
 */
class GrnService(
    private val warehouseRepository: WarehouseRepository = WarehouseRepository(),
    private val productRepository: ProductRepository = ProductRepository(),
    private val inventoryRepository: InventoryRepository = InventoryRepository(),
    private val batchRepository: BatchRepository = BatchRepository()
) {

    // ── Result types (unchanged for InwardViewModel) ──────────

    data class ValidatedRow(
        val rowNumber: Int,
        val sku: String,
        val epc: String,
        val serialNumber: String,
        val binCode: String,
        val warehouseName: String,
        val notes: String,
        val resolvedProduct: Product? = null,
        val resolvedWarehouseId: String = "",
        val resolvedRackId: String = "",
        val resolvedBinId: String = "",
        val errors: List<String>,
        /** Ready to enroll: EPC, serial, price and validated unit fields. */
        val unitInput: EnrollUnitInput? = null,
        val batchNo: String = "",
        val mfgDate: String = "",      // "YYYY-MM-DD" or ""
        val expiryDate: String = ""    // "YYYY-MM-DD" or ""
    )

    sealed class ValidationResult {
        data class AllClean(val rows: List<ValidatedRow>) : ValidationResult()
        data class HasErrors(
            val rows: List<ValidatedRow>,
            val errorRowCount: Int,
            val totalRowCount: Int
        ) : ValidationResult()
    }

    sealed class CommitResult {
        data class Success(val inwardedCount: Int, val grnReference: String) : CommitResult()
        data class PartialFailure(
            val inwardedCount: Int,
            val failedRowNumber: Int,
            val error: String
        ) : CommitResult()
        data class Failure(val error: String) : CommitResult()
    }

    private companion object {
        val PRICE_HEADERS = listOf("Tag price", "Price", "MRP")
        val BATCH_HEADERS = listOf("Batch number", "Batch no", "Batch", "Lot", "Lot number", "Lot no")
        val MFG_HEADERS = listOf("Mfg date", "Manufacturing date", "MFD", "Mfd date", "Date of manufacture")
        val EXPIRY_HEADERS = listOf("Expiry date", "Expiry", "Exp date", "Best before", "Use by")

        fun key(header: String) = FieldValidator.headerKey(header)
    }

    // ── Phase 1: Validate ─────────────────────────────────────

    suspend fun validate(rows: List<GrnExcelParser.GrnRow>): ValidationResult {
        val config = ConfigRepository.current

        val productCache = mutableMapOf<String, Product?>()
        val binCache = mutableMapOf<String, Bin?>()
        val batchCache = mutableMapOf<String, Batch?>()                 // productId|batchKey → stored batch
        val fileBatchDates = mutableMapOf<String, Pair<String, String>>() // productId|batchKey → (mfg, exp) in this file
        val warehouses = warehouseRepository.getWarehouses().getOrDefault(emptyList())

        val epcCounts = rows.map { it.epc.trim().uppercase() }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()

        val validated = rows.map { r ->
            val errors = r.parseErrors.toMutableList()
            val epc = r.epc.trim().uppercase()

            // EPC: format and within-file duplicates
            if (epc.isNotBlank() && !isValidEpc(epc)) errors += "EPC '$epc' isn't a valid tag ID (hex, 8–128 characters)"
            if (epc.isNotBlank() && (epcCounts[epc] ?: 0) > 1) errors += "EPC '$epc' appears in multiple rows of this file"

            // SKU
            var product: Product? = null
            if (r.sku.isNotBlank()) {
                product = productCache.getOrPut(Product.skuKeyOf(r.sku)) {
                    productRepository.getProductBySku(r.sku).getOrNull()
                }
                if (product == null) errors += "SKU '${r.sku.trim()}' not found in the product catalog"
            }

            // Warehouse (case-insensitive name)
            var warehouse: Warehouse? = null
            if (r.warehouseName.isNotBlank()) {
                warehouse = warehouses.firstOrNull { it.name.equals(r.warehouseName.trim(), ignoreCase = true) }
                if (warehouse == null) errors += "Warehouse '${r.warehouseName.trim()}' not found"
            }

            // Bin (must belong to the warehouse)
            var bin: Bin? = null
            if (r.binCode.isNotBlank()) {
                bin = binCache.getOrPut("${warehouse?.id.orEmpty()}|${r.binCode.trim()}") {
                    val found = warehouseRepository.findBinByCode(r.binCode).getOrNull()
                    if (found != null && warehouse != null && found.warehouseId != warehouse.id) null else found
                }
                if (bin == null) {
                    errors += if (warehouse != null) "Bin '${r.binCode.trim()}' not found in warehouse '${warehouse.name}'"
                    else "Bin '${r.binCode.trim()}' not found"
                }
            }

            // Per-piece values, checked against this row's product type
            var unitInput: EnrollUnitInput? = null
            var batchNo = ""
            var mfg = ""
            var exp = ""
            if (product != null) {
                val type = product.type()
                val extras = r.extras.mapKeys { (h, _) -> h.trim() }
                fun extra(headers: List<String>): String {
                    val keys = headers.map { key(it) }.toSet()
                    return extras.entries.firstOrNull { key(it.key) in keys }?.value?.trim().orEmpty()
                }

                // Serial
                val serial = r.serialNumber.trim()
                if (serial.isEmpty() && config.isCoreRequired(type, CoreField.SERIAL_NUMBER)) {
                    errors += "Serial Number is required for ${config.typeDef(type).label}"
                }

                // Tag price
                val priceDef = FieldDef(
                    key = CoreField.PRICE.key, label = "Tag price", type = FieldType.NUMBER, level = FieldLevel.UNIT,
                    required = config.isCoreRequired(type, CoreField.PRICE)
                )
                val priceCheck = FieldValidator.check(priceDef, extra(PRICE_HEADERS).ifBlank { null })
                priceCheck.error?.let { errors += it }
                val price = (priceCheck.value as? Number)?.toDouble()?.takeIf { it > 0 }
                if (priceCheck.value != null && price == null) errors += "Tag price must be more than 0"

                // Unit custom fields, matched by header
                val defs = config.fieldsFor(type, FieldLevel.UNIT)
                val input = mutableMapOf<String, Any?>()
                extras.forEach { (header, value) ->
                    defs.firstOrNull { FieldValidator.matchesHeader(it, header) }?.let { input[it.key] = value }
                }
                val fields = FieldValidator.validate(config, type, FieldLevel.UNIT, input)
                errors += fields.errors.values

                // Batch
                if (config.hasCapability(type, Capability.BATCH_TRACKING)) {
                    batchNo = extra(BATCH_HEADERS)
                    val mfgCheck = FieldValidator.check(dateDef("mfgDate", "Mfg date"), extra(MFG_HEADERS).ifBlank { null })
                    val expCheck = FieldValidator.check(dateDef("expiryDate", "Expiry date"), extra(EXPIRY_HEADERS).ifBlank { null })
                    mfgCheck.error?.let { errors += it }
                    expCheck.error?.let { errors += it }
                    mfg = mfgCheck.value?.toString().orEmpty()
                    exp = expCheck.value?.toString().orEmpty()
                    errors += checkBatch(
                        config = config, product = product, batchNo = batchNo, mfg = mfg, exp = exp,
                        batchCache = batchCache, fileBatchDates = fileBatchDates
                    )
                }

                unitInput = EnrollUnitInput(
                    epc = epc,
                    serialNumber = serial,
                    price = price,
                    customFields = fields.values
                )
            }

            // Already in stock (only worth a read once the row is otherwise clean)
            if (epc.isNotBlank() && errors.isEmpty()) {
                inventoryRepository.findUnitByEpc(epc).fold(
                    onSuccess = { existing -> if (existing != null) errors += "EPC '$epc' is already in inventory" },
                    onFailure = { e -> errors += "Could not verify EPC uniqueness: ${e.message}" }
                )
            }

            ValidatedRow(
                rowNumber = r.rowNumber,
                sku = r.sku,
                epc = epc,
                serialNumber = r.serialNumber,
                binCode = r.binCode,
                warehouseName = r.warehouseName,
                notes = r.notes,
                resolvedProduct = product,
                resolvedWarehouseId = warehouse?.id.orEmpty(),
                resolvedRackId = bin?.rackId.orEmpty(),
                resolvedBinId = bin?.id.orEmpty(),
                errors = errors,
                unitInput = unitInput,
                batchNo = batchNo,
                mfgDate = mfg,
                expiryDate = exp
            )
        }

        val errorRowCount = validated.count { it.errors.isNotEmpty() }
        return if (errorRowCount == 0) ValidationResult.AllClean(validated)
        else ValidationResult.HasErrors(validated, errorRowCount, validated.size)
    }

    private fun dateDef(key: String, label: String) =
        FieldDef(key = key, label = label, type = FieldType.DATE, level = FieldLevel.UNIT)

    /** Batch rules for one row. Returns errors (empty = fine). */
    private suspend fun checkBatch(
        config: com.snainfotech.tagscout.data.config.CompanyConfig,
        product: Product,
        batchNo: String,
        mfg: String,
        exp: String,
        batchCache: MutableMap<String, Batch?>,
        fileBatchDates: MutableMap<String, Pair<String, String>>
    ): List<String> {
        val type = product.type()
        val errors = mutableListOf<String>()

        if (batchNo.isBlank()) {
            if (config.isCoreRequired(type, CoreField.BATCH_NO)) errors += "Batch number is required"
            else if (mfg.isNotBlank() || exp.isNotBlank()) errors += "Batch number is needed for these dates"
            return errors
        }
        if (batchNo.length > BatchRepository.BATCH_NO_MAX) {
            return listOf("Batch number can be at most ${BatchRepository.BATCH_NO_MAX} characters")
        }

        val cacheKey = "${product.id}|${BatchRepository.batchKeyOf(batchNo)}"

        // Same batch, same dates, on every row of this file
        val seen = fileBatchDates[cacheKey]
        if (seen == null) fileBatchDates[cacheKey] = mfg to exp
        else if ((mfg.isNotBlank() && seen.first.isNotBlank() && mfg != seen.first) ||
            (exp.isNotBlank() && seen.second.isNotBlank() && exp != seen.second)
        ) errors += "Batch $batchNo has different dates on another row of this file"

        val stored = batchCache.getOrPut(cacheKey) {
            batchRepository.findBatch(product.id, batchNo).getOrNull()
        }
        if (stored != null) {
            if (mfg.isNotBlank() && mfg != stored.mfgDate) {
                errors += "Batch $batchNo already exists with mfg date ${stored.mfgDate.ifBlank { "not set" }}"
            }
            if (exp.isNotBlank() && exp != stored.expiryDate) {
                errors += "Batch $batchNo already exists with expiry date ${stored.expiryDate.ifBlank { "not set" }}"
            }
        } else {
            if (mfg.isBlank() && config.isCoreRequired(type, CoreField.MFG_DATE)) errors += "Mfg date is required for a new batch"
            if (exp.isBlank() && config.isCoreRequired(type, CoreField.EXPIRY_DATE)) errors += "Expiry date is required for a new batch"
            BatchDates.validate(mfg, exp)?.let { errors += it }
        }
        return errors
    }

    // ── Phase 2: Commit ───────────────────────────────────────

    /**
     * Saves every clean row through enrollUnits, grouped by product, bin,
     * batch and note (one EnrollContext per group). Rows that fail are
     * reported; the others are saved regardless.
     */
    suspend fun commit(rows: List<ValidatedRow>, grnReference: String): CommitResult {
        val ready = rows.filter { it.errors.isEmpty() && it.resolvedProduct != null && it.unitInput != null }
        if (ready.isEmpty()) return CommitResult.Failure("Nothing to inward")

        val rowByEpc = ready.associateBy { it.epc }
        val failures = mutableListOf<Pair<Int, String>>()   // rowNumber → reason
        var inwarded = 0
        val batches = mutableMapOf<String, Result<Batch>>()

        val groups = ready.groupBy {
            listOf(
                it.resolvedProduct!!.id, it.resolvedBinId,
                BatchRepository.batchKeyOf(it.batchNo), it.notes.trim()
            )
        }

        for ((_, groupRows) in groups) {
            val first = groupRows.first()
            val product = first.resolvedProduct!!

            val batch: Batch? = if (first.batchNo.isBlank()) null else {
                val key = "${product.id}|${BatchRepository.batchKeyOf(first.batchNo)}"
                val dated = groupRows.firstOrNull { it.mfgDate.isNotBlank() || it.expiryDate.isNotBlank() } ?: first
                val result = batches.getOrPut(key) {
                    batchRepository.findOrCreate(product.id, product.sku, first.batchNo, dated.mfgDate, dated.expiryDate)
                }
                result.getOrElse { e ->
                    groupRows.forEach { failures += it.rowNumber to "Batch ${first.batchNo}: ${e.message}" }
                    null
                } ?: continue
            }

            val context = EnrollContext(
                product = product,
                warehouseId = first.resolvedWarehouseId,
                rackId = first.resolvedRackId,
                binId = first.resolvedBinId,
                binCode = first.binCode.trim(),
                batch = batch,
                referenceType = "grn",
                referenceId = grnReference.trim(),
                notes = first.notes.trim()
            )

            inventoryRepository.enrollUnits(context, groupRows.mapNotNull { it.unitInput }).fold(
                onSuccess = { summary ->
                    inwarded += summary.enrolled
                    summary.failures.forEach { f ->
                        failures += (rowByEpc[f.epc]?.rowNumber ?: 0) to f.message.ifBlank { f.outcome.name }
                    }
                },
                onFailure = { e ->
                    groupRows.forEach { failures += it.rowNumber to (e.message ?: "Save failed") }
                }
            )
        }

        if (failures.isEmpty()) return CommitResult.Success(inwardedCount = inwarded, grnReference = grnReference)

        val sorted = failures.sortedBy { it.first }
        val (firstRow, firstReason) = sorted.first()
        val more = if (sorted.size > 1) " (${sorted.size} rows not inwarded: " +
                sorted.take(5).joinToString("; ") { "row ${it.first}" } + if (sorted.size > 5) "…)" else ")" else ""
        return CommitResult.PartialFailure(
            inwardedCount = inwarded,
            failedRowNumber = firstRow,
            error = firstReason + more
        )
    }
}