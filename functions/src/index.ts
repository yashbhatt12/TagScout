/**
 * TagScout Cloud Functions — every function in the project lives here,
 * in one codebase, so a deploy can never delete a function by mistake.
 *
 *   updateSkuAggregate  movements ledger → inventory_sku totals
 *   setUpNewCompany     new users/{uid} → company settings from the standard preset
 *   alarmNotifier       door reader tag_events → manager alerts
 *
 * All run in asia-south1 (Mumbai), next to the Firestore database.
 */

import "./firebase"; // initializeApp() before anything else

export {updateSkuAggregate} from "./skuAggregate";
export {setUpNewCompany} from "./newCompany";
export {alarmNotifier} from "./alarmNotifier";
