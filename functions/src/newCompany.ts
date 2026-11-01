/**
 * setUpNewCompany: new registration → company settings and an owner login.
 *
 * The app writes users/{uid} when someone registers (AuthRepository.register).
 * A registration starts a new company whose id is the owner's uid:
 *   1. settings from the "standard" preset plus starter categories
 *   2. the login labelled owner of that company (claims + members entry)
 *
 * Logins added to an existing company (by the team functions) also get a
 * users/{uid} doc, with companyId set to that company. Those are skipped
 * here, so adding a staff member never creates a stray empty company.
 *
 * Idempotent: existing settings are never overwritten, and a login that
 * already has a company in its claims is left alone. A failure never
 * blocks registration — the app runs on built-in defaults until settings
 * exist, and the admin scripts can fix the company afterwards.
 */

import {onDocumentCreated} from "firebase-functions/v2/firestore";
import * as logger from "firebase-functions/logger";
import {getAuth} from "firebase-admin/auth";
import {db, REGION} from "./firebase";
import {setUpCompany} from "./companySetup";
import {makeOwnerOfOwnCompany} from "./members";

const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e));

export const setUpNewCompany = onDocumentCreated(
  {
    document: "users/{uid}",
    region: REGION,
  },
  async (event) => {
    const {uid} = event.params;
    const profile = event.data?.data() ?? {};

    const memberOf = typeof profile.companyId === "string" ? profile.companyId : "";
    if (memberOf && memberOf !== uid) {
      logger.info("Login added to an existing company; no set-up", {uid, companyId: memberOf});
      return;
    }

    try {
      const result = await setUpCompany(db, uid);
      logger.info("Company set-up", result);
    } catch (e) {
      logger.error("Company set-up failed", {uid, error: errorText(e)});
    }

    try {
      const owner = await makeOwnerOfOwnCompany(getAuth(), db, uid, profile);
      logger.info("Owner login", {uid, claims: owner});
    } catch (e) {
      logger.error("Owner labelling failed", {uid, error: errorText(e)});
    }
  },
);
