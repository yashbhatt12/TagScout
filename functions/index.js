/**
 * TagScout WMS — Cloud Functions
 *
 * Purpose: Keep the inventory_sku aggregate cache in sync with the
 *          movements ledger automatically, so the Android app and
 *          future web dashboards can read SKU-level quantities without
 *          scanning through unit records every time.
 *
 * How it works:
 *   1. App writes a document to /companies/{companyId}/movements/{movementId}
 *      inside a transaction that also updates /inventory_units/{epc}.
 *   2. This function fires on that write.
 *   3. It looks at movement.quantity and movement.type, and updates
 *      /companies/{companyId}/inventory_sku/{productId} accordingly.
 *
 * The movements ledger is the source of truth. This cache can always
 * be rebuilt from the ledger if it ever drifts.
 */

const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const logger = require("firebase-functions/logger");

initializeApp();
const db = getFirestore();

/**
 * Fires whenever a new movement is created in any company's ledger.
 * Updates /companies/{companyId}/inventory_sku/{productId} accordingly.
 */
exports.updateSkuAggregate = onDocumentCreated(
  {
    document: "companies/{companyId}/movements/{movementId}",
    region: "us-central1",
  },
  async (event) => {
    const snap = event.data;
    if (!snap) {
      logger.warn("No snapshot on movement event", { params: event.params });
      return;
    }

    const movement = snap.data();
    const { companyId } = event.params;

    // Only unit-tracked movements affect the aggregate (SKU-only tracking
    // will be handled differently in the future).
    if (!movement.productId || !movement.sku) {
      logger.info("Movement has no productId/sku, skipping aggregate update", {
        movementId: event.params.movementId,
      });
      return;
    }

    const quantity = movement.quantity || 0;
    if (quantity === 0 && movement.type !== "relocation") {
      // Nothing to change on the aggregate for a zero-quantity, non-relocation move
      return;
    }

    const aggregateRef = db
      .collection("companies")
      .doc(companyId)
      .collection("inventory_sku")
      .doc(movement.productId);

    try {
      await db.runTransaction(async (txn) => {
        const doc = await txn.get(aggregateRef);
        const existing = doc.exists ? doc.data() : {};
        const byLocation = existing.byLocation || {};

        // ── Adjust the by-location breakdown ──────────────────────
        // For inward: add quantity to the destination bin
        // For outward: subtract from the source bin
        // For relocation: subtract from source, add to destination
        // For cycle count adjust: applied at the destination bin only
        if (movement.type === "inward" || movement.type === "cyclecount_adjust") {
          if (movement.toBinCode) {
            const key = `${movement.toWarehouseId}/${movement.toBinCode}`;
            byLocation[key] = (byLocation[key] || 0) + quantity;
            if (byLocation[key] <= 0) delete byLocation[key];
          }
        } else if (movement.type === "outward") {
          if (movement.fromBinCode) {
            const key = `${movement.fromWarehouseId}/${movement.fromBinCode}`;
            byLocation[key] = (byLocation[key] || 0) + quantity; // quantity is negative for outward
            if (byLocation[key] <= 0) delete byLocation[key];
          }
        } else if (movement.type === "relocation") {
          if (movement.fromBinCode) {
            const fromKey = `${movement.fromWarehouseId}/${movement.fromBinCode}`;
            byLocation[fromKey] = (byLocation[fromKey] || 0) - 1;
            if (byLocation[fromKey] <= 0) delete byLocation[fromKey];
          }
          if (movement.toBinCode) {
            const toKey = `${movement.toWarehouseId}/${movement.toBinCode}`;
            byLocation[toKey] = (byLocation[toKey] || 0) + 1;
          }
        }

        // ── Recompute total quantity as the sum of by-location values ──
        // Safer than incrementing a separate counter — stays consistent
        // with the by-location breakdown even if quantities were tweaked.
        const totalQuantity = Object.values(byLocation).reduce(
          (sum, n) => sum + n,
          0
        );

        txn.set(
          aggregateRef,
          {
            productId: movement.productId,
            sku: movement.sku,
            totalQuantity,
            byLocation,
            lastComputedAt: FieldValue.serverTimestamp(),
          },
          { merge: true }
        );
      });

      logger.info("Updated SKU aggregate", {
        companyId,
        productId: movement.productId,
        sku: movement.sku,
        type: movement.type,
      });
    } catch (err) {
      logger.error("Failed to update SKU aggregate", {
        error: err.message,
        movementId: event.params.movementId,
      });
      throw err; // Firebase retries automatically on failure
    }
  }
);