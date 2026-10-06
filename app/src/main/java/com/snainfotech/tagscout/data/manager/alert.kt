package com.snainfotech.tagscout.data.manager

import com.google.firebase.Timestamp

/**
 * A manager-facing alert — one row per unsold jewellery piece seen at a
 * portal reader.
 *
 * Written by the `alarmNotifier` Cloud Function in `tagscout-functions`.
 * The edge service writes `tag_events` to Firestore; the Cloud Function
 * reacts to each tag_event, looks up the EPC in `inventory_units`, and if
 * the item is active (unsold), writes one of these documents.
 *
 * Stored at /companies/{companyId}/alerts/{alertId}
 *
 * The Android Manager View subscribes to this collection in realtime via
 * [ManagerRepository.observeAlerts] and shows unread alerts to the shop
 * manager. Marking an alert as read updates the `read` field in-place
 * (never deleted — alerts are an audit record).
 *
 * severity values (string, not enum, to stay compatible with web-SDK writes):
 *   "high"   — unsold active item seen at portal (the default)
 *   "medium" — reserved for future use
 *   "low"    — reserved for future use
 *
 * Nullability notes:
 *   - `itemCode`, `category`, `price` come from the matching InventoryUnit
 *     document. They CAN be null if the item was enrolled with sparse data
 *     or if the function fired on an edge case. Treat as missing, don't
 *     crash.
 *   - `readerId`, `antennaPort`, `rssi`, `firstSeenAt` come from the
 *     source tag_event. These are populated for every real portal read
 *     but may be null for synthetic tests.
 *   - `createdAt` is the Firestore server timestamp when this alert was
 *     written. Null only in flight, before the server resolves it.
 */
data class Alert(
    val id: String = "",                 // Firestore doc ID
    val epc: String = "",
    val itemCode: String? = null,
    val category: String? = null,
    val price: Double? = null,
    val readerId: String? = null,
    val antennaPort: Int? = null,
    val rssi: Int? = null,
    val firstSeenAt: Long? = null,       // epoch millis from edge service clock
    val sourceEventId: String = "",      // the tag_events doc id that triggered this
    val severity: String = "high",
    val read: Boolean = false,
    val createdAt: Timestamp? = null
)