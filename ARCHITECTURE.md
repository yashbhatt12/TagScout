# TagScout Architecture

## Vision

TagScout is built to be **vendor-agnostic** — the goal is to support multiple RFID hardware vendors (Bluebird, Zebra, Impinj, and others) through the same Android app. Customers should be able to choose their hardware without being locked into a specific software solution.

## Deployment Model

```
[Stock Android device]  <--Bluetooth-->  [RFID Sled (any vendor)]
   (phone or tablet)                       (Bluebird RFR-901, etc.)
        ↑
   Runs TagScout app
```

The app runs on **any** Android device. The RFID sled is the external Bluetooth hardware. This means:

- Customers can use cheap Android phones, premium rugged tablets, or whatever fits their budget
- Customers aren't locked into vendor-specific Android devices (like TSC's TC-10)
- One software solution works across multiple hardware vendors

## Multi-Vendor Strategy (Future)

### Current State (Phase 1)
- Single vendor support: Bluebird RFR-901 (planned for Phase 2 SDK integration)
- One scanner implementation: `FakeRfidScanner` (placeholder until Phase 2)
- Architecture uses `RfidScanner` interface to allow swapping implementations

### Future State (Phase 3+)
The app will support multiple vendors simultaneously, with automatic detection and routing:

```
        ┌──────────────────────────┐
        │   TagScout App (UI/UX)    │
        └─────────────┬─────────────┘
                      │ uses interface
                      ▼
        ┌──────────────────────────┐
        │     RfidScanner interface  │
        │  (vendor-neutral contract) │
        └─┬────────────┬───────────┬┘
          │            │           │
          ▼            ▼           ▼
    ┌─────────┐  ┌─────────┐  ┌─────────┐
    │Bluebird │  │  Zebra  │  │ Impinj  │
    │ Driver  │  │ Driver  │  │ Driver  │
    └────┬────┘  └────┬────┘  └────┬────┘
         │            │            │
         ▼            ▼            ▼
    Bluebird SDK   Zebra SDK   Impinj SDK
```

### Why This Matters Strategically

**For SNA Infotech (the business):**
- Software differentiates from pure hardware vendors
- Customer flexibility = larger addressable market
- Becomes the "integration layer" across multiple OEMs

**For customers:**
- Cost optimization (use cheap Android devices)
- Fleet flexibility (mix hardware vendors)
- Lower switching costs
- One app to learn across multiple hardware types

**For the product:**
- Each vendor's strengths can be leveraged
- Customer isn't stranded if a vendor's product line changes
- Easier to negotiate with hardware vendors when they know you're not locked in

## Current Architecture

### Layer Separation

```
UI Layer (Compose screens)
   ↓ talks to
ViewModels (state management)
   ↓ talks to
Repositories (data persistence + business logic)
   ↓ talks to
Hardware Interface (RfidScanner) ← vendor-neutral
   ↓ implemented by
Vendor-Specific Implementations (FakeRfidScanner, future BluebirdRfidScanner, etc.)
```

### Key Interfaces

#### `RfidScanner`
The core contract every RFID hardware integration must implement. Currently covers:
- Identity (`vendorName`, `modelName`)
- Capabilities (`supportedFeatures`)
- Scanning (start/stop/antenna)

In Phase 3, this will likely expand into a fuller `RfidDeviceDriver` interface that also handles:
- Device discovery (Bluetooth scanning)
- Connection lifecycle
- Battery monitoring
- Firmware updates
- Settings (buzzer, sleep timeout)

For now, those concerns are handled in screen-specific code. They'll be unified when a second vendor is added.

#### `ScannerFeature`
An enum describing optional capabilities. UI should check `scanner.supportedFeatures` before exposing controls for features. For example, if a scanner doesn't support `ANTENNA_POWER_CONTROL`, the Quick Scan screen should hide the antenna slider.

## When to Add Multi-Vendor Support

**Don't pre-optimize.** Add new vendor implementations only when:

1. You have a real second SDK in hand to validate against
2. A customer has a concrete request for a non-Bluebird device
3. Phase 2 (Bluebird integration) is stable and battle-tested

Building abstractions without a real second implementation tends to produce wrong abstractions. Real-world usage informs the right interface design.

## Adding a New Vendor (Future Process)

When the time comes:

1. **Get the vendor's SDK and documentation**
2. **Identify what's similar to Bluebird** (the reference implementation)
3. **Extract any patterns into the `RfidScanner` interface** that should be common
4. **Create `<Vendor>RfidScanner` class** implementing `RfidScanner`
5. **Add a way for the app to detect/select the vendor** — could be:
   - User-selectable in settings
   - Auto-detected via Bluetooth MAC prefix
   - Configured by deployment context
6. **Test thoroughly with real hardware**

## Notes for Phase 2 (Bluebird Integration)

When implementing `BluebirdRfidScanner : RfidScanner`:

- Wrap Bluebird SDK's Java callbacks in Kotlin `callbackFlow`
- Use `BTReader` class (not `Reader`) — we're Bluetooth-based, not Serial/USB
- Map Bluebird's settings classes (`SDBuzzerLevel`, `SDSleepTimeout`) to our internal enums
- Handle connection lifecycle events (`SLED_BT_ACL_CONNECTED`, `SLED_BT_ACL_DISCONNECTED`)
- Don't expose Bluebird-specific types in the `RfidScanner` interface

## Anti-Patterns to Avoid

❌ **Don't reference vendor-specific classes from UI code**
   Bad: `import co.kr.bluebird.sled.BTReader` in any Compose file
   Good: UI only knows about `RfidScanner` and its data types

❌ **Don't make `RfidScanner` too generic too early**
   Bad: Adding methods like `genericCommand(opcode, params)` to support future flexibility
   Good: Add methods only when there's a concrete need

❌ **Don't pre-build vendor adapters for vendors you don't have SDKs for**
   Bad: Stub `ZebraRfidScanner` with hypothetical method signatures
   Good: Add new vendor implementations when actually integrating

## Glossary

- **OEM** — Original Equipment Manufacturer (Bluebird, Zebra, Impinj, etc.)
- **Sled** — A Bluetooth RFID handheld accessory that pairs with an Android device
- **EPC** — Electronic Product Code (the standard format for RFID tag IDs)
- **TID** — Tag ID (an alternative memory bank on some tags)
- **RSSI** — Received Signal Strength Indicator (how loud a tag's reply is)
- **AGP** — Android Gradle Plugin
- **KSP** — Kotlin Symbol Processing (used for Room database code generation)
