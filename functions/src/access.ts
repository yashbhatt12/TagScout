/**
 * Who is calling a write function, and may they?
 *
 * The company and role come from the login's ID token claims (signed by
 * Firebase, so they can't be forged). Because a token can be up to an hour
 * old, every write also checks the members list: a person removed from the
 * team is refused at once, not when their token expires.
 */

import {HttpsError, type CallableRequest} from "firebase-functions/v2/https";
import {db} from "./firebase";
import {memberRef, readMemberClaims, type MemberClaims, type Role} from "./members";

export interface CallerMember extends MemberClaims {
  uid: string;
}

export async function requireMember(request: CallableRequest<unknown>, roles?: Role[]): Promise<CallerMember> {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const uid = request.auth.uid;
  const claims = readMemberClaims(request.auth.token as Record<string, unknown>);
  if (!claims) {
    throw new HttpsError("permission-denied", "This login isn't part of a company. Contact SNA support.");
  }
  const member = await memberRef(db, claims.companyId, uid).get();
  if (!member.exists || member.get("status") !== "active") {
    throw new HttpsError("permission-denied", "This login no longer has access to the company.");
  }
  if (roles && !roles.includes(claims.role)) {
    throw new HttpsError("permission-denied", "Your role doesn't allow this.");
  }
  return {uid, ...claims};
}

/** The request body as an object, or a clear error. */
export function body(request: CallableRequest<unknown>): Record<string, unknown> {
  const data = request.data;
  if (typeof data !== "object" || data === null || Array.isArray(data)) {
    throw new HttpsError("invalid-argument", "Request body must be an object.");
  }
  return data as Record<string, unknown>;
}
