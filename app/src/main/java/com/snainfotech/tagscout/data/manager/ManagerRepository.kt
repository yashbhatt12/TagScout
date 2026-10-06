package com.snainfotech.tagscout.data.manager

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.snainfotech.tagscout.data.auth.AuthReady
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Reads the alerts collection in realtime and lets the manager mark alerts
 * as read.
 *
 * Alerts are written by the `alarmNotifier` Cloud Function; this repository
 * never creates them — it only observes and updates `read`. The alerts
 * collection is append-only from the write side (we never delete alerts;
 * they're audit records), but mark-read is an in-place update.
 *
 * Design notes:
 *   - [observeAlerts] returns a cold Flow — Firestore's realtime listener
 *     is attached when a collector subscribes and detached when the collector
 *     cancels. One listener per active screen; no leaks when the Manager
 *     screen is backgrounded or recreated.
 *   - Ordering is server-side DESC by createdAt so the newest alerts are
 *     always at the top of the stream.
 *   - A 100-doc limit guards against runaway Firestore reads if the alerts
 *     collection grows large. We'll add paging in a later version if needed.
 */
class ManagerRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /** Blocks until Firebase Auth has settled; see AuthReady for details. */
    private suspend fun companyId(): String? = AuthReady.awaitUid()

    /** Current user id for stamping into audit fields; see JewelleryCheckoutViewModel. */
    private fun currentUserId(): String = auth.currentUser?.uid ?: ""

    /**
     * Observe the alerts collection in realtime.
     *
     * The returned Flow emits the full current list every time anything
     * changes in Firestore — a new alert lands, an existing one is marked
     * read, etc. Emissions are already ordered newest-first.
     *
     * If the user is not signed in, the flow completes empty rather than
     * throwing — the caller (ViewModel) typically shows a "please sign in"
     * state and doesn't need to distinguish "empty" from "not authorised".
     *
     * Firestore handles its own reconnection; the listener survives WiFi
     * drops and auth-token refreshes. We only remove it on cancellation.
     */
    fun observeAlerts(limit: Long = 100): Flow<List<Alert>> = callbackFlow {
        val uid = companyId()
        if (uid == null) {
            // Not signed in — complete empty rather than throw.
            close()
            return@callbackFlow
        }

        val registration = firestore
            .collection("companies").document(uid)
            .collection("alerts")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Firestore will retry transient errors on its own.
                    // Close the Flow with the error so the ViewModel can show it.
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val alerts = snapshot.documents.map { doc ->
                    Alert(
                        id = doc.id,
                        epc = doc.getString("epc") ?: "",
                        itemCode = doc.getString("itemCode"),
                        category = doc.getString("category"),
                        price = doc.getDouble("price"),
                        readerId = doc.getString("readerId"),
                        antennaPort = doc.getLong("antennaPort")?.toInt(),
                        rssi = doc.getLong("rssi")?.toInt(),
                        firstSeenAt = doc.getLong("firstSeenAt"),
                        sourceEventId = doc.getString("sourceEventId") ?: "",
                        severity = doc.getString("severity") ?: "high",
                        read = doc.getBoolean("read") ?: false,
                        createdAt = doc.getTimestamp("createdAt")
                    )
                }
                trySend(alerts)
            }

        awaitClose { registration.remove() }
    }

    /**
     * Mark a single alert as read. In-place update — never deletes.
     *
     * Also stamps when it was read and by whom, for audit. If multiple
     * managers are reviewing alerts, the UI treats "read by anyone" as read
     * everywhere — matches a shop's realistic workflow where any team member
     * acknowledging the alarm counts.
     */
    suspend fun markRead(alertId: String): Result<Unit> {
        val uid = companyId() ?: return Result.failure(
            IllegalStateException("Not signed in")
        )
        return try {
            firestore
                .collection("companies").document(uid)
                .collection("alerts").document(alertId)
                .update(
                    mapOf(
                        "read" to true,
                        "readAt" to Timestamp.now(),
                        "readBy" to currentUserId()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Mark every currently-unread alert as read in one batch.
     *
     * Useful "clear all" action on the Manager screen. Scoped to the alerts
     * currently in the user's view (we fetch unread first, batch-update them).
     * If the alerts collection has more than 500 unread docs — the Firestore
     * batch limit — we only clear the first 500 and the user taps again.
     * In practice an alert backlog that large means something else is wrong.
     */
    suspend fun markAllRead(): Result<Int> {
        val uid = companyId() ?: return Result.failure(
            IllegalStateException("Not signed in")
        )
        return try {
            val unreadDocs = firestore
                .collection("companies").document(uid)
                .collection("alerts")
                .whereEqualTo("read", false)
                .limit(500)
                .get()
                .await()

            if (unreadDocs.isEmpty) return Result.success(0)

            val now = Timestamp.now()
            val userId = currentUserId()
            val batch = firestore.batch()
            for (doc in unreadDocs.documents) {
                batch.update(
                    doc.reference,
                    mapOf(
                        "read" to true,
                        "readAt" to now,
                        "readBy" to userId
                    )
                )
            }
            batch.commit().await()
            Result.success(unreadDocs.size())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}