/**
 * setUpNewCompany: new registration → company settings.
 *
 * The app writes users/{uid} when someone registers (AuthRepository.register),
 * and companyId == uid until multi-user lands. This gives the new company
 * the "standard" preset plus starter categories.
 *
 * Idempotent: if a settings doc already exists (e.g. set up by hand or by
 * applyTemplate.js first), nothing is overwritten. A failure here never
 * blocks registration — the app runs on built-in defaults until settings
 * exist, and applyTemplate.js can fix the company afterwards.
 */

import {onDocumentCreated} from "firebase-functions/v2/firestore";
import * as logger from "firebase-functions/logger";
import {db, REGION} from "./firebase";
import {setUpCompany} from "./companySetup";

export const setUpNewCompany = onDocumentCreated(
  {
    document: "users/{uid}",
    region: REGION,
  },
  async (event) => {
    const {uid} = event.params;
    try {
      const result = await setUpCompany(db, uid);
      logger.info("Company set-up", result);
    } catch (e) {
      logger.error("Company set-up failed", {uid, error: e instanceof Error ? e.message : String(e)});
    }
  },
);
