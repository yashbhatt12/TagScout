package com.snainfotech.tagscout.data.auth

import com.google.firebase.auth.FirebaseAuth
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
 */
object AuthReady {

    private const val TIMEOUT_MS = 5000L

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
        auth.currentUser?.uid?.let { return it }

        // Slower path: wait for the IdTokenListener to fire once.
        return withTimeoutOrNull(TIMEOUT_MS) {
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
    }
}