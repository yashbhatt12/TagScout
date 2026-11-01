/**
 * Team actions: thin wrappers around the server's team functions
 * (functions/src/team.ts). Every call gets a fresh opId, so a request the
 * network retries is applied once.
 */
import { sendPasswordResetEmail } from "firebase/auth";
import { httpsCallable } from "firebase/functions";
import { auth, functions } from "../firebase";

export type AssignableRole = "manager" | "staff";

export type AddMemberResult = {
  uid: string;
  email: string;
  role: AssignableRole;
  status: "created" | "restored" | "already";
};

const newOpId = () => crypto.randomUUID();

export async function addMember(input: { email: string; name: string; role: AssignableRole }): Promise<AddMemberResult> {
  const call = httpsCallable<typeof input & { opId: string }, AddMemberResult>(functions, "addMemberV1");
  return (await call({ ...input, opId: newOpId() })).data;
}

export async function changeRole(uid: string, role: AssignableRole): Promise<void> {
  await httpsCallable(functions, "setMemberRoleV1")({ uid, role, opId: newOpId() });
}

export async function removeMember(uid: string): Promise<void> {
  await httpsCallable(functions, "removeMemberV1")({ uid, opId: newOpId() });
}

/** Firebase's "set your password" email (the same one Forgot password sends). */
export async function sendInvite(email: string): Promise<void> {
  await sendPasswordResetEmail(auth, email, { url: `${window.location.origin}/` });
}

/** The server's own message where there is one; plain words otherwise. */
export function teamErrorMessage(e: unknown): string {
  const code = typeof e === "object" && e !== null && "code" in e ? String(e.code) : "";
  const message = e instanceof Error ? e.message : "";
  if (code === "functions/unavailable" || code === "auth/network-request-failed") {
    return "No connection to the server. Check your internet and try again.";
  }
  if (code === "functions/internal" || !message) return "Something went wrong on the server. Try again.";
  return message;
}
