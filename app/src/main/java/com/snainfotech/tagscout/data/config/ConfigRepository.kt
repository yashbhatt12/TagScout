package com.snainfotech.tagscout.data.config

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide, live view of the signed-in company's settings doc:
 *   /companies/{companyId}/settings/config
 *
 * Usage (ViewModel or Composable):
 *   val config by ConfigRepository.config.collectAsState()
 *   if (config.isEnabled(Feature.RETAIL)) ...
 *
 * Behaviour:
 *   - Starts on CompanyConfig.DEFAULT, so the app works before (or without)
 *     any settings doc — everything behaves as it does today.
 *   - Follows Firebase Auth: attaches one snapshot listener per signed-in
 *     company, swaps it on account change, and resets to defaults on logout,
 *     so one company's settings can never leak into another's session.
 *   - Firestore's offline cache serves the last known doc instantly on cold
 *     start (source = CACHE), then the server copy replaces it (source = SERVER).
 *   - Errors (offline, permission denied before rules are deployed) keep the
 *     last good config rather than blanking it. A bad doc never crashes the app:
 *     parsing is forgiving, and anything it still throws falls back to defaults.
 *
 * Read-only: the app never writes settings. Admin edits come in through the
 * console / Admin SDK and arrive here live via the listener.
 *
 * Started lazily on first access — no Application wiring needed.
 */
object ConfigRepository {

    private const val TAG = "ConfigRepository"

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val _config = MutableStateFlow(CompanyConfig.DEFAULT)

    /** Live config for the signed-in company. Never null; defaults when unknown. */
    val config: StateFlow<CompanyConfig> = _config.asStateFlow()

    /** Snapshot of the current value, for non-reactive call sites (e.g. a save check). */
    val current: CompanyConfig get() = _config.value

    private var registration: ListenerRegistration? = null
    private var listeningFor: String? = null

    init {
        // Fires once immediately with the current auth state, then on every
        // sign-in / sign-out. companyId == uid until multi-user lands.
        auth.addAuthStateListener { firebaseAuth ->
            onCompanyChanged(firebaseAuth.currentUser?.uid)
        }
    }

    /** Path helper, shared with anything else that needs the doc (e.g. onboarding checks). */
    fun settingsDoc(companyId: String): DocumentReference =
        firestore.collection("companies").document(companyId)
            .collection("settings").document("config")

    @Synchronized
    private fun onCompanyChanged(companyId: String?) {
        if (companyId == listeningFor) return

        registration?.remove()
        registration = null
        listeningFor = companyId
        _config.value = CompanyConfig.DEFAULT

        if (companyId == null) return

        registration = settingsDoc(companyId).addSnapshotListener { snapshot, error ->
            // Ignore late callbacks from a listener we've already replaced.
            if (listeningFor != companyId) return@addSnapshotListener

            if (error != null) {
                Log.w(TAG, "Settings listener error; keeping last config", error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener

            val source = if (snapshot.metadata.isFromCache) ConfigSource.CACHE else ConfigSource.SERVER

            _config.value = if (!snapshot.exists()) {
                CompanyConfig.DEFAULT
            } else {
                runCatching { CompanyConfigParser.parse(snapshot.data, source) }
                    .onFailure { Log.e(TAG, "Settings doc failed to parse; using defaults", it) }
                    .getOrDefault(CompanyConfig.DEFAULT)
            }
        }
    }
}