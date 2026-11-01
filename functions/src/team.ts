/**
 * Team management callables (owner only):
 *   addMemberV1      { email, name?, role: "manager" | "staff", opId }
 *   setMemberRoleV1  { uid, role: "manager" | "staff", opId }
 *   removeMemberV1   { uid, opId }
 *
 * The caller's company and role are read from their login's current claims
 * on the server (never from the request, and never from a possibly stale
 * ID token). The work itself is in teamCore.ts, shared with snaAdmin.js.
 *
 * A new login has no password. After addMemberV1 returns "created", the
 * dashboard calls sendPasswordResetEmail(email) on the client, and Firebase
 * emails the person the standard link to choose their own password.
 */

import {onCall, HttpsError, type CallableRequest} from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import {getAuth} from "firebase-admin/auth";
import {db, REGION} from "./firebase";
import {readMemberClaims, type MemberClaims, type Role} from "./members";
import {requireOpId, runOnce} from "./ops";
import {addMemberToCompany, removeMember, requireUid, setMemberRole, type AddMemberResult} from "./teamCore";

function body(request: CallableRequest<unknown>): Record<string, unknown> {
  const data = request.data;
  if (typeof data !== "object" || data === null || Array.isArray(data)) {
    throw new HttpsError("invalid-argument", "Request body must be an object.");
  }
  return data as Record<string, unknown>;
}

interface Caller extends MemberClaims {
  uid: string;
}

/** The caller, who must be a company owner, from their login as it is right now. */
async function requireOwner(request: CallableRequest<unknown>): Promise<Caller> {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const uid = request.auth.uid;
  const user = await getAuth().getUser(uid);
  // A login without labels is the owner of its own company (pre multi-user).
  const member = readMemberClaims(user.customClaims) ?? {companyId: uid, role: "owner" as Role};
  if (member.role !== "owner") throw new HttpsError("permission-denied", "Only the company owner can manage the team.");
  return {uid, ...member};
}

export const addMemberV1 = onCall({region: REGION}, async (request): Promise<AddMemberResult> => {
  const data = body(request);
  const owner = await requireOwner(request);
  const opId = requireOpId(data.opId);
  const input = {email: data.email as string, name: data.name as string | undefined, role: data.role as "staff"};
  return runOnce(owner.companyId, opId, "addMember", owner.uid, async () => {
    const result = await addMemberToCompany(getAuth(), db, owner.companyId, owner.uid, input);
    logger.info("Team member added", {companyId: owner.companyId, ...result});
    return result;
  });
});

export const setMemberRoleV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const owner = await requireOwner(request);
  const opId = requireOpId(data.opId);
  const uid = requireUid(data.uid);
  return runOnce(owner.companyId, opId, "setMemberRole", owner.uid, async () => {
    const result = await setMemberRole(getAuth(), db, owner.companyId, owner.uid, uid, data.role);
    logger.info("Team member role changed", {companyId: owner.companyId, ...result});
    return result;
  });
});

export const removeMemberV1 = onCall({region: REGION}, async (request) => {
  const data = body(request);
  const owner = await requireOwner(request);
  const opId = requireOpId(data.opId);
  const uid = requireUid(data.uid);
  return runOnce(owner.companyId, opId, "removeMember", owner.uid, async () => {
    const result = await removeMember(getAuth(), db, owner.companyId, owner.uid, uid);
    logger.info("Team member removed", {companyId: owner.companyId, ...result});
    return result;
  });
});
