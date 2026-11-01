package com.snainfotech.tagscout.data.server

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Calls the server's write functions (functions/src, region asia-south1).
 *
 * Every call carries an opId, created once per call: if the connection drops
 * the request is retried with the same opId, and the server never applies it
 * twice. The server's own error messages (e.g. "This piece was just sold
 * elsewhere.") come back as the exception message.
 */
object ServerCalls {

    private val functions = FirebaseFunctions.getInstance("asia-south1")

    /** Calls [name] with [data] (plus an opId) and returns the reply as a map. */
    suspend fun call(name: String, data: Map<String, Any?>, timeoutMinutes: Long = 1): Map<*, *> {
        val request = HashMap<String, Any?>(data).apply { put("opId", UUID.randomUUID().toString()) }
        val callable = functions.getHttpsCallable(name).apply { setTimeout(timeoutMinutes, TimeUnit.MINUTES) }

        var attempt = 0
        while (true) {
            try {
                // getData(), not .data: the Kotlin property clashes with a private field in this SDK.
                return callable.call(request).await().getData() as? Map<*, *>
                    ?: throw IllegalStateException("Unexpected reply from the server")
            } catch (e: FirebaseFunctionsException) {
                val retryable = e.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                    e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ||
                    e.code == FirebaseFunctionsException.Code.ABORTED
                if (!retryable || attempt >= 2) throw e
                attempt++
                delay(2000L * attempt)
            }
        }
    }

    /** The server's message where there is one; plain words for connection trouble. */
    fun message(e: Throwable): String = when {
        e is FirebaseFunctionsException && (
            e.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED
            ) -> "No connection to the server — not saved. Try again."
        e is FirebaseFunctionsException && e.code == FirebaseFunctionsException.Code.INTERNAL ->
            "Server error — not saved. Try again."
        else -> e.message ?: "Save failed"
    }
}
