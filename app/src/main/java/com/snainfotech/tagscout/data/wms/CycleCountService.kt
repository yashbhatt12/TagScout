package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
import kotlinx.coroutines.tasks.await

/**
 * Orchestrates the Cycle Count (bin-level) workflow.
 *
 * v1 scope:
 *   - Bin-level only (not rack/warehouse)
 *   - Log-only reconciliation: writes a cyclecount_adjust movement for
 *     every discrepancy so managers have an audit trail, but does NOT
 *     auto-relocate, auto-delete, or otherwise mutate inventory_units.
 *
 * Why log-only:
 *   Real warehouses need human judgment on discrepancies. Was the missing
 *   item stolen? Miscounted? On its way back from a pick? Auto-changing
 *   the database based on one scan is dangerous. The movements ledger
 *   captures the truth of "what was seen when" and management workflows
 *   (future) decide what to do about it.
 */
class CycleCountService(
    private val inventoryRepository: InventoryRepository = InventoryRepository(),
    private val productRepository: ProductRepository = ProductRepository()
) {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private suspend fun companyId(): String? = AuthReady.awaitUid()

    private suspend fun companyDoc() = companyId()?.let {
        firestore.collection("companies").document(it)
    }

    // ── Result types ──────────────────────────────────────────

    /**
     * One row in the reconciliation report. Every scanned or expected EPC
     * ends up as one of these.
     */
    data class ReconciliationRow(
        val epc: String,
        val sku: String,                    // "" if unknown (unexpected tag not in DB)
        val classification: Classification,
        val expectedBinCode: String = "",   // only for UNEXPECTED (where DB thinks it is)
        val expectedBinId: String = ""      // ditto
    )

    enum class Classification {
        MATCHED,      // scanned AND in the DB for this bin
        MISSING,      // in the DB for this bin, NOT scanned
        UNEXPECTED    // scanned, but not in the DB for this bin (may be elsewhere)
    }

    data class ReconciliationReport(
        val binId: String,
        val binCode: String,
        val warehouseId: String,
        val rackId: String,
        val rows: List<ReconciliationRow>
    ) {
        val matchedCount get() = rows.count { it.classification == Classification.MATCHED }
        val missingCount get() = rows.count { it.classification == Classification.MISSING }
        val unexpectedCount get() = rows.count { it.classification == Classification.UNEXPECTED }
        val discrepancyCount get() = missingCount + unexpectedCount
        val hasNoDiscrepancies get() = discrepancyCount == 0
    }

    sealed class CommitResult {
        data class Success(val movementsWritten: Int, val reference: String) : CommitResult()
        data class NothingToCommit(val reference: String) : CommitResult()
        data class Failure(val error: String) : CommitResult()
    }

    // ── Reconcile ─────────────────────────────────────────────

    /**
     * Compare the scanned EPCs against what the database says should be
     * in this bin. Returns a full row-by-row classification.
     *
     * Reads inventory_units filtered by binId and also fetches the current
     * location of each "unexpected" scanned EPC (so the UI can show
     * "this is here but DB says it's in BIN-B-03").
     */
    suspend fun reconcile(
        binId: String,
        binCode: String,
        warehouseId: String,
        rackId: String,
        scannedEpcs: Set<String>
    ): Result<ReconciliationReport> {
        return try {
            val company = companyDoc()
                ?: return Result.failure(Exception("Not logged in"))

            // 1. Pull the expected inventory for this bin.
            val expectedUnitsResult = inventoryRepository.getUnitsInBin(binId)
            val expectedUnits = expectedUnitsResult.getOrElse {
                return Result.failure(it)
            }
            val expectedEpcs = expectedUnits.map { it.epc }.toSet()
            val expectedUnitByEpc = expectedUnits.associateBy { it.epc }

            // 2. Classify each EPC that appears in either set.
            val allEpcs = (expectedEpcs union scannedEpcs)
            val rows = mutableListOf<ReconciliationRow>()

            for (epc in allEpcs) {
                val inExpected = epc in expectedEpcs
                val inScanned = epc in scannedEpcs

                when {
                    inExpected && inScanned -> {
                        val unit = expectedUnitByEpc[epc]!!
                        rows.add(ReconciliationRow(
                            epc = epc,
                            sku = unit.sku,
                            classification = Classification.MATCHED
                        ))
                    }
                    inExpected && !inScanned -> {
                        val unit = expectedUnitByEpc[epc]!!
                        rows.add(ReconciliationRow(
                            epc = epc,
                            sku = unit.sku,
                            classification = Classification.MISSING
                        ))
                    }
                    !inExpected && inScanned -> {
                        // Look up where the DB thinks this tag is, if it exists at all
                        val existing = inventoryRepository.findUnitByEpc(epc).getOrNull()
                        rows.add(ReconciliationRow(
                            epc = epc,
                            sku = existing?.sku ?: "",
                            classification = Classification.UNEXPECTED,
                            expectedBinCode = existing?.currentBinCode ?: "",
                            expectedBinId = existing?.currentBinId ?: ""
                        ))
                    }
                }
            }

            // Sort: MATCHED first (green), then MISSING (red), then UNEXPECTED (amber)
            val sortedRows = rows.sortedWith(compareBy(
                { it.classification.ordinal },
                { it.sku },
                { it.epc }
            ))

            Result.success(ReconciliationReport(
                binId = binId,
                binCode = binCode,
                warehouseId = warehouseId,
                rackId = rackId,
                rows = sortedRows
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Commit reconciliation as movements ────────────────────

    /**
     * Writes one cyclecount_adjust movement per discrepancy (missing or
     * unexpected). Matched rows don't need movements — they represent the
     * "no news" case.
     *
     * Log-only: does NOT change inventory_units, does NOT relocate anything.
     * The Cloud Function will pick up these movements and reflect them in
     * inventory_sku aggregates for reporting.
     */
    suspend fun commitReconciliation(
        report: ReconciliationReport,
        reference: String
    ): CommitResult {
        val company = companyDoc() ?: return CommitResult.Failure("Not logged in")
        val cid = companyId() ?: return CommitResult.Failure("Not logged in")

        val discrepancies = report.rows.filter {
            it.classification == Classification.MISSING ||
                    it.classification == Classification.UNEXPECTED
        }
        if (discrepancies.isEmpty()) {
            return CommitResult.NothingToCommit(reference)
        }

        val now = Timestamp.now()
        var written = 0

        try {
            for (row in discrepancies) {
                val movementRef = company.collection("movements").document()

                // Quantity semantics for a cycle count adjust:
                //   MISSING at bin X    → quantity -1 (row was expected, wasn't there)
                //   UNEXPECTED at bin X → quantity +1 (row was found, wasn't recorded here)
                //
                // fromLocation / toLocation both reference the audited bin.
                // The "adjust" type + reference make it clear this is an audit
                // record, not an actual physical movement of goods.
                val quantity = if (row.classification == Classification.MISSING) -1 else 1

                val movementData = hashMapOf(
                    "type" to "cyclecount_adjust",
                    "productId" to "",       // resolved from EPC later if needed
                    "sku" to row.sku,
                    "epc" to row.epc,
                    "quantity" to quantity,
                    "fromWarehouseId" to report.warehouseId,
                    "fromRackId" to report.rackId,
                    "fromBinId" to report.binId,
                    "fromBinCode" to report.binCode,
                    "toWarehouseId" to report.warehouseId,
                    "toRackId" to report.rackId,
                    "toBinId" to report.binId,
                    "toBinCode" to report.binCode,
                    "referenceType" to "cyclecount",
                    "referenceId" to reference.trim(),
                    "userId" to cid,
                    "timestamp" to now,
                    "notes" to when (row.classification) {
                        Classification.MISSING -> "Expected at ${report.binCode}, not physically found"
                        Classification.UNEXPECTED ->
                            if (row.expectedBinCode.isNotBlank())
                                "Physically found at ${report.binCode}, database has it at ${row.expectedBinCode}"
                            else
                                "Physically found at ${report.binCode}, not in database"
                        else -> ""
                    }
                )
                movementRef.set(movementData).await()
                written++
            }
            return CommitResult.Success(movementsWritten = written, reference = reference)
        } catch (e: Exception) {
            return CommitResult.Failure(
                "Wrote $written of ${discrepancies.size} movements before failure: ${e.message}"
            )
        }
    }
}