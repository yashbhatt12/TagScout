package com.snainfotech.tagscout.ui.screens.orderpicking

/**
 * A single line item loaded from the uploaded Order Picking file.
 *
 * Source file columns (header row required, any order, matched by name):
 *   Product Name | Product ID | EPC Code | Bin Number | Order ID
 */
data class OrderPickingItem(
    val rowIndex: Int,           // original row position — used to preserve file order on export
    val orderId: String,
    val productId: String,
    val productName: String,
    val epc: String,
    val binNumber: String,
    val picked: Boolean = false,
    val pickedAtMillis: Long? = null
)

/**
 * A tag currently sitting "close" to the reader — strong enough signal that we
 * treat it as a pick-confirmation candidate. Only ever one of these live at a time;
 * scanning pauses the moment this appears.
 */
data class ProximityCandidate(
    val epc: String,
    val rssi: Int,
    val item: OrderPickingItem
)

data class OrderPickingState(
    val fileName: String = "",
    val hasFileLoaded: Boolean = false,
    val isParsingFile: Boolean = false,
    val fileError: String? = null,

    val items: List<OrderPickingItem> = emptyList(),

    val isScanning: Boolean = false,
    val isPaused: Boolean = false,

    val antennaPower: Int = 5,

    // The tag currently being offered up for pick confirmation, if any
    val proximityCandidate: ProximityCandidate? = null,

    val showClearWarningDialog: Boolean = false,
    val showDeviceDisconnectedDialog: Boolean = false,

    val isSaving: Boolean = false,
    val saveError: String? = null,
    val saveCompleted: Boolean = false
) {
    val totalCount: Int get() = items.size
    val pickedCount: Int get() = items.count { it.picked }
    val remainingCount: Int get() = totalCount - pickedCount
    val progressPercent: Int get() = if (totalCount == 0) 0 else (pickedCount * 100) / totalCount
    val allPicked: Boolean get() = totalCount > 0 && pickedCount == totalCount

    // Drives the shared BottomButtonBar (Save disabled until paused, Clear disabled until a file is loaded)
    val canSave: Boolean get() = hasFileLoaded && isPaused
    val canClear: Boolean get() = hasFileLoaded && !isScanning
}
// ============================================
// TEMPORARY MOCK DATA
// Skips the real file picker for now — REMOVE once real upload testing is ready.
// EPCs match FakeRfidScanner's active tag list so scanning actually finds them.
// ============================================

fun generateMockOrderPickingItems(): List<OrderPickingItem> {
    return listOf(
        OrderPickingItem(
            rowIndex = 0, orderId = "ORD-1001", productId = "P-1001",
            productName = "Widget Type A", epc = "3004A1B2C3D4E5F600000001", binNumber = "A-1"
        ),
        OrderPickingItem(
            rowIndex = 1, orderId = "ORD-1001", productId = "P-1002",
            productName = "Widget Type B", epc = "3004A2B3C4D5E6F700000002", binNumber = "A-1"
        ),
        OrderPickingItem(
            rowIndex = 2, orderId = "ORD-1001", productId = "P-1003",
            productName = "Gadget Mark 1", epc = "3004A3B4C5D6E7F800000003", binNumber = "A-2"
        ),
        OrderPickingItem(
            rowIndex = 3, orderId = "ORD-1001", productId = "P-1004",
            productName = "Gadget Mark 2", epc = "3004A4B5C6D7E8F900000004", binNumber = "A-2"
        ),
        OrderPickingItem(
            rowIndex = 4, orderId = "ORD-1002", productId = "P-1005",
            productName = "Sprocket S-100", epc = "3004A5B6C7D8E9F000000005", binNumber = "B-1"
        ),
        OrderPickingItem(
            rowIndex = 5, orderId = "ORD-1002", productId = "P-1006",
            productName = "Sprocket S-200", epc = "3004A6B7C8D9E0F100000006", binNumber = "B-1"
        )
    )
}

fun generateMockOrderFilename(): String {
    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
        .format(java.util.Date())
    return "orderpicking_$timestamp.xlsx"
}