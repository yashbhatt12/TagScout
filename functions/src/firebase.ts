/**
 * The one Firebase Admin app for every function. Imported before any
 * function module, so initializeApp() always runs first.
 *
 * No projectId: the functions use whichever project they're deployed to.
 */
import {initializeApp} from "firebase-admin/app";
import {getFirestore} from "firebase-admin/firestore";

initializeApp();

export const db = getFirestore();

/** Every function runs next to the Firestore database, in Mumbai. */
export const REGION = "asia-south1";
