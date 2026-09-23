package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
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
 * These checks are advisory in single-user v1 — same race caveat as the
 * inventory-guard in the delete methods. When Feature 1 lands and multiple
 * users share a companyId, uniqueness should move into a Cloud Function or
 * transaction with server-side lookups.
 */
class WarehouseRepository(
    private val inventoryRepository: InventoryRepository = InventoryRepository()
) {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /**
     * For now, one user = one company. Later: read from user profile.
     *
     * Uses AuthReady.awaitUid() rather than auth.currentUser?.uid directly
     * to avoid a startup race: FirebaseAuth restores its session lazily on
     * app start, and code that runs immediately after screen entry (like
     * ViewModel init blocks) can see a null currentUser even when a valid
     * session is on disk. AuthReady waits for the first IdTokenListener
     * fire (usually <1 second) before returning.
     */
    private suspend fun companyId(): String? = AuthReady.awaitUid()

    private suspend fun companyDoc() = companyId()?.let {
        firestore.collection("companies").document(it)
    }

    // ── Uniqueness helpers ────────────────────────────────────
    //
    // All comparisons are case-insensitive and trim leading/trailing
    // whitespace. Original casing is preserved when documents are written.

    /**
     * Whether another warehouse in this company shares the given name.
     * Pass excludeId when checking during an update so the entity being
     * renamed does not collide with itself.
     */
    private suspend fun warehouseNameExists(
        name: String,
        excludeId: String? = null
    ): Result<Boolean> {
        return try {
            val ref = companyDoc()?.collection("warehouses")
                ?: return Result.failure(Exception("Not logged in"))
            val target = name.trim()
            val snap = ref.get().await()
            val hit = snap.documents.any { doc ->
                val existing = doc.getString("name")?.trim().orEmpty()
                existing.equals(target, ignoreCase = true) && doc.id != excludeId
            }
            Result.success(hit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Whether another rack in the same warehouse shares the given name.
     */
    private suspend fun rackNameExistsInWarehouse(
        warehouseId: String,
        name: String,
        excludeId: String? = null
    ): Result<Boolean> {
        return try {
            val ref = companyDoc()
                ?.collection("warehouses")?.document(warehouseId)
                ?.collection("racks")
                ?: return Result.failure(Exception("Not logged in"))
            val target = name.trim()
            val snap = ref.get().await()
            val hit = snap.documents.any { doc ->
                val existing = doc.getString("name")?.trim().orEmpty()
                existing.equals(target, ignoreCase = true) && doc.id != excludeId
            }
            Result.success(hit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Location of an existing bin found during a collision check. Used to
     * build an error message that tells the user WHERE the duplicate lives
     * so they can find it — bin codes are unique across the whole company,
     * so the collision may be in a warehouse the user isn't currently
     * looking at.
     */
    private data class ExistingBinLocation(
        val warehouseName: String,
        val rackName: String
    )

    /**
     * Walks warehouses → racks → bins across the whole company looking for a
     * bin whose code matches [binCode] case-insensitively. Returns where it
     * lives, or null if no collision.
     *
     * Uses the same path-walk pattern as findBinByCode (no collection-group
     * queries, so no special security rules needed). Cheap at v1 scale.
     */
    private suspend fun findBinCodeCollision(
        binCode: String,
        excludeBinId: String? = null
    ): Result<ExistingBinLocation?> {
        return try {
            val company = companyDoc()
                ?: return Result.failure(Exception("Not logged in"))
            val target = binCode.trim()

            val warehousesSnap = company.collection("warehouses").get().await()
            for (whDoc in warehousesSnap.documents) {
                val racksSnap = whDoc.reference.collection("racks").get().await()
                for (rackDoc in racksSnap.documents) {
                    val binsSnap = rackDoc.reference.collection("bins").get().await()
                    val hit = binsSnap.documents.firstOrNull { binDoc ->
                        val existing = binDoc.getString("binCode")?.trim().orEmpty()
                        existing.equals(target, ignoreCase = true) && binDoc.id != excludeBinId
                    }
                    if (hit != null) {
                        return Result.success(
                            ExistingBinLocation(
                                warehouseName = whDoc.getString("name").orEmpty(),
                                rackName = rackDoc.getString("name").orEmpty()
                            )
                        )
                    }
                }
            }
            Result.success(null)
        } catch (e: Exception) { Result.failure(e) }
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

    suspend fun createWarehouse(name: String, address: String): Result<String> {
        return try {
            val nameExists = warehouseNameExists(name)
                .getOrElse { return Result.failure(it) }
            if (nameExists) {
                return Result.failure(Exception(
                    "A warehouse named '${name.trim()}' already exists"
                ))
            }

            val ref = companyDoc()?.collection("warehouses")
                ?: return Result.failure(Exception("Not logged in"))
            val data = hashMapOf(
                "name" to name.trim(),
                "address" to address.trim(),
                "createdAt" to Timestamp.now(),
                "updatedAt" to Timestamp.now()
            )
            val doc = ref.add(data).await()
            Result.success(doc.id)
        } catch (e: Exception) { Result.failure(e) }
    }

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
    ): Result<Unit> {
        return try {
            val nameExists = warehouseNameExists(name, excludeId = warehouseId)
                .getOrElse { return Result.failure(it) }
            if (nameExists) {
                return Result.failure(Exception(
                    "A warehouse named '${name.trim()}' already exists"
                ))
            }

            val ref = companyDoc()?.collection("warehouses")?.document(warehouseId)
                ?: return Result.failure(Exception("Not logged in"))
            val data = hashMapOf<String, Any>(
                "name" to name.trim(),
                "address" to address.trim(),
                "updatedAt" to Timestamp.now()
            )
            ref.update(data).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Delete a warehouse and everything under it (cascade racks + bins).
     *
     * BLOCKED if any inventory unit currently sits anywhere under the warehouse.
     * If empty, cascades: bins → racks → warehouse in one atomic batch.
     *
     * Movement history for this warehouse is left in place as audit trail —
     * historical records may point to a warehouse ID that no longer exists.
     */
    suspend fun deleteWarehouse(warehouseId: String): Result<Unit> {
        return try {
            val units = inventoryRepository.countUnitsInWarehouse(warehouseId)
                .getOrElse { return Result.failure(it) }
            if (units > 0L) {
                return Result.failure(Exception(
                    "Cannot delete warehouse — $units unit(s) still stored under it. Move or dispatch them first."
                ))
            }

            val company = companyDoc()
                ?: return Result.failure(Exception("Not logged in"))
            val warehouseRef = company.collection("warehouses").document(warehouseId)

            // Collect every rack and bin under this warehouse.
            val batch = firestore.batch()
            val racksSnap = warehouseRef.collection("racks").get().await()
            for (rackDoc in racksSnap.documents) {
                val binsSnap = rackDoc.reference.collection("bins").get().await()
                for (binDoc in binsSnap.documents) {
                    batch.delete(binDoc.reference)
                }
                batch.delete(rackDoc.reference)
            }
            batch.delete(warehouseRef)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

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

    suspend fun createRack(warehouseId: String, name: String): Result<String> {
        return try {
            val nameExists = rackNameExistsInWarehouse(warehouseId, name)
                .getOrElse { return Result.failure(it) }
            if (nameExists) {
                return Result.failure(Exception(
                    "A rack named '${name.trim()}' already exists in this warehouse"
                ))
            }

            val ref = companyDoc()
                ?.collection("warehouses")?.document(warehouseId)
                ?.collection("racks")
                ?: return Result.failure(Exception("Not logged in"))
            val data = hashMapOf(
                "name" to name.trim(),
                "warehouseId" to warehouseId,
                "createdAt" to Timestamp.now()
            )
            val doc = ref.add(data).await()
            Result.success(doc.id)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Rename a rack. Rack ID stays fixed. */
    suspend fun updateRack(
        warehouseId: String,
        rackId: String,
        name: String
    ): Result<Unit> {
        return try {
            val nameExists = rackNameExistsInWarehouse(warehouseId, name, excludeId = rackId)
                .getOrElse { return Result.failure(it) }
            if (nameExists) {
                return Result.failure(Exception(
                    "A rack named '${name.trim()}' already exists in this warehouse"
                ))
            }

            val ref = companyDoc()
                ?.collection("warehouses")?.document(warehouseId)
                ?.collection("racks")?.document(rackId)
                ?: return Result.failure(Exception("Not logged in"))
            ref.update(mapOf<String, Any>("name" to name.trim())).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Delete a rack and every bin under it.
     *
     * BLOCKED if any inventory unit sits under this rack.
     * If empty, cascades bins → rack in one atomic batch.
     */
    suspend fun deleteRack(warehouseId: String, rackId: String): Result<Unit> {
        return try {
            val units = inventoryRepository.countUnitsInRack(rackId)
                .getOrElse { return Result.failure(it) }
            if (units > 0L) {
                return Result.failure(Exception(
                    "Cannot delete rack — $units unit(s) still stored under it. Move or dispatch them first."
                ))
            }

            val rackRef = companyDoc()
                ?.collection("warehouses")?.document(warehouseId)
                ?.collection("racks")?.document(rackId)
                ?: return Result.failure(Exception("Not logged in"))

            val batch = firestore.batch()
            val binsSnap = rackRef.collection("bins").get().await()
            for (binDoc in binsSnap.documents) {
                batch.delete(binDoc.reference)
            }
            batch.delete(rackRef)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

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
    ): Result<String> {
        return try {
            val collision = findBinCodeCollision(binCode)
                .getOrElse { return Result.failure(it) }
            if (collision != null) {
                return Result.failure(Exception(
                    "A bin with code '${binCode.trim()}' already exists " +
                            "(in ${collision.warehouseName} / ${collision.rackName})"
                ))
            }

            val ref = companyDoc()
                ?.collection("warehouses")?.document(warehouseId)
                ?.collection("racks")?.document(rackId)
                ?.collection("bins")
                ?: return Result.failure(Exception("Not logged in"))
            val data = hashMapOf(
                "binCode" to binCode.trim(),
                "name" to name.trim().ifBlank { binCode.trim() },
                "rackId" to rackId,
                "warehouseId" to warehouseId,
                "createdAt" to Timestamp.now()
            )
            val doc = ref.add(data).await()
            Result.success(doc.id)
        } catch (e: Exception) { Result.failure(e) }
    }

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
    ): Result<Unit> {
        return try {
            val ref = companyDoc()
                ?.collection("warehouses")?.document(warehouseId)
                ?.collection("racks")?.document(rackId)
                ?.collection("bins")?.document(binId)
                ?: return Result.failure(Exception("Not logged in"))
            ref.update(mapOf<String, Any>("name" to name.trim())).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

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
    ): Result<Unit> {
        return try {
            val units = inventoryRepository.countUnitsInBin(binId)
                .getOrElse { return Result.failure(it) }
            if (units > 0L) {
                return Result.failure(Exception(
                    "Cannot delete bin — $units unit(s) still stored here. Move or dispatch them first."
                ))
            }

            val ref = companyDoc()
                ?.collection("warehouses")?.document(warehouseId)
                ?.collection("racks")?.document(rackId)
                ?.collection("bins")?.document(binId)
                ?: return Result.failure(Exception("Not logged in"))
            ref.delete().await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

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
     * uniqueness check (findBinCodeCollision) is case-insensitive to prevent
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
}