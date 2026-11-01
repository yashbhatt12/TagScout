/**
 * Duplicate protection for callable functions.
 *
 * Every write callable takes an `opId`: a random ID the app or dashboard
 * creates once per user action (one tap on Save). Phones on weak networks
 * retry; when the same opId arrives again, the work is not repeated and the
 * first call's result is returned instead.
 *
 * Records live in /companies/{companyId}/ops/{opId}, closed to clients.
 */

import {FieldValue} from "firebase-admin/firestore";
import {HttpsError} from "firebase-functions/v2/https";
import {db} from "./firebase";

const ALREADY_EXISTS = 6;
const OP_ID = /^[A-Za-z0-9_-]{8,128}$/;

export function requireOpId(value: unknown): string {
  if (typeof value !== "string" || !OP_ID.test(value)) {
    throw new HttpsError("invalid-argument", "Missing or invalid opId (8–128 letters, digits, - or _).");
  }
  return value;
}

/**
 * Runs [work] once per (company, opId). A repeat of a finished call returns
 * the stored result; a repeat while the first is still running is told to
 * wait; a failed call can be retried with the same opId.
 */
export async function runOnce<T>(
  companyId: string,
  opId: string,
  kind: string,
  callerUid: string,
  work: () => Promise<T>,
): Promise<T> {
  const ref = db.collection("companies").doc(companyId).collection("ops").doc(opId);
  try {
    await ref.create({kind, status: "running", by: callerUid, startedAt: FieldValue.serverTimestamp()});
  } catch (e) {
    if ((e as { code?: unknown }).code !== ALREADY_EXISTS) throw e;
    const existing = await ref.get();
    if (existing.get("kind") !== kind) {
      throw new HttpsError("invalid-argument", "This opId was already used for a different action.");
    }
    if (existing.get("status") === "done") return existing.get("result") as T;
    throw new HttpsError("aborted", "This request is already being processed. Try again in a moment.");
  }

  try {
    const result = await work();
    await ref.update({status: "done", result: result ?? null, finishedAt: FieldValue.serverTimestamp()});
    return result;
  } catch (e) {
    await ref.delete().catch(() => undefined); // let the same opId be retried
    throw e;
  }
}
