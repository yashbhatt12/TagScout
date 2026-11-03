/**
 * TagScout Cloud Functions — every function in the project lives here,
 * in one codebase, so a deploy can never delete a function by mistake.
 *
 *   updateSkuAggregate  movements ledger → inventory_sku totals
 *   setUpNewCompany     new users/{uid} → company settings from the standard preset
 *   doorEventProcessor  fixed reader tag_events → door passes (in/out, verdict) → alerts for unpaid items leaving
 *   readersV1           fixed reader settings: name, inside/outside antennas (callable, owner + manager)
 *   bfrmsIngest         HTTPS endpoint for readers posting reads straight to TagScout (Bluebird BFRMS)
 *   doorMonitor         every 5 minutes: "Door monitoring stopped" alerts for silent readers
 *   grnImportV1         GRN Excel import: check the uploaded file, then save it (callable, any team member)
 *   addMemberV1, setMemberRoleV1, removeMemberV1   team management (callable, owner only)
 *   enrollUnitsV1       enroll a batch of tags (callable, any team member)
 *   changeUnitStatusV1, markSoldV1, updateUnitDetailsV1   unit writes (callable; role limits inside)
 *   locationsV1         warehouses / racks / bins set-up (callable, owner + manager)
 *   catalogV1           categories and brands (callable, owner + manager)
 *   productsV1          product catalog incl. SKU index (callable, owner + manager)
 *   batchesV1, bumpGrnCounterV1, commitCycleCountV1   batches, GRN numbers, cycle-count commit (callable)
 *
 * All run in asia-south1 (Mumbai), next to the Firestore database.
 */

import "./firebase"; // initializeApp() before anything else

export {updateSkuAggregate} from "./skuAggregate";
export {setUpNewCompany} from "./newCompany";
export {doorEventProcessor, readersV1} from "./door";
export {bfrmsIngest, doorMonitor} from "./bfrms";
export {grnImportV1} from "./grn/grnImport";
export {addMemberV1, setMemberRoleV1, removeMemberV1} from "./team";
export {enrollUnitsV1} from "./enroll";
export {changeUnitStatusV1, markSoldV1, updateUnitDetailsV1} from "./units";
export {locationsV1} from "./locations";
export {catalogV1} from "./catalog";
export {productsV1} from "./products";
export {batchesV1, bumpGrnCounterV1, commitCycleCountV1} from "./stock";
