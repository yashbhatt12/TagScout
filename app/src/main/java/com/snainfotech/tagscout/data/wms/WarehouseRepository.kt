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
 */
class WarehouseRepository {

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
     * Find a bin by its printed barcode value. Used when a user scans a bin
     * label to identify their current location.
     *
     * Uses a collection group query so it finds the bin regardless of which
     * rack it lives under — the barcode alone is enough.
     */
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
     * At v1 scale (single-digit warehouses, low-double-digit racks each),
     * the walk is trivially cheap — a handful of small reads.
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