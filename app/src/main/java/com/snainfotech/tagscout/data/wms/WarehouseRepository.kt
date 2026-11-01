package com.snainfotech.tagscout.data.wms

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
import com.snainfotech.tagscout.data.server.ServerCalls
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * Reads/writes the warehouse → rack → bin structure.
 *
 * All operations scope under the current user's companyId (= userId for now).
 * When Feature 1 (admin) lands, the companyId lookup will resolve via the
 * user's profile instead of being hard-coded to their uid.
 *
 * The optional [inventoryRepository] is used by the delete methods to check
 * that no inventory units exist under the entity being deleted. It defaults
 * to a fresh InventoryRepository() so existing no-arg callers keep working.
 *
 * Uniqueness rules enforced by this repository (all case-insensitive,
 * trim-tolerant):
 *   - Warehouse name: unique within the company
 *   - Rack name:      unique within its parent warehouse
 *   - Bin code:       unique across the entire company (bin codes must
 *                     match printed barcodes and are looked up globally)
 *
 * Writes (create / update / delete) run on the server in locationsV1, which
 * enforces these rules and the "no units stored here" delete guard.
 */
class WarehouseRepository(
    private val inventoryRepository: InventoryRepository = InventoryRepository()
) {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /**
     * For now, one user = one company. Later: read from user profile.
     *
     * Uses AuthReady.awaitCompanyId() rather than auth.currentUser?.uid directly
     * to avoid a startup race: FirebaseAuth restores its session lazily on
     * app start, and code that runs immediately after screen entry (like
     * ViewModel init blocks) can see a null currentUser even when a valid
     * session is on disk. AuthReady waits for the first IdTokenListener
     * fire (usually <1 second) before returning.
     */
    private suspend fun companyId(): String? = AuthReady.awaitCompanyId()

    private suspend fun companyDoc() = companyId()?.let {
        firestore.collection("companies").document(it)
    }

    // ── Writes go through the server ───────────────────────────
    //
    // locationsV1 (functions/src/locations.ts) checks the names and codes,
    // refuses deletes while units are stored, cascades deletes, and allows
    // owners and managers only. Returns the document id it created or changed.

    private suspend fun location(action: String, vararg fields: Pair<String, String>): Result<String> = try {
        if (companyId() == null) {
            Result.failure(Exception("Not logged in"))
        } else {
            val reply = ServerCalls.call("locationsV1", mapOf("action" to action) + fields.map { (k, v) -> k to v.trim() })
            Result.success(reply["id"]?.toString().orEmpty())
        }
    } catch (e: Exception) {
        Result.failure(Exception(ServerCalls.message(e), e))
    }

    // ── Warehouses ─────────────────────────────────────────────

    suspend fun getWarehouses(): Result<List<Warehouse>> {
        return try {
            val ref = companyDoc()?.collection("warehouses")
                ?: return Result.failure(Exception("Not logged in"))
            val snap = ref.get().await()
            val list = snap.documents.mapNotNull { doc ->
                Warehouse(
                    id = doc.id,
                    name = doc.getString("name") ?: "",
                    address = doc.getString("address") ?: "",
                    createdAt = doc.getTimestamp("createdAt"),
                    updatedAt = doc.getTimestamp("updatedAt")
                )
            }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun createWarehouse(name: String, address: String): Result<String> =
        location("createWarehouse", "name" to name, "address" to address)

    /**
     * Update a warehouse's display fields (name, address).
     *
     * The document ID is immutable by design — renaming a warehouse doesn't
     * require touching any child documents or inventory records.
     */
    suspend fun updateWarehouse(
        warehouseId: String,
        name: String,
        address: String
    ): Result<Unit> =
        location("updateWarehouse", "warehouseId" to warehouseId, "name" to name, "address" to address).map { }

    /**
     * Delete a warehouse and everything under it (cascade racks + bins).
     *
     * BLOCKED if any inventory unit currently sits anywhere under the warehouse.
     * If empty, cascades: bins → racks → warehouse in one atomic batch.
     *
     * Movement history for this warehouse is left in place as audit trail —
     * historical records may point to a warehouse ID that no longer exists.
     */
    suspend fun deleteWarehouse(warehouseId: String): Result<Unit> =
        location("deleteWarehouse", "warehouseId" to warehouseId).map { }

    // ── Racks ──────────────────────────────────────────────────

    suspend fun getRacks(warehouseId: String): Result<List<Rack>> {
        return try {
            val ref = companyDoc()
                ?.collection("warehouses")?.document(warehouseId)
                ?.collection("racks")
                ?: return Result.failure(Exception("Not logged in"))
            val snap = ref.get().await()
            val list = snap.documents.mapNotNull { doc ->
                Rack(
                    id = doc.id,
                    name = doc.getString("name") ?: "",
                    warehouseId = warehouseId,
                    createdAt = doc.getTimestamp("createdAt")
                )
            }.sortedBy { it.name }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun createRack(warehouseId: String, name: String): Result<String> =
        location("createRack", "warehouseId" to warehouseId, "name" to name)

    /** Rename a rack. Rack ID stays fixed. */
    suspend fun updateRack(
        warehouseId: String,
        rackId: String,
        name: String
    ): Result<Unit> =
        location("updateRack", "warehouseId" to warehouseId, "rackId" to rackId, "name" to name).map { }

    /**
     * Delete a rack and every bin under it.
     *
     * BLOCKED if any inventory unit sits under this rack.
     * If empty, cascades bins → rack in one atomic batch.
     */
    suspend fun deleteRack(warehouseId: String, rackId: String): Result<Unit> =
        location("deleteRack", "warehouseId" to warehouseId, "rackId" to rackId).map { }

    // ── Bins ───────────────────────────────────────────────────

    suspend fun getBins(warehouseId: String, rackId: String): Result<List<Bin>> {
        return try {
            val ref = companyDoc()
                ?.collection("warehouses")?.document(warehouseId)
                ?.collection("racks")?.document(rackId)
                ?.collection("bins")
                ?: return Result.failure(Exception("Not logged in"))
            val snap = ref.get().await()
            val list = snap.documents.mapNotNull { doc ->
                Bin(
                    id = doc.id,
                    binCode = doc.getString("binCode") ?: "",
                    name = doc.getString("name") ?: "",
                    rackId = rackId,
                    warehouseId = warehouseId,
                    createdAt = doc.getTimestamp("createdAt")
                )
            }.sortedBy { it.binCode }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun createBin(
        warehouseId: String, rackId: String,
        binCode: String, name: String
    ): Result<String> =
        location("createBin", "warehouseId" to warehouseId, "rackId" to rackId, "binCode" to binCode, "name" to name)

    /**
     * Update a bin's display name only.
     *
     * binCode is intentionally immutable — it's the human-readable identifier
     * printed on physical bin labels and denormalized into every InventoryUnit
     * (currentBinCode) and Movement (fromBinCode/toBinCode) that touches this
     * bin. Renaming the code would require a bulk rewrite across those
     * collections; if a bin code is genuinely wrong, delete-and-recreate the
     * empty bin instead. A proper rename workflow may come in a later version.
     *
     * No uniqueness check is needed here — the mutable field (name) is not
     * required to be unique.
     */
    suspend fun updateBin(
        warehouseId: String,
        rackId: String,
        binId: String,
        name: String
    ): Result<Unit> =
        location("updateBin", "warehouseId" to warehouseId, "rackId" to rackId, "binId" to binId, "name" to name).map { }

    /**
     * Delete a bin.
     *
     * BLOCKED if any inventory unit currently has currentBinId = this bin.
     * Movement history is left in place as audit trail.
     */
    suspend fun deleteBin(
        warehouseId: String,
        rackId: String,
        binId: String
    ): Result<Unit> =
        location("deleteBin", "warehouseId" to warehouseId, "rackId" to rackId, "binId" to binId).map { }

    /**
     * Find a bin by its printed barcode value. Used by GRN validation to
     * resolve bin codes from the Excel file, and (in future) when a user
     * scans a bin barcode to identify their location.
     *
     * Implementation: walks warehouses → racks → bins scoped to this company.
     * Uses only path-scoped queries (no collection-group queries), which
     * plays nicely with per-company Firestore security rules and avoids
     * needing a special allow rule for collection-group access.
     *
     * NOTE: this lookup is currently case-sensitive by design — GRN Excel
     * uploads are expected to match bin codes exactly. The write-time
     * uniqueness check (in locationsV1 on the server) is case-insensitive to prevent
     * confusingly-similar bin codes from being created in the first place.
     */
    suspend fun findBinByCode(binCode: String): Result<Bin?> {
        return try {
            val company = companyDoc() ?: return Result.failure(Exception("Not logged in"))
            val target = binCode.trim()

            val warehousesSnap = company.collection("warehouses").get().await()
            for (whDoc in warehousesSnap.documents) {
                val racksSnap = whDoc.reference.collection("racks").get().await()
                for (rackDoc in racksSnap.documents) {
                    val binsSnap = rackDoc.reference.collection("bins")
                        .whereEqualTo("binCode", target)
                        .limit(1)
                        .get().await()
                    val hit = binsSnap.documents.firstOrNull()
                    if (hit != null) {
                        return Result.success(Bin(
                            id = hit.id,
                            binCode = hit.getString("binCode") ?: "",
                            name = hit.getString("name") ?: "",
                            rackId = hit.getString("rackId") ?: "",
                            warehouseId = hit.getString("warehouseId") ?: "",
                            createdAt = hit.getTimestamp("createdAt")
                        ))
                    }
                }
            }
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Bulk structure read ───────────────────────────────────
    //
    // Loads the entire warehouse → rack → bin tree in three parallel waves
    // instead of one call-per-selection. Used by screens that need to show
    // dropdowns of the whole structure (Cycle Count pick-bin, WMS Dashboard
    // by-location) — load once on screen open, filter in memory from there.
    //
    // Cost: 1 warehouse read + N parallel rack reads + M parallel bin reads,
    // where N = warehouses and M = total racks across the company. For the
    // SME customers we target this is a handful of reads; for a very large
    // customer the aggregates-based approach in InventoryRepository is a
    // better fit for the "which bins are non-empty" question.

    /** All warehouses + all racks + all bins for the current company. */
    data class FullStructure(
        val warehouses: List<Warehouse>,
        val racks: List<Rack>,
        val bins: List<Bin>
    )

    suspend fun getFullStructure(): Result<FullStructure> {
        return try {
            val warehouses = getWarehouses().getOrElse { return Result.failure(it) }
            if (warehouses.isEmpty()) {
                return Result.success(FullStructure(emptyList(), emptyList(), emptyList()))
            }

            coroutineScope {
                // Fan out one rack-read per warehouse, wait for all.
                val racks = warehouses
                    .map { wh -> async { getRacks(wh.id).getOrElse { emptyList() } } }
                    .awaitAll()
                    .flatten()

                // Fan out one bin-read per rack, wait for all.
                val bins = racks
                    .map { rack -> async { getBins(rack.warehouseId, rack.id).getOrElse { emptyList() } } }
                    .awaitAll()
                    .flatten()

                Result.success(FullStructure(warehouses, racks, bins))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}