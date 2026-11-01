/**
 * TagScout Cloud Functions — every function in the project lives here,
 * in one codebase, so a deploy can never delete a function by mistake.
 *
 *   updateSkuAggregate  movements ledger → inventory_sku totals
 *   setUpNewCompany     new users/{uid} → company settings from the standard preset
 *   alarmNotifier       door reader tag_events → manager alerts
 *   addMemberV1, setMemberRoleV1, removeMemberV1   team management (callable, owner only)
 *   enrollUnitsV1       enroll a batch of tags (callable, any team member)
 *   changeUnitStatusV1, markSoldV1, updateUnitDetailsV1   unit writes (callable; role limits inside)
 *
 * All run in asia-south1 (Mumbai), next to the Firestore database.
 */

import "./firebase"; // initializeApp() before anything else

export {updateSkuAggregate} from "./skuAggregate";
export {setUpNewCompany} from "./newCompany";
export {alarmNotifier} from "./alarmNotifier";
export {addMemberV1, setMemberRoleV1, removeMemberV1} from "./team";
export {enrollUnitsV1} from "./enroll";
export {changeUnitStatusV1, markSoldV1, updateUnitDetailsV1} from "./units";
