package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

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

    private fun companyId(): String? = auth.currentUser?.uid
    private fun userId(): String = auth.currentUser?.uid ?: ""

    private fun companyDoc() = companyId()?.let {
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
}