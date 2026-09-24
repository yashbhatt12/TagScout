package com.snainfotech.tagscout.data.wms

import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
import kotlinx.coroutines.tasks.await
import java.util.Calendar

/**
 * Generates human-readable reference numbers for WMS documents.
 *
 * Current scope: GRN references, format "GRN-YYYY-NNNN" (four-digit
 * zero-padded sequence, up to 9,999 per year). Room to add other prefixes
 * later — dispatch notes, transfers, cycle-count runs — following the same
 * peek/bump pattern.
 *
 * How the peek/bump pattern works:
 *   - Screen opens → suggestNextGrn() reads the counter, returns
 *     "GRN-{year}-{sequence+1}" as a pre-filled default. User can override.
 *   - After a successful commit → bumpGrnCounter(reference) parses the
 *     actual number that was used and sets the counter to max(current,
 *     parsed). This handles both auto-suggested and user-typed references.
 *
 * Why this design:
 *   - Peek doesn't reserve. If the user cancels, next suggestion is the
 *     same number, so there are no gaps in the sequence.
 *   - Bump uses max(), so user-typed higher numbers advance the counter
 *     and user-typed lower numbers don't rewind it.
 *   - Exotic references (e.g. "MYGRN2026") that don't parse are a silent
 *     no-op — they simply aren't tracked. Counter continues from whatever
 *     the last parseable reference was.
 *
 * Race semantics:
 *   Best-effort in single-user v1 — worst case two concurrent commits
 *   suggest the same number and both succeed, since referenceId is a
 *   stored field on movements (not a doc ID) with no unique constraint.
 *   When the Admin Console lands (Feature 1) and multiple users share a
 *   companyId, this should move into a Firestore transaction as part of
 *   the GRN commit itself.
 *
 * Storage:
 *   /companies/{companyId}/counters/grn
 *     year:     Long  (year the counter was last bumped in)
 *     sequence: Long  (last used sequence number for that year)
 *
 *   Single doc per company. Year rollover is detected by comparing the
 *   stored year to the current year at peek/bump time — a mismatch means
 *   the sequence resets to 0.
 */
object ReferenceGenerator {

    private const val GRN_PREFIX = "GRN"
    private const val SEQUENCE_DIGITS = 4
    private val GRN_PATTERN = Regex("""^GRN-(\d{4})-(\d+)$""", RegexOption.IGNORE_CASE)

    private val firestore get() = FirebaseFirestore.getInstance()

    private suspend fun counterDoc(counterName: String) = AuthReady.awaitUid()?.let { uid ->
        firestore.collection("companies").document(uid)
            .collection("counters").document(counterName)
    }

    /**
     * Read the GRN counter and return the next suggested reference number
     * for the current year, without incrementing anything.
     *
     * If the counter doc is missing, or its stored year is older than the
     * current year, we treat the sequence as starting fresh at 1.
     *
     * Returns a formatted string on success. Result.failure is only used
     * for "not logged in" and network errors — never for a missing counter
     * doc (which is a normal state on first use of the feature).
     */
    suspend fun suggestNextGrn(): Result<String> {
        return try {
            val year = Calendar.getInstance().get(Calendar.YEAR)
            val ref = counterDoc("grn")
                ?: return Result.failure(Exception("Not logged in"))
            val snap = ref.get().await()

            val next: Long = if (!snap.exists()) {
                1L
            } else {
                val storedYear = snap.getLong("year") ?: 0L
                val storedSeq = snap.getLong("sequence") ?: 0L
                if (storedYear.toInt() != year) 1L else storedSeq + 1L
            }
            Result.success(formatGrn(year, next))
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * After a successful GRN commit, advance the counter to reflect the
     * reference that was actually used. Handles four cases:
     *
     *   1. Auto-suggested reference (e.g. "GRN-2026-0042"): counter moves
     *      to 42 for year 2026.
     *   2. User-typed higher number ("GRN-2026-0100"): counter jumps to 100.
     *   3. User-typed lower number ("GRN-2026-0010" when counter was 42):
     *      counter stays at 42 (max()).
     *   4. Non-parseable reference ("MYGRN2026"): silently no-op, counter
     *      unchanged. Next suggestion continues from wherever we were.
     *
     * A year mismatch (reference from 2025 committed in 2026) also no-ops.
     * The reference itself is still valid; we just don't drag the current
     * year's counter backwards.
     *
     * Failures here are swallowed: bump is best-effort and never should
     * break the GRN commit flow (which has already succeeded by the time
     * we're called). Worst case, the next suggestion is off by one and
     * self-corrects on the following bump.
     */
    suspend fun bumpGrnCounter(reference: String): Result<Unit> {
        return try {
            val match = GRN_PATTERN.matchEntire(reference.trim())
                ?: return Result.success(Unit)  // exotic reference: silent no-op

            val refYear = match.groupValues[1].toIntOrNull()
                ?: return Result.success(Unit)
            val refSeq = match.groupValues[2].toLongOrNull()
                ?: return Result.success(Unit)

            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            if (refYear != currentYear) {
                // Cross-year commit — leave the current year's counter alone.
                return Result.success(Unit)
            }

            val ref = counterDoc("grn")
                ?: return Result.failure(Exception("Not logged in"))

            firestore.runTransaction { txn ->
                val snap = txn.get(ref)
                val storedYear = snap.getLong("year") ?: 0L
                val storedSeq = snap.getLong("sequence") ?: 0L
                val newSeq = if (storedYear.toInt() == currentYear) {
                    maxOf(storedSeq, refSeq)
                } else {
                    // First bump this year — take the reference's sequence directly.
                    refSeq
                }
                txn.set(
                    ref,
                    hashMapOf<String, Any>(
                        "year" to currentYear.toLong(),
                        "sequence" to newSeq
                    )
                )
                null
            }.await()

            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun formatGrn(year: Int, sequence: Long): String {
        val padded = sequence.toString().padStart(SEQUENCE_DIGITS, '0')
        return "$GRN_PREFIX-$year-$padded"
    }
}