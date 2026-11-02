// Team management, company creation (snaAdmin) and the signup trigger.
const fake = require("./fake");
const {test} = require("node:test");
const assert = require("node:assert/strict");
const {getAuth} = require("firebase-admin/auth");
const {getFirestore} = require("firebase-admin/firestore");
const fns = require("../lib/index.js");
const core = require("../lib/teamCore.js");

const C = "own";
const users = () => ({
  own: {uid: "own", email: "owner@shop.in", customClaims: {companyId: C, role: "owner"}, disabled: false},
  mgr: {uid: "mgr", email: "mgr@shop.in", customClaims: {companyId: C, role: "manager"}, disabled: false},
  other: {uid: "other", email: "other@else.in", customClaims: {companyId: "other", role: "owner"}, disabled: false},
});
const call = (fn, uid, data) => fns[fn].run(fake.request(uid, {}, data));
const claims = (uid) => fake.state.users[uid].customClaims;

test("only the owner manages the team", async () => {
  fake.reset({}, users());
  await fake.rejects(fns.addMemberV1.run(fake.request(null, null, {email: "a@b.in", role: "staff"})), "unauthenticated");
  await fake.rejects(call("addMemberV1", "mgr", {email: "a@b.in", role: "staff"}), "permission-denied");
  await fake.rejects(call("addMemberV1", "own", {email: "a@b.in", role: "owner"}), "invalid-argument");
});

test("add, retry, change role, remove, add back", async () => {
  fake.reset({}, users());
  const req = fake.request("own", {}, {email: " Ravi@Shop.in ", name: "Ravi", role: "staff"});
  const added = await fns.addMemberV1.run(req);
  assert.equal(added.status, "created");
  assert.equal((await fns.addMemberV1.run(req)).uid, added.uid, "retry returns the first answer");
  assert.deepEqual(claims(added.uid), {companyId: C, role: "staff"});
  assert.equal(fake.doc(`users/${added.uid}`).companyId, C);
  assert.equal((await call("addMemberV1", "own", {email: "ravi@shop.in", role: "staff"})).status, "already");
  await fake.rejects(call("addMemberV1", "own", {email: "other@else.in", role: "staff"}), "already-exists");
  await call("setMemberRoleV1", "own", {uid: added.uid, role: "manager"});
  assert.equal(claims(added.uid).role, "manager");
  await fake.rejects(call("setMemberRoleV1", "own", {uid: "own", role: "staff"}), "failed-precondition");
  await call("removeMemberV1", "own", {uid: added.uid});
  assert.equal(fake.state.users[added.uid].disabled, true);
  assert.deepEqual(claims(added.uid), {});
  assert.equal(fake.doc(`companies/${C}/members/${added.uid}`).status, "removed");
  assert.equal((await call("addMemberV1", "own", {email: "ravi@shop.in", role: "staff"})).status, "restored");
});

test("snaAdmin create-company sets up the owner, preset and company name", async () => {
  fake.reset();
  const r = await core.createCompany(getAuth(), getFirestore(), "sna-admin",
    {ownerEmail: "Boss@Shop.in", ownerName: "Mehul", companyName: "Shah Jewellers", preset: "jewellery"});
  assert.deepEqual(claims(r.ownerUid), {companyId: r.companyId, role: "owner"});
  assert.equal(fake.doc(`companies/${r.companyId}/settings/config`).template, "jewellery");
  assert.equal(fake.doc(`companies/${r.companyId}`).name, "Shah Jewellers");
  await assert.rejects(core.createCompany(getAuth(), getFirestore(), "sna-admin",
    {ownerEmail: "boss@shop.in", companyName: "X", preset: "jewellery"}), /already has a TagScout login/);
});

test("signup trigger: new owner set up; staff logins skipped; existing labels kept", async () => {
  fake.reset({}, {newbie: {uid: "newbie", email: "n@x.in"}, staffer: {uid: "staffer", email: "s@x.in"}});
  await fns.setUpNewCompany.run({data: {data: () => ({name: "N"})}, params: {uid: "newbie"}});
  assert.deepEqual(claims("newbie"), {companyId: "newbie", role: "owner"});
  assert.equal(fake.doc("companies/newbie/settings/config").template, "standard");
  await fns.setUpNewCompany.run({data: {data: () => ({companyId: C})}, params: {uid: "staffer"}});
  assert.equal(claims("staffer"), undefined);
  assert.equal(fake.paths("companies/staffer").length, 0);
});
