import { initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";
import { collection, doc, getFirestore } from "firebase/firestore";
import { getFunctions } from "firebase/functions";

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY as string | undefined,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN as string | undefined,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID as string | undefined,
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET as string | undefined,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID as string | undefined,
  appId: import.meta.env.VITE_FIREBASE_APP_ID as string | undefined,
};

const missing = Object.entries(firebaseConfig)
  .filter(([, value]) => !value)
  .map(([key]) => key);

if (missing.length > 0) {
  throw new Error(`Missing Firebase settings in web/.env.local: ${missing.join(", ")}`);
}

export const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const db = getFirestore(app);
/** Callable functions run in Mumbai, next to the database. */
export const functions = getFunctions(app, "asia-south1");

/**
 * Company data lives under companies/{companyId}. The company comes from the
 * login's claims (see AuthProvider); older logins without claims use their uid.
 */
export function companyCol(companyId: string, ...segments: string[]) {
  return collection(db, "companies", companyId, ...segments);
}

export function companyDoc(companyId: string, ...segments: string[]) {
  return doc(db, "companies", companyId, ...segments);
}
