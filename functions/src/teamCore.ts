/**
 * Team and company operations shared by the callable functions (team.ts)
 * and SNA's admin script (scripts/snaAdmin.js, which uses the built copy in
 * lib/). Takes Auth and Firestore as parameters so both can pass their own.
 *
 * Errors are HttpsErrors with plain-language messages: the callables pass
 * them straight to the app or dashboard, and the script prints them.
 *
 * Rules every path follows:
 *   - a login is never moved between companies
 *   - each company has exactly one owner, its original login
 *   - removed people are disabled, not deleted, so history keeps a real person
 *   - new logins have no password; the person sets one from Firebase's
 *     "set your password" email
 */

import {HttpsError} from "firebase-functions/v2/https";
import type {Auth, UserRecord} from "firebase-admin/auth";
import {FieldValue, type Firestore} from "firebase-admin/firestore";
import {memberRef, readMemberClaims, makeOwnerOfOwnCompany, type MemberClaims, type Role} from "./members";
import {setUpCompany} from "./companySetup";
import {PRESETS} from "./templates";

export type AssignableRole = Exclude<Role, "owner">;
export const ASSIGNABLE_ROLES: AssignableRole[] = ["manager", "staff"];
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// ── Input checks ──────────────────────────────────────────────────

export function requireRole(value: unknown): AssignableRole {
  if (typeof value === "string" && (ASSIGNABLE_ROLES as string[]).includes(value)) return value as AssignableRole;
  throw new HttpsError("invalid-argument", "Role must be manager or staff.");
}

export function requireEmail(value: unknown): string {
  const email = typeof value === "string" ? value.trim().toLowerCase() : "";
  if (!EMAIL.test(email) || email.length > 254) throw new HttpsError("invalid-argument", "Enter a valid email address.");
  return email;
}

export function optionalText(value: unknown, what: string, max = 100): string {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string" || value.length > max) {
    throw new HttpsError("invalid-argument", `${what} must be text, up to ${max} characters.`);
  }
  return value.trim();
}

export function requireUid(value: unknown): string {
  if (typeof value !== "string" || value.length < 1 || value.length > 128) {
    throw new HttpsError("invalid-argument", "Missing person.");
  }
  return value;
}

async function userByEmail(auth: Auth, email: string): Promise<UserRecord | null> {
  try {
    return await auth.getUserByEmail(email);
  } catch (e) {
    if ((e as { code?: string }).code === "auth/user-not-found") return null;
    throw e;
  }
}

/** Who is acting: a login's uid, or "sna-admin" for the admin script. */
export type Actor = string;

// ── Create a company (SNA only) ───────────────────────────────────

export interface CreateCompanyInput {
  ownerEmail: string;
  ownerName?: string;
  companyName: string;
  mobile?: string;
  preset: string;
  extraTypes?: string[];
}

export interface CreateCompanyResult {
  companyId: string;
  ownerUid: string;
  email: string;
  preset: string;
  categories: number;
}

/**
 * A new company with its owner login: settings from [preset], the owner
 * labelled and listed, and the profile doc the app reads. Refuses if the
 * email already has a login.
 */
export async function createCompany(
  auth: Auth,
  db: Firestore,
  actor: Actor,
  input: CreateCompanyInput,
): Promise<CreateCompanyResult> {
  const email = requireEmail(input.ownerEmail);
  const ownerName = optionalText(input.ownerName, "Owner name");
  const companyName = optionalText(input.companyName, "Company name");
  const mobile = optionalText(input.mobile, "Mobile", 20);
  if (!companyName) throw new HttpsError("invalid-argument", "Enter the company name.");
  if (!PRESETS[input.preset]) {
    throw new HttpsError("invalid-argument", `Unknown preset '${input.preset}'. Known: ${Object.keys(PRESETS).join(", ")}.`);
  }
  if (await userByEmail(auth, email)) {
    throw new HttpsError("already-exists", `${email} already has a TagScout login.`);
  }

  const user = await auth.createUser({email, emailVerified: true, displayName: ownerName || undefined});
  const uid = user.uid; // companyId == owner's uid
  // The company record: its name, readable by everyone in the company.
  await db.collection("companies").doc(uid).set(
    {name: companyName, createdAt: FieldValue.serverTimestamp(), createdBy: actor},
    {merge: true},
  );
  const setUp = await setUpCompany(db, uid, {preset: input.preset, extraTypes: input.extraTypes ?? []});
  await makeOwnerOfOwnCompany(auth, db, uid, {name: ownerName, email});
  // Profile doc the app reads. Written last: the signup trigger then finds
  // settings and labels already in place and changes nothing.
  await db.collection("users").doc(uid).set({
    name: ownerName,
    email,
    mobile,
    companyName,
    companyId: uid,
    role: "owner",
    createdAt: FieldValue.serverTimestamp(),
    createdBy: actor,
  });
  await memberRef(db, uid, uid).set({addedBy: actor}, {merge: true});
  return {companyId: uid, ownerUid: uid, email, preset: input.preset, categories: setUp.categories};
}

// ── Add a person ──────────────────────────────────────────────────

export interface AddMemberInput {
  email: string;
  name?: string;
  role: AssignableRole;
}

export interface AddMemberResult {
  uid: string;
  email: string;
  role: AssignableRole;
  /** created: new login. restored: a person removed earlier was brought back. already: no change needed. */
  status: "created" | "restored" | "already";
}

export async function addMemberToCompany(
  auth: Auth,
  db: Firestore,
  companyId: string,
  actor: Actor,
  input: AddMemberInput,
): Promise<AddMemberResult> {
  const email = requireEmail(input.email);
  const name = optionalText(input.name, "Name");
  const role = requireRole(input.role);

  const existing = await userByEmail(auth, email);
  if (existing) {
    const claims = readMemberClaims(existing.customClaims);
    if (claims && claims.companyId !== companyId) {
      throw new HttpsError("already-exists", "This email already has its own TagScout login. Contact SNA support to move it.");
    }
    if (claims && claims.companyId === companyId) {
      if (claims.role === "owner") throw new HttpsError("already-exists", "That's the owner's own login.");
      return {uid: existing.uid, email, role: claims.role as AssignableRole, status: "already"};
    }
    // No labels: bring back only someone removed from this company before.
    const previous = await memberRef(db, companyId, existing.uid).get();
    if (!previous.exists) {
      throw new HttpsError("already-exists", "This email already has a TagScout login. Contact SNA support to add it.");
    }
    await auth.updateUser(existing.uid, {disabled: false});
    await auth.setCustomUserClaims(existing.uid, {companyId, role});
    await memberRef(db, companyId, existing.uid).set(
      {role, status: "active", name: name || previous.get("name") || "", restoredAt: FieldValue.serverTimestamp(), restoredBy: actor},
      {merge: true},
    );
    await db.collection("users").doc(existing.uid).set({companyId, role}, {merge: true});
    return {uid: existing.uid, email, role, status: "restored"};
  }

  // Marked verified: the inviter vouches for the address, and the password
  // link only works from that inbox.
  const user = await auth.createUser({email, emailVerified: true, displayName: name || undefined});
  await auth.setCustomUserClaims(user.uid, {companyId, role});
  await memberRef(db, companyId, user.uid).set({
    uid: user.uid,
    email,
    name,
    role,
    status: "active",
    addedAt: FieldValue.serverTimestamp(),
    addedBy: actor,
  });
  // Profile doc. companyId != uid, so the signup trigger skips company set-up.
  await db.collection("users").doc(user.uid).set({
    name,
    email,
    companyId,
    role,
    createdAt: FieldValue.serverTimestamp(),
    createdBy: actor,
  });
  return {uid: user.uid, email, role, status: "created"};
}

// ── Change role / remove ──────────────────────────────────────────

/** The person [uid], who must belong to [companyId] and not be its owner. */
export async function teamMember(
  auth: Auth,
  companyId: string,
  uid: string,
): Promise<{ user: UserRecord; claims: MemberClaims }> {
  if (uid === companyId) throw new HttpsError("failed-precondition", "The owner's own login can't be changed here.");
  let user: UserRecord;
  try {
    user = await auth.getUser(uid);
  } catch {
    throw new HttpsError("not-found", "That person doesn't have a login.");
  }
  const claims = readMemberClaims(user.customClaims);
  if (!claims || claims.companyId !== companyId) throw new HttpsError("not-found", "That person isn't in your team.");
  if (claims.role === "owner") throw new HttpsError("failed-precondition", "The owner can't be changed here.");
  return {user, claims};
}

export async function setMemberRole(
  auth: Auth,
  db: Firestore,
  companyId: string,
  actor: Actor,
  uid: string,
  roleInput: unknown,
): Promise<{ uid: string; role: AssignableRole }> {
  const role = requireRole(roleInput);
  const {user} = await teamMember(auth, companyId, uid);
  await auth.setCustomUserClaims(uid, {...(user.customClaims ?? {}), companyId, role});
  await memberRef(db, companyId, uid).set({role, updatedAt: FieldValue.serverTimestamp(), updatedBy: actor}, {merge: true});
  await db.collection("users").doc(uid).set({role}, {merge: true});
  return {uid, role};
}

export async function removeMember(
  auth: Auth,
  db: Firestore,
  companyId: string,
  actor: Actor,
  uid: string,
): Promise<{ uid: string; status: "removed" }> {
  await teamMember(auth, companyId, uid);
  await auth.updateUser(uid, {disabled: true});
  await auth.setCustomUserClaims(uid, {});
  await auth.revokeRefreshTokens(uid);
  await memberRef(db, companyId, uid).set(
    {status: "removed", removedAt: FieldValue.serverTimestamp(), removedBy: actor},
    {merge: true},
  );
  return {uid, status: "removed"};
}
