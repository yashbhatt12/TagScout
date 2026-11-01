/**
 * Company membership: which company a login belongs to, and its role.
 *
 * Source of truth is the login's custom claims, set only by the server:
 *   { companyId: "<company id>", role: "owner" | "manager" | "staff" }
 * The security rules read them from the ID token. A copy lives in
 * /companies/{companyId}/members/{uid} so the Team page can list people
 * (the claims can't be listed).
 *
 * Takes Auth and Firestore as parameters (rather than importing them)
 * so the admin scripts can pass their own.
 */

import type {Auth} from "firebase-admin/auth";
import {FieldValue, type Firestore} from "firebase-admin/firestore";

export const ROLES = ["owner", "manager", "staff"] as const;
export type Role = (typeof ROLES)[number];

export interface MemberClaims {
  companyId: string;
  role: Role;
}

export function isRole(value: unknown): value is Role {
  return typeof value === "string" && (ROLES as readonly string[]).includes(value);
}

/** The company and role in a login's claims, or null if it has none yet. */
export function readMemberClaims(claims: Record<string, unknown> | undefined): MemberClaims | null {
  const companyId = claims?.companyId;
  const role = claims?.role;
  if (typeof companyId !== "string" || companyId === "" || !isRole(role)) return null;
  return {companyId, role};
}

export function memberRef(db: Firestore, companyId: string, uid: string) {
  return db.collection("companies").doc(companyId).collection("members").doc(uid);
}

/**
 * Makes [uid] the owner of its own company (companyId == uid), the way every
 * login worked before multi-user: sets the claims and the members entry.
 *
 * Never moves a login between companies: if the login already has a company
 * in its claims, nothing changes and "kept" is returned.
 */
export async function makeOwnerOfOwnCompany(
  auth: Auth,
  db: Firestore,
  uid: string,
  profile: { name?: unknown; email?: unknown } = {},
): Promise<"set" | "kept"> {
  const user = await auth.getUser(uid);
  const existing = user.customClaims ?? {};
  if (readMemberClaims(existing)) return "kept";

  await auth.setCustomUserClaims(uid, {...existing, companyId: uid, role: "owner"});

  const text = (v: unknown) => (typeof v === "string" ? v.trim() : "");
  await memberRef(db, uid, uid).set(
    {
      uid,
      email: user.email ?? text(profile.email),
      name: text(profile.name) || user.displayName || "",
      role: "owner",
      status: "active",
      addedAt: FieldValue.serverTimestamp(),
      addedBy: "signup",
    },
    {merge: true},
  );
  return "set";
}
