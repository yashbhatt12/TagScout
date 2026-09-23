package com.snainfotech.tagscout.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Helpers for reliably reading the currently-signed-in user's UID.
 *
 * Why this exists:
 *   FirebaseAuth restores the cached session asynchronously on app startup.
 *   For a short window (usually <1 second, but sometimes longer on slow
 *   devices or cold starts), FirebaseAuth.getInstance().currentUser returns
 *   null even though a valid session is on disk. Any code that reads
 *   currentUser?.uid during this window will incorrectly conclude the user
 *   is not logged in.
 *
 *   The IdTokenListener fires exactly once as soon as Firebase Auth has
 *   determined the initial user state (either a real user or definitively
 *   no user). Waiting for that first fire eliminates the race.
 *
 * Timing budget:
 *   awaitInitialAuthState() returns as soon as auth settles, with a 5-second
 *   ceiling. If auth genuinely hasn't settled in 5 seconds, we return null
 *   and callers treat it as "not logged in" — which by that point is a fair
 *   conclusion (probably a serious network issue or a corrupted session).
 *
 * Crashlytics user identifier:
 *   Whenever awaitUid() resolves a UID we haven't seen this session, we
 *   also stamp it onto Firebase Crashlytics via setUserId(). This means any
 *   crash captured later in the session shows the Firebase UID in the
 *   Crashlytics console, so we can tell which user hit it. It's a no-op in
 *   debug builds (Crashlytics collection is disabled in Application.onCreate).
 */
object AuthReady {

    private const val TIMEOUT_MS = 5000L

    /**
     * Tracks the last UID we forwarded to Crashlytics so we don't spam
     * setUserId on every awaitUid call (repositories call awaitUid dozens
     * of times per screen). Not a security-sensitive cache — worst case
     * we set the same UID twice.
     */
    @Volatile
    private var lastSetUid: String? = null

    /**
     * Returns the currently-signed-in user's UID, waiting up to 5 seconds
     * for Firebase Auth to settle if needed. Returns null if genuinely
     * not logged in, or if auth doesn't settle in time.
     *
     * Safe to call from any coroutine. Cheap once auth has settled — the
     * currentUser check is instant and the listener wait only happens on
     * the very first calls after app startup.
     */
    suspend fun awaitUid(): String? {
        val auth = FirebaseAuth.getInstance()

        // Fast path: if auth is already settled with a user, return immediately.
        auth.currentUser?.uid?.let { uid ->
            attachToCrashlytics(uid)
            return uid
        }

        // Slower path: wait for the IdTokenListener to fire once.
        val uid = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                val listener = object : com.google.firebase.auth.FirebaseAuth.IdTokenListener {
                    override fun onIdTokenChanged(firebaseAuth: FirebaseAuth) {
                        // Fire once, then remove ourselves. The listener may
                        // fire multiple times over the app's lifetime as
                        // tokens refresh; we only care about the first fire.
                        firebaseAuth.removeIdTokenListener(this)
                        if (cont.isActive) {
                            cont.resume(firebaseAuth.currentUser?.uid)
                        }
                    }
                }
                auth.addIdTokenListener(listener)
                cont.invokeOnCancellation {
                    auth.removeIdTokenListener(listener)
                }
            }
        }
        if (uid != null) attachToCrashlytics(uid)
        return uid
    }

    /**
     * Stamp this UID onto Crashlytics so future crashes show who hit them.
     * Deduped against lastSetUid to avoid repeated no-op writes. Wrapped in
     * try/catch because Crashlytics initialization can fail in edge cases
     * (e.g. missing google-services.json in a stripped variant) and we
     * absolutely never want this to bubble up and crash a caller.
     */
    private fun attachToCrashlytics(uid: String) {
        if (lastSetUid == uid) return
        try {
            FirebaseCrashlytics.getInstance().setUserId(uid)
            lastSetUid = uid
        } catch (_: Exception) {
            // Silent: crash-reporting failure must never break the app.
        }
    }
}