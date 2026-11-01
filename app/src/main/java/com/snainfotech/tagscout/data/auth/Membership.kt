package com.snainfotech.tagscout.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * A person's role in their company. Set by the server (team functions,
 * snaAdmin.js) in the login's custom claims; never by the app.
 */
enum class TeamRole(val key: String, val displayName: String) {
    OWNER("owner", "Owner"),
    MANAGER("manager", "Manager"),
    STAFF("staff", "Staff");

    /** Company Set Up: locations, products, categories, brands, bulk catalog. */
    val canSetUp: Boolean get() = this != STAFF

    /** Marking units lost or disposed. */
    val canWriteOff: Boolean get() = this != STAFF

    companion object {
        fun fromKey(key: Any?): TeamRole? = entries.firstOrNull { it.key == key }
    }
}

/** Which company the signed-in login works in, and its role there. */
data class Membership(val uid: String, val companyId: String, val role: TeamRole)

/**
 * The signed-in login's company and role, read from its ID token claims
 * { companyId, role }. A login without claims (from before multi-user) is the
 * owner of its own company, companyId == uid — the same rule as firestore.rules.
 *
 * Kept current automatically: the ID token listener fires on sign-in, sign-out
 * and every token refresh, and the token is force-refreshed once per app start
 * so a role changed on the dashboard applies the next time the app opens.
 */
object MembershipRepository {

    private val auth = FirebaseAuth.getInstance()
    private val _membership = MutableStateFlow<Membership?>(null)

    /** Null while signed out (or for a moment while the token loads). */
    val membership: StateFlow<Membership?> = _membership.asStateFlow()

    val current: Membership? get() = _membership.value

    @Volatile
    private var refreshedThisRun = false

    init {
        auth.addIdTokenListener(FirebaseAuth.IdTokenListener { a -> load(a.currentUser) })
    }

    private fun load(user: FirebaseUser?) {
        if (user == null) {
            _membership.value = null
            refreshedThisRun = false
            return
        }
        val force = !refreshedThisRun
        user.getIdToken(force)
            .addOnSuccessListener { result ->
                if (force) refreshedThisRun = true
                if (auth.currentUser?.uid == user.uid) _membership.value = parse(result.claims, user.uid)
            }
            .addOnFailureListener {
                // Offline: fall back to the cached token rather than leave the app without a company.
                if (force) user.getIdToken(false).addOnSuccessListener { result ->
                    if (auth.currentUser?.uid == user.uid) _membership.value = parse(result.claims, user.uid)
                }
            }
    }

    /** Waits for the signed-in login's membership; null when not signed in. */
    suspend fun await(): Membership? {
        val uid = AuthReady.awaitUid() ?: return null
        _membership.value?.takeIf { it.uid == uid }?.let { return it }
        val user = auth.currentUser ?: return null
        val claims = try {
            user.getIdToken(false).await().claims
        } catch (_: Exception) {
            emptyMap<String, Any>()
        }
        return parse(claims, uid).also { _membership.value = it }
    }

    private fun parse(claims: Map<String, Any?>, uid: String): Membership {
        val companyId = claims["companyId"] as? String
        val role = TeamRole.fromKey(claims["role"])
        return if (!companyId.isNullOrBlank() && role != null) {
            Membership(uid, companyId, role)
        } else {
            Membership(uid, uid, TeamRole.OWNER)
        }
    }
}
