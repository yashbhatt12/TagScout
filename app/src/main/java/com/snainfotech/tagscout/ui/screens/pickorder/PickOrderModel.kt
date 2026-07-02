package com.snainfotech.tagscout.ui.screens.pickorder

// ============================================
// PICK ORDER DATA MODELS
// ============================================

// A single item on the pick list
data class PickOrderItem(
    val serialNo: Int,              // Sr. No. from uploaded file
    val epc: String,                 // 24-char hex EPC
    val productName: String,         // e.g., "Widget Type A"
    val binNumber: String,           // e.g., "A-1"
    // Runtime state:
    val isDetected: Boolean = false, // Currently in range of reader
    val isPicked: Boolean = false,   // Confirmed picked by user
    val pickedAt: Long? = null,      // Timestamp when picked
    val currentRssi: Int = 0,        // Latest signal strength (for UI)
    val firstDetectedAt: Long? = null // When detection started (for 1s proximity threshold)
)

// An EPC scanned but not on the pick list
data class UnexpectedItem(
    val epc: String,
    val firstDetectedAt: Long,
    val includedInOrder: Boolean = false  // User chose to include in delivery
)

// The overall pick order state
data class PickOrderState(
    val phase: PickOrderPhase = PickOrderPhase.EMPTY,
    val orderFilename: String = "",
    val orderNumber: String = "",           // e.g., "ORD-12345" (from file or generated)
    val items: List<PickOrderItem> = emptyList(),
    val unexpectedItems: List<UnexpectedItem> = emptyList(),
    val isPicking: Boolean = false,          // Currently scanning
    val antennaStrength: Int = 5,
    val selectedTab: PickOrderTab = PickOrderTab.PICK_LIST,
    val locateTargetEpc: String? = null,     // Non-null when in locate mode
    val showWrongEpcSnackbar: Boolean = false,
    val wrongEpcMessage: String = ""
)

// Phases of the pick order workflow
enum class PickOrderPhase {
    EMPTY,       // No file loaded
    LOADED,      // File loaded, not started picking
    PICKING,     // Actively scanning + picking
    COMPLETE     // All items picked or user confirmed complete
}

// Tabs in the pick order screen
enum class PickOrderTab {
    PICK_LIST,       // Items grouped by bin
    UNEXPECTED       // Extra scanned items
}

// ============================================
// COMPUTED HELPERS
// ============================================

// Total expected items
val PickOrderState.totalItems: Int
    get() = items.size

// How many are picked
val PickOrderState.pickedCount: Int
    get() = items.count { it.isPicked }

// How many are unpicked
val PickOrderState.remainingCount: Int
    get() = items.count { !it.isPicked }

// Progress percentage (0-100)
val PickOrderState.progressPercent: Int
    get() = if (items.isEmpty()) 0
    else (pickedCount * 100) / items.size

// Items grouped by bin (sorted by bin number)
val PickOrderState.itemsByBin: Map<String, List<PickOrderItem>>
    get() = items.groupBy { it.binNumber }
        .toSortedMap()

// Is the order fully picked?
val PickOrderState.isFullyPicked: Boolean
    get() = items.isNotEmpty() && items.all { it.isPicked }

// ============================================
// MOCK FILE LOADER (Phase 1 — real Excel parsing in Phase 2)
// ============================================

// Generates a realistic mock pick order for testing.
// EPCs match the FakeRfidScanner's active tags so testing works.
fun generateMockPickOrder(): List<PickOrderItem> {
    return listOf(
        // Bin A-1 (small parts shelf)
        PickOrderItem(
            serialNo = 1,
            epc = "3004A1B2C3D4E5F600000001",
            productName = "Widget Type A",
            binNumber = "A-1"
        ),
        PickOrderItem(
            serialNo = 2,
            epc = "3004A2B3C4D5E6F700000002",
            productName = "Widget Type B",
            binNumber = "A-1"
        ),
        // Bin A-2
        PickOrderItem(
            serialNo = 3,
            epc = "3004A3B4C5D6E7F800000003",
            productName = "Gadget Mark 1",
            binNumber = "A-2"
        ),
        PickOrderItem(
            serialNo = 4,
            epc = "3004A4B5C6D7E8F900000004",
            productName = "Gadget Mark 2",
            binNumber = "A-2"
        ),
        // Bin B-1
        PickOrderItem(
            serialNo = 5,
            epc = "3004A5B6C7D8E9F000000005",
            productName = "Sprocket S-100",
            binNumber = "B-1"
        ),
        PickOrderItem(
            serialNo = 6,
            epc = "3004A6B7C8D9E0F100000006",
            productName = "Sprocket S-200",
            binNumber = "B-1"
        ),
        // Bin B-2 (contains some items that WON'T match fake scanner — for testing "missing" flow)
        PickOrderItem(
            serialNo = 7,
            epc = "3004FF00FF00FF00FF000001",  // NOT in fake scanner
            productName = "Missing Item Alpha",
            binNumber = "B-2"
        ),
        PickOrderItem(
            serialNo = 8,
            epc = "3004FF11FF11FF11FF000002",  // NOT in fake scanner
            productName = "Missing Item Beta",
            binNumber = "B-2"
        )
    )
}

// Generate a mock order number based on current time
fun generateMockOrderNumber(): String {
    val timestamp = System.currentTimeMillis() % 100000
    return "ORD-${timestamp.toString().padStart(5, '0')}"
}

// Generate a mock filename
fun generateMockFilename(): String {
    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
        .format(java.util.Date())
    return "picklist_$timestamp.xlsx"
}