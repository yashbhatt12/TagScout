package com.snainfotech.tagscout.data.wms

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
import com.snainfotech.tagscout.data.server.ServerCalls
import kotlinx.coroutines.tasks.await
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * One batch / lot of one product.
 *
 *   /companies/{cid}/batches/{batchId}
 *
 * batchId is deterministic: "<productId>__<url-encoded lowercase batchNo>",
 * so a batch number is unique per product (case-insensitive) and
 * find-or-create is a single transactional read, with no query or index.
 *
 * mfgDate / expiryDate are "YYYY-MM-DD" and immutable once the batch exists
 * (also enforced by firestore.rules). qtyInStock is maintained by the
 * aggregate Cloud Function from movements carrying batchId; the app never
 * writes it after creation.
 *
 * Status is NOT copied onto units (it changes); dispatch and checkout read
 * the batch doc. Dates ARE copied onto units, because FEFO needs to sort
 * units by expiryDate and Firestore can't join.
 */
data class Batch(
    val id: String = "",
    val productId: String = "",
    val sku: String = "",
    val batchNo: String = "",
    val batchKey: String = "",          // lowercase trimmed batchNo
    val mfgDate: String = "",           // "YYYY-MM-DD" or ""
    val expiryDate: String = "",        // "YYYY-MM-DD" or ""
    val status: String = BatchStatus.ACTIVE.key,
    val qtyInStock: Int = 0,
    val createdAt: Timestamp? = null,
    val createdBy: String = "",
    val updatedAt: Timestamp? = null,
    val updatedBy: String = ""
)

/** [systemOnly] statuses are set by the nightly function, never picked by hand. */
enum class BatchStatus(val key: String, val displayName: String, val systemOnly: Boolean = false) {
    ACTIVE("active", "Active"),
    QUARANTINED("quarantined", "Quarantined"),
    RECALLED("recalled", "Recalled"),
    EXPIRED("expired", "Expired", systemOnly = true);

    /** Whether units of a batch in this status may be dispatched or sold. */
    val allowsOutward: Boolean get() = this == ACTIVE

    companion object {
        fun fromKey(key: String?): BatchStatus? =
            BatchStatus.entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

// Extension functions (not properties) so Firestore doesn't serialize them.
fun Batch.batchStatus(): BatchStatus = BatchStatus.fromKey(status) ?: BatchStatus.ACTIVE

/** True once expiryDate is before [today]. Blank expiry never expires. */
fun Batch.isPastExpiry(today: LocalDate = LocalDate.now()): Boolean =
    BatchDates.parse(expiryDate)?.isBefore(today) ?: false

/** Thrown when an existing batch is entered again with different dates. */
class BatchDateMismatchException(
    val batchNo: String,
    val field: String,
    val existing: String,
    val entered: String
) : IllegalStateException(
    "Batch $batchNo already exists with $field " +
            "${existing.ifBlank { "not set" }}. Dates can't change once a batch exists " +
            "(you entered ${entered.ifBlank { "nothing" }})."
)

/** Strict "YYYY-MM-DD" handling; day-first input is converted by FieldValidator before it gets here. */
object BatchDates {
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun parse(value: String?): LocalDate? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        return try { LocalDate.parse(v, ISO) } catch (_: DateTimeParseException) { null }
    }

    fun format(date: LocalDate): String = date.format(ISO)

    /** Null when valid; otherwise a user-facing message. Blank dates are allowed here (required-ness is checked by the caller). */
    fun validate(mfgDate: String, expiryDate: String): String? {
        val mfg = mfgDate.trim()
        val exp = expiryDate.trim()
        val m = parse(mfg)
        val e = parse(exp)
        if (mfg.isNotEmpty() && m == null) return "Manufacturing date must be YYYY-MM-DD"
        if (exp.isNotEmpty() && e == null) return "Expiry date must be YYYY-MM-DD"
        if (m != null && m.isAfter(LocalDate.now())) return "Manufacturing date can't be in the future"
        if (m != null && e != null && !e.isAfter(m)) return "Expiry date must be after manufacturing date"
        return null
    }
}

class BatchRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private suspend fun companyId(): String? = AuthReady.awaitCompanyId()

    private suspend fun batchesRef(): CollectionReference? =
        companyId()?.let { firestore.collection("companies").document(it).collection(COLLECTION) }

    companion object {
        private const val TAG = "BatchRepository"
        private const val COLLECTION = "batches"
        const val BATCH_NO_MAX = 40

        fun batchKeyOf(batchNo: String): String = batchNo.trim().lowercase()

        /** Deterministic id; productId is a Firestore auto-id so it's path-safe. */
        fun batchIdOf(productId: String, batchNo: String): String =
            "${productId}__" + URLEncoder.encode(batchKeyOf(batchNo), "UTF-8")

        private fun fromSnapshot(doc: DocumentSnapshot): Batch? =
            doc.toObject(Batch::class.java)?.copy(id = doc.id)
    }

    // ── Reads ────────────────────────────────────────────────────────────

    suspend fun getBatch(batchId: String): Result<Batch?> = runCatching {
        val ref = batchesRef() ?: error("Not signed in")
        fromSnapshot(ref.document(batchId).get().await())
    }.onFailure { Log.e(TAG, "getBatch($batchId) failed", it) }

    suspend fun findBatch(productId: String, batchNo: String): Result<Batch?> {
        if (productId.isBlank() || batchNo.isBlank()) return Result.success(null)
        return getBatch(batchIdOf(productId, batchNo))
    }

    /** All batches of a product, soonest expiry first (blank expiry last). Sorted here to avoid a composite index. */
    suspend fun listForProduct(productId: String): Result<List<Batch>> = runCatching {
        val ref = batchesRef() ?: error("Not signed in")
        ref.whereEqualTo("productId", productId).get().await()
            .documents.mapNotNull { fromSnapshot(it) }
            .sortedWith(compareBy<Batch>({ it.expiryDate.isBlank() }, { it.expiryDate }, { it.batchKey }))
    }.onFailure { Log.e(TAG, "listForProduct($productId) failed", it) }

    // ── Find or create ───────────────────────────────────────────────────

    /**
     * Returns the product's batch with this number, creating it if new.
     *
     * Existing batch: blank dates entered → stored dates are used; a date
     * entered that differs from the stored one → [BatchDateMismatchException].
     * New batch: dates are validated and stored as entered.
     *
     * Runs on the server (batchesV1) in a transaction, so concurrent first
     * enrolments of the same new batch resolve to one doc. A date mismatch
     * comes back as the server's message (the same wording as
     * [BatchDateMismatchException]).
     */
    suspend fun findOrCreate(
        productId: String,
        sku: String,
        batchNo: String,
        mfgDate: String,
        expiryDate: String
    ): Result<Batch> = runCatching {
        val no = batchNo.trim()
        require(productId.isNotBlank()) { "Product is required for a batch" }
        require(no.isNotEmpty()) { "Batch number is required" }
        require(no.length <= BATCH_NO_MAX) { "Batch number can be at most $BATCH_NO_MAX characters" }
        BatchDates.validate(mfgDate, expiryDate)?.let { throw IllegalArgumentException(it) }

        if (companyId() == null) error("Not signed in")

        // On the server (batchesV1): same checks, one transaction, India date for "future".
        val r = try {
            ServerCalls.call(
                "batchesV1",
                mapOf(
                    "action" to "findOrCreate",
                    "productId" to productId,
                    "batchNo" to no,
                    "mfgDate" to mfgDate.trim(),
                    "expiryDate" to expiryDate.trim()
                )
            )
        } catch (e: Exception) {
            throw Exception(ServerCalls.message(e), e)
        }
        Batch(
            id = r["id"]?.toString().orEmpty(),
            productId = r["productId"]?.toString() ?: productId,
            sku = r["sku"]?.toString() ?: sku,
            batchNo = r["batchNo"]?.toString() ?: no,
            batchKey = r["batchKey"]?.toString() ?: batchKeyOf(no),
            mfgDate = r["mfgDate"]?.toString().orEmpty(),
            expiryDate = r["expiryDate"]?.toString().orEmpty(),
            status = r["status"]?.toString() ?: BatchStatus.ACTIVE.key,
            createdBy = r["createdBy"]?.toString().orEmpty(),
            updatedBy = r["updatedBy"]?.toString().orEmpty()
        )
    }.onFailure { Log.w(TAG, "findOrCreate($productId, $batchNo) failed", it) }

    // ── Status ───────────────────────────────────────────────────────────

    /** Quarantine, recall or re-activate. Expired is set only by the nightly function. */
    suspend fun setStatus(batchId: String, status: BatchStatus): Result<Unit> = runCatching {
        require(!status.systemOnly) { "${status.displayName} is set automatically" }
        if (companyId() == null) error("Not signed in")
        try {
            // Owners and managers only (batchesV1 setStatus).
            ServerCalls.call("batchesV1", mapOf("action" to "setStatus", "batchId" to batchId, "status" to status.key))
        } catch (e: Exception) {
            throw Exception(ServerCalls.message(e), e)
        }
        Unit
    }.onFailure { Log.e(TAG, "setStatus($batchId, ${status.key}) failed", it) }
}