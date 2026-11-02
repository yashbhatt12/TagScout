/**
 * In-memory stand-in for Firestore and Firebase Auth, for fast tests of the
 * server code with no emulator and no network.
 *
 * Load it FIRST in a test file (before ../lib/index.js): it patches the real
 * Admin SDK classes so the functions run unchanged against memory.
 *
 *   const fake = require("./fake");
 *   const fns = require("../lib/index.js");
 *   fake.reset({ "companies/c1/members/u1": { role: "staff", status: "active" } });
 *   await fns.enrollUnitsV1.run(fake.request("u1", { companyId: "c1", role: "staff" }, data));
 *
 * It covers what the functions use: get/set/update/create/delete, add and
 * auto ids, where/limit/count queries, transactions, batches,
 * FieldValue.delete, and the Auth calls the team code makes. Timestamps and
 * server timestamps are stored as "<ts>". It doesn't model concurrency,
 * indexes or security rules (the rules tests in test-rules/ cover those).
 */

process.env.GCLOUD_PROJECT = process.env.GCLOUD_PROJECT || "demo-tagscout";
process.env.FIREBASE_CONFIG = process.env.FIREBASE_CONFIG || JSON.stringify({projectId: "demo-tagscout"});

const fs = require("firebase-admin/firestore");
const {Auth} = require("firebase-admin/auth");

const state = {docs: {}, users: {}, autoId: 0, revoked: [], emails: []};
const DELETE = fs.FieldValue.delete();

const isDelete = (v) => v && typeof v.isEqual === "function" && v.isEqual(DELETE);
const store = (v) => JSON.parse(JSON.stringify(v ?? null, (k, x) => {
  if (x && x.constructor && /Timestamp/.test(x.constructor.name)) return "<ts>";
  if (x && x.constructor && /FieldValue|Sentinel/.test(x.constructor.name)) return "<ts>";
  return x;
}));
const merge = (path, data) => {
  const current = {...(state.docs[path] || {})};
  for (const [k, v] of Object.entries(data)) {
    const kind = v && v.constructor ? v.constructor.name : "";
    if (isDelete(v)) delete current[k];
    else if (kind === "NumericIncrementTransform") current[k] = (Number(current[k]) || 0) + v.operand;
    else if (kind === "ArrayUnionTransform") current[k] = [...new Set([...(current[k] || []), ...v.elements])];
    else current[k] = store(v);
  }
  state.docs[path] = current;
};
const snapshot = (path) => {
  const d = state.docs[path];
  return {exists: !!d, id: path.split("/").pop(), ref: fs.getFirestore().doc(path), get: (f) => d && d[f], data: () => d};
};
const childPaths = (col) => Object.keys(state.docs)
  .filter((p) => p.startsWith(col + "/") && p.split("/").length === col.split("/").length + 1);
const runQuery = (q) => {
  const o = q._queryOptions;
  let paths = childPaths(o.parentPath.append(o.collectionId).relativeName);
  for (const f of o.filters || []) paths = paths.filter((p) => state.docs[p][f.field.formattedName] === f.value);
  if (o.limit) paths = paths.slice(0, o.limit);
  return paths;
};

// ── Firestore ─────────────────────────────────────────────────────
const D = fs.DocumentReference.prototype;
D.get = async function () { return snapshot(this.path); };
D.set = async function (d, o) { if (o && o.merge) merge(this.path, d); else { state.docs[this.path] = {}; merge(this.path, d); } };
D.update = async function (d) { if (!state.docs[this.path]) throw Object.assign(new Error("NOT_FOUND"), {code: 5}); merge(this.path, d); };
D.create = async function (d) { if (this.path in state.docs) throw Object.assign(new Error("ALREADY_EXISTS"), {code: 6}); state.docs[this.path] = {}; merge(this.path, d); };
D.delete = async function () { delete state.docs[this.path]; };

const Col = fs.CollectionReference.prototype;
const realDoc = Col.doc;
Col.doc = function (id) { return id === undefined ? realDoc.call(this, "auto" + (++state.autoId)) : realDoc.call(this, id); };
Col.add = async function (d) { const ref = this.doc(); state.docs[ref.path] = {}; merge(ref.path, d); return ref; };
Col.listDocuments = async function () {
  const depth = this.path.split("/").length;
  const ids = new Set(Object.keys(state.docs).filter((p) => p.startsWith(this.path + "/")).map((p) => p.split("/")[depth]));
  return [...ids].map((id) => this.doc(id));
};

fs.Query.prototype.get = async function () { const docs = runQuery(this).map(snapshot); return {docs, size: docs.length, empty: docs.length === 0}; };
fs.Query.prototype.count = function () { const q = this; return {get: async () => ({data: () => ({count: runQuery(q).length})})}; };

fs.Firestore.prototype.runTransaction = async function (fn) {
  const writes = [];
  const txn = {
    get: async (ref) => (ref._queryOptions ? fs.Query.prototype.get.call(ref) : snapshot(ref.path)),
    set: (ref, d, o) => { writes.push(() => (o && o.merge ? merge(ref.path, d) : ((state.docs[ref.path] = {}), merge(ref.path, d)))); return txn; },
    update: (ref, d) => { writes.push(() => merge(ref.path, d)); return txn; },
    delete: (ref) => { writes.push(() => delete state.docs[ref.path]); return txn; },
  };
  const result = await fn(txn);
  writes.forEach((w) => w()); // all or nothing: nothing is written if fn throws
  return result;
};

const B = fs.WriteBatch.prototype;
const queue = (b, w) => { (b.__writes = b.__writes || []).push(w); return b; };
B.set = function (ref, d, o) { return queue(this, () => (o && o.merge ? merge(ref.path, d) : ((state.docs[ref.path] = {}), merge(ref.path, d)))); };
B.update = function (ref, d) { return queue(this, () => merge(ref.path, d)); };
B.delete = function (ref) { return queue(this, () => delete state.docs[ref.path]); };
B.commit = async function () { (this.__writes || []).forEach((w) => w()); return []; };

// ── Auth ──────────────────────────────────────────────────────────
const notFound = () => Object.assign(new Error("no user"), {code: "auth/user-not-found"});
const copy = (u) => ({...u, customClaims: u.customClaims ? {...u.customClaims} : u.customClaims});
Auth.prototype.getUser = async (uid) => { if (!state.users[uid]) throw notFound(); return copy(state.users[uid]); };
Auth.prototype.getUserByEmail = async (email) => {
  const u = Object.values(state.users).find((x) => x.email === email);
  if (!u) throw notFound();
  return copy(u);
};
Auth.prototype.createUser = async (p) => {
  const uid = "user" + (++state.autoId);
  state.users[uid] = {uid, email: p.email, emailVerified: !!p.emailVerified, displayName: p.displayName, customClaims: undefined, disabled: false};
  return copy(state.users[uid]);
};
Auth.prototype.setCustomUserClaims = async (uid, c) => { state.users[uid].customClaims = c; };
Auth.prototype.updateUser = async (uid, p) => { Object.assign(state.users[uid], p); return copy(state.users[uid]); };
Auth.prototype.revokeRefreshTokens = async (uid) => { state.revoked.push(uid); };

// ── Helpers for tests ─────────────────────────────────────────────

/** Clears everything and loads [docs] (path → data) and [users] (uid → user). */
function reset(docs = {}, users = {}) {
  state.docs = JSON.parse(JSON.stringify(docs));
  state.users = JSON.parse(JSON.stringify(users));
  state.autoId = 0;
  state.revoked = [];
}

let opCounter = 0;
/** A callable request from [uid] whose token carries [claims]; adds a fresh opId unless data has one. */
function request(uid, claims, data) {
  return {data: {opId: `op-test-${String(++opCounter).padStart(6, "0")}`, ...data}, auth: uid ? {uid, token: claims || {}} : undefined, rawRequest: {}};
}

/** An active team member: the members doc plus matching claims, ready for request(). */
function member(companyId, uid, role) {
  state.docs[`companies/${companyId}/members/${uid}`] = {uid, role, status: "active"};
  return {uid, claims: {companyId, role}};
}

const doc = (path) => state.docs[path];
const paths = (prefix) => Object.keys(state.docs).filter((p) => p.startsWith(prefix));

/** Asserts the promise rejects with an HttpsError of [code]; returns the error. */
async function rejects(promise, code) {
  try {
    await promise;
  } catch (e) {
    if (e.code !== code) throw new Error(`Expected ${code}, got ${e.code}: ${e.message}`);
    return e;
  }
  throw new Error(`Expected ${code}, but it succeeded`);
}

module.exports = {state, reset, request, member, doc, paths, rejects};
