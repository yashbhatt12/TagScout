package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
import com.snainfotech.tagscout.data.file.GrnExcelParser
import kotlinx.coroutines.tasks.await

/**
 * Orchestrates the Inward (GRN) workflow.
 *
 * Two-phase design:
 *   1. Validate: resolve every row's SKU/Warehouse/Bin against the database,
 *      check for duplicate EPCs and file-level uniqueness. Produce a report
 *      the user can review before committing anything.
 *   2. Commit: only runs if validation is fully clean. Writes each row as
 *      an atomic (unit + movement) transaction. Any single row failing aborts
 *      the batch — but since we validated first, real failures are rare.
 *
 * Design notes:
 *   - Reference resolution (SKU → productId, Bin → binId) happens ONCE up front
 *     with cached lookups, so a file with 100 rows referencing 5 SKUs makes
 *     5 product queries, not 100.
 *   - EPC duplicates are checked both within the file (row X vs row Y) and
 *     against the database (already in inventory).
 *   - Warehouse Name is used as a display-friendly identifier; internally we
 *     resolve it to the warehouseId at validation time.
 */
class GrnService(
    private val warehouseRepository: WarehouseRepository = WarehouseRepository(),
    private val productRepository: ProductRepository = ProductRepository()
) {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private suspend fun companyId(): String? = AuthReady.awaitUid()

    private fun userIdSync(): String = auth.currentUser?.uid ?: ""

    private suspend fun companyDoc() = companyId()?.let {
        firestore.collection("companies").document(it)
    }

    // ── Result types ──────────────────────────────────────────

    /**
     * Per-row validation result. If [errors] is empty, the row is ready
     * to inward. If not, the whole file is rejected and errors are shown.
     */
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
        val errors: List<String>
    )

    sealed class ValidationResult {
        /** Every row validated cleanly — ready to commit. */
        data class AllClean(val rows: List<ValidatedRow>) : ValidationResult()

        /** One or more rows failed validation — the file is rejected. */
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

    // ── Phase 1: Validate ─────────────────────────────────────

    /**
     * Validate every parsed row against the current database state. Resolves
     * SKUs to Products, Warehouse Names to warehouseIds, and Bin Codes to
     * (rackId, binId). Also checks for EPC duplicates within the file and
     * against existing inventory.
     *
     * This does NOT write anything.
     */
    suspend fun validate(rows: List<GrnExcelParser.GrnRow>): ValidationResult {
        val company = companyDoc()
            ?: return ValidationResult.HasErrors(
                rows = rows.map {
                    ValidatedRow(
                        rowNumber = it.rowNumber,
                        sku = it.sku, epc = it.epc, serialNumber = it.serialNumber,
                        binCode = it.binCode, warehouseName = it.warehouseName, notes = it.notes,
                        errors = listOf("Not logged in")
                    )
                },
                errorRowCount = rows.size,
                totalRowCount = rows.size
            )

        // Caches for reference resolution
        val productCache = mutableMapOf<String, Product?>()
        val warehouseCache = mutableMapOf<String, Warehouse?>()
        val binCache = mutableMapOf<String, Bin?>()

        // Load all warehouses once — small list, avoids per-row lookups
        val warehouses = warehouseRepository.getWarehouses().getOrDefault(emptyList())

        // Detect within-file EPC duplicates
        val epcDuplicateCount = rows.groupBy { it.epc.trim() }
            .filter { it.key.isNotBlank() && it.value.size > 1 }
            .mapValues { it.value.size }

        val validated = mutableListOf<ValidatedRow>()

        for (r in rows) {
            val errors = mutableListOf<String>()
            errors.addAll(r.parseErrors)

            val cleanEpc = r.epc.trim()

            // Within-file duplicate check
            if (cleanEpc.isNotBlank() && (epcDuplicateCount[cleanEpc] ?: 0) > 1) {
                errors.add("EPC '$cleanEpc' appears in multiple rows of this file")
            }

            // SKU resolution
            var resolvedProduct: Product? = null
            if (r.sku.isNotBlank()) {
                resolvedProduct = productCache.getOrPut(r.sku.trim()) {
                    productRepository.getProductBySku(r.sku).getOrNull()
                }
                if (resolvedProduct == null) {
                    errors.add("SKU '${r.sku.trim()}' not found in the product catalog")
                }
            }

            // Warehouse resolution (case-insensitive name match)
            var resolvedWarehouse: Warehouse? = null
            if (r.warehouseName.isNotBlank()) {
                resolvedWarehouse = warehouseCache.getOrPut(r.warehouseName.trim()) {
                    warehouses.firstOrNull {
                        it.name.equals(r.warehouseName.trim(), ignoreCase = true)
                    }
                }
                if (resolvedWarehouse == null) {
                    errors.add("Warehouse '${r.warehouseName.trim()}' not found")
                }
            }

            // Bin resolution (by binCode, scoped to resolved warehouse if any)
            var resolvedBin: Bin? = null
            if (r.binCode.isNotBlank()) {
                val cacheKey = "${resolvedWarehouse?.id ?: ""}|${r.binCode.trim()}"
                resolvedBin = binCache.getOrPut(cacheKey) {
                    val bin = warehouseRepository.findBinByCode(r.binCode).getOrNull()
                    // Enforce: bin must belong to the specified warehouse
                    if (bin != null && resolvedWarehouse != null && bin.warehouseId != resolvedWarehouse.id) {
                        null
                    } else bin
                }
                if (resolvedBin == null) {
                    errors.add(
                        if (resolvedWarehouse != null)
                            "Bin '${r.binCode.trim()}' not found in warehouse '${resolvedWarehouse.name}'"
                        else
                            "Bin '${r.binCode.trim()}' not found"
                    )
                }
            }

            // EPC already exists in inventory
            if (cleanEpc.isNotBlank() && errors.isEmpty()) {
                try {
                    val existing = company.collection("inventory_units")
                        .document(cleanEpc).get().await()
                    if (existing.exists()) {
                        errors.add("EPC '$cleanEpc' is already in inventory")
                    }
                } catch (e: Exception) {
                    errors.add("Could not verify EPC uniqueness: ${e.message}")
                }
            }

            validated.add(
                ValidatedRow(
                    rowNumber = r.rowNumber,
                    sku = r.sku, epc = r.epc, serialNumber = r.serialNumber,
                    binCode = r.binCode, warehouseName = r.warehouseName, notes = r.notes,
                    resolvedProduct = resolvedProduct,
                    resolvedWarehouseId = resolvedWarehouse?.id.orEmpty(),
                    resolvedRackId = resolvedBin?.rackId.orEmpty(),
                    resolvedBinId = resolvedBin?.id.orEmpty(),
                    errors = errors
                )
            )
        }

        val errorRowCount = validated.count { it.errors.isNotEmpty() }
        return if (errorRowCount == 0) {
            ValidationResult.AllClean(validated)
        } else {
            ValidationResult.HasErrors(
                rows = validated,
                errorRowCount = errorRowCount,
                totalRowCount = validated.size
            )
        }
    }

    // ── Phase 2: Commit ──────────────────────────────────────

    /**
     * Writes all validated rows to Firestore. Each row is a transaction
     * (unit + movement) — if any row's transaction fails, remaining rows
     * are aborted and we report which one broke.
     *
     * The Cloud Function updateSkuAggregate handles /inventory_sku/ automatically.
     */
    suspend fun commit(
        rows: List<ValidatedRow>,
        grnReference: String
    ): CommitResult {
        val company = companyDoc() ?: return CommitResult.Failure("Not logged in")
        val uid = userIdSync()
        var inwardedCount = 0

        for (r in rows) {
            if (r.errors.isNotEmpty() || r.resolvedProduct == null) {
                return CommitResult.PartialFailure(
                    inwardedCount = inwardedCount,
                    failedRowNumber = r.rowNumber,
                    error = "Row was not fully validated"
                )
            }

            val product = r.resolvedProduct
            val cleanEpc = r.epc.trim()
            val unitRef = company.collection("inventory_units").document(cleanEpc)
            val movementRef = company.collection("movements").document()
            val now = Timestamp.now()

            try {
                firestore.runTransaction { txn ->
                    // Re-check inside the transaction — protects against a race where
                    // another operation inserted the same EPC between validation and commit
                    val existing = txn.get(unitRef)
                    if (existing.exists()) {
                        throw Exception("EPC $cleanEpc was created by another operation just now")
                    }

                    txn.set(unitRef, hashMapOf(
                        "epc" to cleanEpc,
                        "productId" to product.id,
                        "sku" to product.sku,
                        "serialNumber" to r.serialNumber.trim(),
                        "currentWarehouseId" to r.resolvedWarehouseId,
                        "currentRackId" to r.resolvedRackId,
                        "currentBinId" to r.resolvedBinId,
                        "currentBinCode" to r.binCode.trim(),
                        "status" to "in_stock",
                        "inwardedAt" to now,
                        "lastMovedAt" to now
                    ))

                    txn.set(movementRef, hashMapOf(
                        "type" to "inward",
                        "productId" to product.id,
                        "sku" to product.sku,
                        "epc" to cleanEpc,
                        "quantity" to 1,
                        "fromWarehouseId" to "",
                        "fromRackId" to "",
                        "fromBinId" to "",
                        "fromBinCode" to "",
                        "toWarehouseId" to r.resolvedWarehouseId,
                        "toRackId" to r.resolvedRackId,
                        "toBinId" to r.resolvedBinId,
                        "toBinCode" to r.binCode.trim(),
                        "referenceType" to "grn",
                        "referenceId" to grnReference.trim(),
                        "userId" to uid,
                        "timestamp" to now,
                        "notes" to r.notes
                    ))
                    null
                }.await()
                inwardedCount++
            } catch (e: Exception) {
                return CommitResult.PartialFailure(
                    inwardedCount = inwardedCount,
                    failedRowNumber = r.rowNumber,
                    error = e.message ?: "Unknown error"
                )
            }
        }

        return CommitResult.Success(inwardedCount = inwardedCount, grnReference = grnReference)
    }
}