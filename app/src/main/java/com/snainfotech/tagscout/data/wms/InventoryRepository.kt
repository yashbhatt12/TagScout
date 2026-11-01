package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.server.ServerCalls
import com.snainfotech.tagscout.data.auth.AuthReady
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.firebase.firestore.FieldValue
import com.snainfotech.tagscout.data.config.UnitStatus
/**
 * Reads/writes inventory units and the movement ledger.
 *
 * Design contract:
 *   Every inventory-changing operation writes BOTH the unit AND a movement
 *   record, in a single Firestore transaction. The transaction guarantees
 *   both writes succeed or both fail — the ledger and the unit state can
 *   never disagree, even if the app crashes mid-operation.
 *
 * The Cloud Function on /movements/ handles the /inventory_sku/ aggregate.
 * This repository never touches inventory_sku directly.
 */
class InventoryRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /** Uses AuthReady to avoid the startup race where currentUser is briefly null. */
    private suspend fun companyId(): String? = AuthReady.awaitCompanyId()

    /**
     * Non-suspend userId for stamping into movement records. Only ever called
     * from inside methods that have already awaited companyId(), so by that
     * point the currentUser is guaranteed to be present.
     */
    private fun userId(): String = auth.currentUser?.uid ?: ""

    private suspend fun companyDoc() = companyId()?.let {
        firestore.collection("companies").document(it)
    }

    // ── Read ───────────────────────────────────────────────────

    suspend fun findUnitByEpc(epc: String): Result<InventoryUnit?> {
        return try {
            val ref = companyDoc()?.collection("inventory_units")?.document(epc.trim())
                ?: return Result.failure(Exception("Not logged in"))
            val doc = ref.get().await()
            if (!doc.exists()) return Result.success(null)
            Result.success(
                InventoryUnit(
                    epc = doc.id,
                    productId = doc.getString("productId") ?: "",
                    sku = doc.getString("sku") ?: "",
                    serialNumber = doc.getString("serialNumber") ?: "",
                    currentWarehouseId = doc.getString("currentWarehouseId") ?: "",
                    currentRackId = doc.getString("currentRackId") ?: "",
                    currentBinId = doc.getString("currentBinId") ?: "",
                    currentBinCode = doc.getString("currentBinCode") ?: "",
                    status = doc.getString("status") ?: "in_stock",
                    inwardedAt = doc.getTimestamp("inwardedAt"),
                    lastMovedAt = doc.getTimestamp("lastMovedAt"),
                    dispatchedAt = doc.getTimestamp("dispatchedAt")
                )
            )
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Delete-guard counts ────────────────────────────────────
    //
    // Used before deleting a bin/rack/warehouse/product to decide whether the
    // delete should be blocked. Uses Firestore's aggregation count() so we
    // pay for one aggregate read regardless of how many units match — cheap
    // and future-proof for larger warehouses.
    //
    // Note: these are advisory. In single-user single-tenant v1 there's no
    // realistic race between count-and-delete. When the Admin Console lands
    // and multiple users share a companyId, we'll want to move the guard
    // into a Cloud Function or transaction for real atomicity.

    /** How many units are currently sitting in this specific bin. */
    suspend fun countUnitsInBin(binId: String): Result<Long> {
        return try {
            val ref = companyDoc()?.collection("inventory_units")
                ?: return Result.failure(Exception("Not logged in"))
            val agg = ref.whereEqualTo("currentBinId", binId)
                .count().get(AggregateSource.SERVER).await()
            Result.success(agg.count)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** How many units are currently under any bin of this rack. */
    suspend fun countUnitsInRack(rackId: String): Result<Long> {
        return try {
            val ref = companyDoc()?.collection("inventory_units")
                ?: return Result.failure(Exception("Not logged in"))
            val agg = ref.whereEqualTo("currentRackId", rackId)
                .count().get(AggregateSource.SERVER).await()
            Result.success(agg.count)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** How many units are currently anywhere under this warehouse. */
    suspend fun countUnitsInWarehouse(warehouseId: String): Result<Long> {
        return try {
            val ref = companyDoc()?.collection("inventory_units")
                ?: return Result.failure(Exception("Not logged in"))
            val agg = ref.whereEqualTo("currentWarehouseId", warehouseId)
                .count().get(AggregateSource.SERVER).await()
            Result.success(agg.count)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** How many units currently reference this product (by productId). */
    suspend fun countUnitsForProduct(productId: String): Result<Long> {
        return try {
            val ref = companyDoc()?.collection("inventory_units")
                ?: return Result.failure(Exception("Not logged in"))
            val agg = ref.whereEqualTo("productId", productId)
                .count().get(AggregateSource.SERVER).await()
            Result.success(agg.count)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Inward (GRN) ───────────────────────────────────────────

    /**
     * Records an inward for one physical unit.
     *
     * Fails if the EPC is already known to this company (prevents accidental
     * double-inwarding of the same tag).
     *
     * Writes to:
     *   /companies/{c}/inventory_units/{epc}  — new unit
     *   /companies/{c}/movements/{auto}        — inward record
     * … as one atomic transaction.
     */
    suspend fun inwardUnit(
        epc: String,
        product: Product,
        serialNumber: String,
        warehouseId: String,
        rackId: String,
        binId: String,
        binCode: String,
        referenceId: String = ""  // GRN number
    ): Result<Unit> {
        return try {
            val company = companyDoc() ?: return Result.failure(Exception("Not logged in"))
            val cleanEpc = epc.trim()
            val unitRef = company.collection("inventory_units").document(cleanEpc)
            val movementRef = company.collection("movements").document()
            val now = Timestamp.now()

            firestore.runTransaction { txn ->
                val existing = txn.get(unitRef)
                if (existing.exists()) {
                    throw Exception("EPC $cleanEpc is already in inventory")
                }

                val unitData = hashMapOf(
                    "epc" to cleanEpc,
                    "productId" to product.id,
                    "sku" to product.sku,
                    "serialNumber" to serialNumber.trim(),
                    "currentWarehouseId" to warehouseId,
                    "currentRackId" to rackId,
                    "currentBinId" to binId,
                    "currentBinCode" to binCode,
                    "status" to "in_stock",
                    "inwardedAt" to now,
                    "lastMovedAt" to now
                )
                txn.set(unitRef, unitData)

                val movementData = hashMapOf(
                    "type" to "inward",
                    "productId" to product.id,
                    "sku" to product.sku,
                    "epc" to cleanEpc,
                    "quantity" to 1,
                    "fromWarehouseId" to "",
                    "fromRackId" to "",
                    "fromBinId" to "",
                    "fromBinCode" to "",
                    "toWarehouseId" to warehouseId,
                    "toRackId" to rackId,
                    "toBinId" to binId,
                    "toBinCode" to binCode,
                    "referenceType" to "grn",
                    "referenceId" to referenceId.trim(),
                    "userId" to userId(),
                    "timestamp" to now,
                    "notes" to ""
                )
                txn.set(movementRef, movementData)
                null
            }.await()

            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Enrollment (shared inward path) ────────────────────────
    //
    // One path for every inward that creates units: scan-based enrollment
    // (sled, desktop reader, fixed reader) and file-based GRN. The writes
    // happen on the server (enrollUnitsV1): it checks the tag, refuses a
    // known EPC, reads the product's SKU and the bin code itself, and writes
    // each unit with its inward movement in one transaction. Validation of
    // required and custom fields is still the caller's job (FieldValidator +
    // CompanyConfig) until that moves to the server too.

    /**
     * Enrolls each tag in [units] as one unit of [context].product.
     *
     * Sent to the server in chunks of up to [SERVER_MAX_UNITS] tags. Each tag
     * succeeds or fails on its own; a chunk that can't reach the server comes
     * back as FAILED for its tags, so earlier chunks' results are never lost
     * and tapping Save again is safe (already-saved tags show as already
     * enrolled). Returns failure only when nothing could be attempted (not
     * signed in, no product or location, batch of another product).
     */
    suspend fun enrollUnits(
        context: EnrollContext,
        units: List<EnrollUnitInput>
    ): Result<EnrollSummary> {
        if (companyId() == null) return Result.failure(Exception("Not logged in"))
        val product = context.product

        if (product.id.isBlank()) return Result.failure(IllegalArgumentException("Pick a product first"))
        if (context.binId.isBlank()) return Result.failure(IllegalArgumentException("Pick a location first"))
        context.batch?.let { batch ->
            if (batch.productId != product.id) {
                return Result.failure(IllegalArgumentException("Batch ${batch.batchNo} belongs to a different product"))
            }
        }
        if (units.isEmpty()) return Result.success(EnrollSummary(emptyList()))

        val results = units.chunked(SERVER_MAX_UNITS).flatMap { chunk ->
            try {
                callEnroll(context, chunk)
            } catch (e: Exception) {
                val message = ServerCalls.message(e)
                chunk.map { EnrollResult(it.epc.trim().uppercase(), EnrollOutcome.FAILED, message) }
            }
        }
        return Result.success(EnrollSummary(results))
    }

    /** One enrollUnitsV1 call (opId and connection retries handled by ServerCalls). */
    private suspend fun callEnroll(context: EnrollContext, chunk: List<EnrollUnitInput>): List<EnrollResult> {
        val request = hashMapOf<String, Any?>(
            "productId" to context.product.id,
            "warehouseId" to context.warehouseId,
            "rackId" to context.rackId,
            "binId" to context.binId,
            "batchId" to (context.batch?.id ?: ""),
            "referenceType" to context.referenceType,
            "referenceId" to context.referenceId.trim(),
            "notes" to context.notes,
            "units" to chunk.map { input ->
                hashMapOf<String, Any>(
                    "epc" to input.epc.trim(),
                    "tid" to input.tid.trim(),
                    "serialNumber" to input.serialNumber.trim(),
                    "customFields" to callableFields(input.customFields)
                ).apply { input.price?.let { put("price", it) } }
            }
        )
        val data = ServerCalls.call("enrollUnitsV1", request, timeoutMinutes = 5)
        val list = data["results"] as? List<*> ?: emptyList<Any>()
        return list.mapNotNull { row ->
            val r = row as? Map<*, *> ?: return@mapNotNull null
            val outcome = runCatching { EnrollOutcome.valueOf(r["outcome"].toString()) }
                .getOrDefault(EnrollOutcome.FAILED)
            EnrollResult(r["epc"]?.toString().orEmpty(), outcome, r["message"]?.toString().orEmpty())
        }
    }

    /** Custom field values the server accepts: text, numbers, yes/no. Blanks dropped. */
    private fun callableFields(fields: Map<String, Any?>): Map<String, Any> =
        fields.entries.mapNotNull { (k, v) ->
            when (v) {
                null -> null
                is String -> v.trim().takeIf { it.isNotEmpty() }?.let { k to it }
                is Number, is Boolean -> k to v
                is Timestamp -> k to ISO_DATE.format(v.toDate())
                else -> v.toString().trim().takeIf { it.isNotEmpty() }?.let { k to it }
            }
        }.toMap()

    // ── Unit details ───────────────────────────────────────────

    /** Every field of a unit (custom fields, batch, sale…), or null if the tag isn't enrolled. */
    suspend fun getUnitFull(epc: String): Result<InventoryUnit?> {
        return try {
            val ref = companyDoc()?.collection("inventory_units")?.document(epc.trim().uppercase())
                ?: return Result.failure(Exception("Not logged in"))
            val doc = ref.get().await()
            if (!doc.exists()) return Result.success(null)
            Result.success(doc.toObject(InventoryUnit::class.java)?.copy(epc = doc.id))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Units with exactly this serial number. Tries as typed, then uppercase. */
    suspend fun findUnitsBySerial(serial: String): Result<List<InventoryUnit>> {
        return try {
            val ref = companyDoc()?.collection("inventory_units")
                ?: return Result.failure(Exception("Not logged in"))
            val typed = serial.trim()
            if (typed.isEmpty()) return Result.success(emptyList())
            val variants = listOf(typed, typed.uppercase()).distinct()
            val units = variants.flatMap { v ->
                ref.whereEqualTo("serialNumber", v).limit(MAX_SERIAL_MATCHES).get().await().documents
            }
                .distinctBy { it.id }
                .mapNotNull { d -> d.toObject(InventoryUnit::class.java)?.copy(epc = d.id) }
            Result.success(units)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Edits a unit's own details. [customFields] is the complete map to store
     * (FieldValidator.validate output, which keeps values of inactive fields).
     * A null [price] removes the price.
     */
    suspend fun updateUnitDetails(
        epc: String,
        serialNumber: String,
        price: Double?,
        customFields: Map<String, Any>
    ): Result<Unit> = runServer {
        ServerCalls.call(
            "updateUnitDetailsV1",
            mapOf(
                "epc" to epc,
                "serialNumber" to serialNumber.trim(),
                "price" to price,
                "customFields" to callableFields(customFields)
            )
        )
        Unit
    }

    /**
     * Changes a unit's status and logs it in the movement ledger, on the
     * server (changeUnitStatusV1, one transaction):
     *
     *   counted → not counted   outward,  quantity -1, from the unit's location
     *   not counted → counted   inward,   quantity +1, into the unit's location
     *   otherwise               status_change, quantity 0 (history only)
     *
     * "Counted" = [STOCK_STATUSES]. The server also enforces the role limits:
     * staff can't mark lost/disposed or edit details.
     */
    suspend fun changeUnitStatus(epc: String, newStatus: UnitStatus, note: String = ""): Result<Unit> {
        if (newStatus.systemOnly) return Result.failure(IllegalStateException("${newStatus.displayName} is set automatically"))
        return runServer {
            ServerCalls.call("changeUnitStatusV1", mapOf("epc" to epc, "status" to newStatus.key, "note" to note.trim()))
            Unit
        }
    }

    /** Checkout: marks an in-stock piece sold (markSoldV1). Returns when it was sold. */
    suspend fun markSold(epc: String): Result<Timestamp> = runServer {
        val reply = ServerCalls.call("markSoldV1", mapOf("epc" to epc))
        val millis = (reply["soldAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        Timestamp(Date(millis))
    }

    private suspend fun <T> runServer(block: suspend () -> T): Result<T> = try {
        if (companyId() == null) Result.failure(Exception("Not logged in")) else Result.success(block())
    } catch (e: Exception) {
        Result.failure(Exception(ServerCalls.message(e), e))
    }

    // ── Dashboard reads ────────────────────────────────────────

    /**
     * Read all SKU aggregates for the company.
     *
     * These are maintained by the updateSkuAggregate Cloud Function — the app
     * only reads. A single query returns everything the by-product dashboard
     * view needs (no per-product lookups required).
     *
     * Returns aggregates sorted by SKU. Zero-quantity aggregates are still
     * included (the function deletes zero entries from byLocation but may
     * leave totalQuantity = 0 entries around — filtering happens in the VM).
     */
    suspend fun getAllSkuAggregates(): Result<List<InventorySkuAggregate>> {
        return try {
            val ref = companyDoc()?.collection("inventory_sku")
                ?: return Result.failure(Exception("Not logged in"))
            val snap = ref.get().await()
            val list = snap.documents.mapNotNull { doc ->
                @Suppress("UNCHECKED_CAST")
                val byLoc = (doc.get("byLocation") as? Map<String, Any>)
                    ?.mapValues { (it.value as? Number)?.toInt() ?: 0 }
                    ?: emptyMap()
                InventorySkuAggregate(
                    productId = doc.getString("productId") ?: doc.id,
                    sku = doc.getString("sku") ?: "",
                    totalQuantity = (doc.getLong("totalQuantity") ?: 0L).toInt(),
                    byLocation = byLoc,
                    lastComputedAt = doc.getTimestamp("lastComputedAt")
                )
            }.sortedBy { it.sku }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }
    /**
     * Look up a single inventory unit by its EPC. Returns Result.success(null)
     * if no document exists for that EPC. Used by Cycle Count's Additional Tags
     * tab to show where an unexpected tag actually lives.
     */
    suspend fun getUnitByEpc(epc: String): Result<InventoryUnit?> {
        return try {
            val ref = companyDoc()?.collection("inventory_units")?.document(epc.trim().uppercase())
                ?: return Result.failure(Exception("Not logged in"))
            val doc = ref.get().await()
            if (!doc.exists()) return Result.success(null)
            val unit = InventoryUnit(
                epc = doc.id,
                productId = doc.getString("productId") ?: "",
                sku = doc.getString("sku") ?: "",
                serialNumber = doc.getString("serialNumber") ?: "",
                currentWarehouseId = doc.getString("currentWarehouseId") ?: "",
                currentRackId = doc.getString("currentRackId") ?: "",
                currentBinId = doc.getString("currentBinId") ?: "",
                currentBinCode = doc.getString("currentBinCode") ?: "",
                status = doc.getString("status") ?: "in_stock",
                inwardedAt = doc.getTimestamp("inwardedAt"),
                lastMovedAt = doc.getTimestamp("lastMovedAt"),
                dispatchedAt = doc.getTimestamp("dispatchedAt")
            )
            Result.success(unit)
        } catch (e: Exception) { Result.failure(e) }
    }
    /**
     * All units currently in a specific bin. Used by the by-location
     * dashboard view — pick a bin, see what's in it.
     *
     * Filters by currentBinId (unique per bin), not currentBinCode, since
     * bin codes are meant to be unique but the ID is the guaranteed one.
     */
    suspend fun getUnitsInBin(binId: String): Result<List<InventoryUnit>> {
        return try {
            val ref = companyDoc()?.collection("inventory_units")
                ?: return Result.failure(Exception("Not logged in"))
            val snap = ref.whereEqualTo("currentBinId", binId).get().await()
            val list = snap.documents.mapNotNull { doc ->
                InventoryUnit(
                    epc = doc.id,
                    productId = doc.getString("productId") ?: "",
                    sku = doc.getString("sku") ?: "",
                    serialNumber = doc.getString("serialNumber") ?: "",
                    currentWarehouseId = doc.getString("currentWarehouseId") ?: "",
                    currentRackId = doc.getString("currentRackId") ?: "",
                    currentBinId = doc.getString("currentBinId") ?: "",
                    currentBinCode = doc.getString("currentBinCode") ?: "",
                    status = doc.getString("status") ?: "in_stock",
                    inwardedAt = doc.getTimestamp("inwardedAt"),
                    lastMovedAt = doc.getTimestamp("lastMovedAt"),
                    dispatchedAt = doc.getTimestamp("dispatchedAt")
                )
            }.sortedBy { it.sku }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }
}

/** Same limit as enrollUnitsV1 on the server. */
private const val SERVER_MAX_UNITS = 500

/** Custom date fields are stored as yyyy-MM-dd text. */
private val ISO_DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private const val MAX_SERIAL_MATCHES = 20L

/** Statuses that count as stock on hand. Shared rule for status changes and (Phase 5) auto-expire. */
val STOCK_STATUSES: Set<UnitStatus> = setOf(UnitStatus.IN_STOCK, UnitStatus.PICKED)
/**
 * Same rule as the readers: hex, whole 16-bit words, 64–496 bits
 * (see [com.snainfotech.tagscout.sdk.RfidConstants.isValidEpc]). Also rejects
 * keyboard-wedge noise and anything unsafe as a doc id.
 */
fun isValidEpc(epc: String): Boolean = com.snainfotech.tagscout.sdk.RfidConstants.isValidEpc(epc)

/** What every unit in one enrollment shares. */
data class EnrollContext(
    val product: Product,
    val warehouseId: String,
    val rackId: String,
    val binId: String,
    val binCode: String,
    /** Resolved via BatchRepository.findOrCreate when the type has batch tracking; null otherwise. */
    val batch: Batch? = null,
    /** "enrollment" for scan-based, "grn" for file-based. */
    val referenceType: String = "enrollment",
    val referenceId: String = "",
    val notes: String = "",
    /** Blank = the signed-in user; "import" for Excel-imported units. */
    val enrolledBy: String = ""
)

/** Per-tag values. Unit-level custom fields are keyed by FieldDef.key. */
data class EnrollUnitInput(
    val epc: String,
    val tid: String = "",
    val serialNumber: String = "",
    val price: Double? = null,
    val customFields: Map<String, Any?> = emptyMap()
)

enum class EnrollOutcome { ENROLLED, ALREADY_ENROLLED, DUPLICATE_IN_LIST, INVALID_EPC, FAILED }

data class EnrollResult(
    val epc: String,
    val outcome: EnrollOutcome,
    val message: String = ""
) {
    val ok: Boolean get() = outcome == EnrollOutcome.ENROLLED
}

data class EnrollSummary(val results: List<EnrollResult>) {
    val enrolled: Int get() = results.count { it.ok }
    val skipped: Int get() = results.size - enrolled
    val failures: List<EnrollResult> get() = results.filterNot { it.ok }
}
