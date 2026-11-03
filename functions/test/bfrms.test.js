// Readers that post straight to TagScout (Bluebird BFRMS) and door monitoring.
const fake = require("./fake");
const {test} = require("node:test");
const assert = require("node:assert/strict");
const {getFirestore} = require("firebase-admin/firestore");
const fns = require("../lib/index.js");
const {checkDoorMonitoring, parseBfrms} = require("../lib/bfrms.js");

const C = "c1";
const E = (n) => "E2000000000000000000" + String(n).padStart(4, "0");

function setUp() {
  fake.reset({
    [`companies/${C}/warehouses/w1`]: {name: "Andheri Store"},
    [`companies/${C}/inventory_units/${E(1)}`]: {status: "in_stock", sku: "JW-1", productId: "p1", price: 52000, currentWarehouseId: "w1"},
    [`companies/${C}/products/p1`]: {title: "Temple Necklace"},
  });
  return fake.member(C, "mg", "manager");
}
const callReaders = (who, data) => fns.readersV1.run(fake.request(who.uid, who.claims, data));

/** Calls the HTTPS function like the reader would. */
async function post({c = C, r, k, basic, body, method = "POST"}) {
  const headers = basic ? {authorization: "Basic " + Buffer.from(`${basic[0]}:${basic[1]}`).toString("base64")} : {};
  const req = {method, query: {c, r, ...(k ? {k} : {})}, body, get: (h) => headers[h.toLowerCase()], header: (h) => headers[h.toLowerCase()], headers};
  const out = {code: 200, payload: null};
  const res = {
    status(code) { out.code = code; return res; }, send(p) { out.payload = p; return res; }, json(p) { out.payload = p; return res; },
    set() { return res; }, setHeader() { return res; }, getHeader() { return undefined; }, end() { return res; }, on() { return res; },
  };
  await fns.bfrmsIngest(req, res);
  return out;
}
// Straight from the BFRMS manual (Single mode, HTTP POST example)
const bfrmsRead = (epc, antenna, rssi = -42) => ({type: "Simple", timestamp: "2023-10-16T07:36:31Z", data: {epc, antenna, peakRssi: rssi}});

async function directReader(who) {
  const created = await callReaders(who, {action: "createDirect", name: "Main entrance", storeId: "w1", ports: 4});
  await callReaders(who, {action: "configure", readerId: created.readerId, name: "Main entrance", storeId: "w1",
    antennaRoles: {"1": "in", "2": "out", "3": "off", "4": "off"}, heartbeatEpc: E(900).toLowerCase(), enabled: true});
  return created;
}

test("parses the manual's JSON, lists of messages, and skips keepalives and events", () => {
  const {reads, other} = parseBfrms([bfrmsRead("e2801191a50201800007380e", 1, -37),
    {type: "Simple", timestamp: "2023-10-19T03:01:07Z", command: "keepalive"},
    {type: "Custom", event: "inventory start", payload: {}}, bfrmsRead("xyz", 2)], 1000);
  assert.deepEqual(reads, [{epc: "E2801191A50201800007380E", antenna: 1, rssi: -37, at: 1000}]);
  assert.deepEqual(other, ["keepalive", "inventory start"]);
});

test("createDirect returns the URL and login once; only a hash is stored", async () => {
  const who = setUp();
  const staff = fake.member(C, "st", "staff");
  await fake.rejects(callReaders(staff, {action: "createDirect", name: "X", storeId: "w1"}), "permission-denied");
  await fake.rejects(callReaders(who, {action: "createDirect", name: "X", storeId: "nope"}), "invalid-argument");
  const r = await callReaders(who, {action: "createDirect", name: "Main entrance", storeId: "w1", ports: 8});
  assert.match(r.url, /^https:\/\/asia-south1-.+\.cloudfunctions\.net\/bfrmsIngest\?c=c1&r=bf-/);
  assert.equal(r.username, r.readerId);
  const doc = fake.doc(`companies/${C}/readers/${r.readerId}`);
  assert.equal(doc.connectionType, "bfrms");
  assert.equal(doc.antennas.length, 8);
  assert.equal(doc.enabled, false);
  assert.ok(doc.ingestKeyHash && !JSON.stringify(doc).includes(r.password), "the key itself is never stored");
});

test("refuses wrong keys, unknown readers and non-POST", async () => {
  const who = setUp();
  const r = await directReader(who);
  assert.equal((await post({r: r.readerId, k: "wrong", body: bfrmsRead(E(1), 1)})).code, 401);
  assert.equal((await post({r: r.readerId, body: bfrmsRead(E(1), 1)})).code, 401);
  assert.equal((await post({r: "bf-nope", k: r.password, body: bfrmsRead(E(1), 1)})).code, 401);
  assert.equal((await post({r: r.readerId, k: r.password, method: "GET"})).code, 405);
  assert.equal(fake.paths(`companies/${C}/tag_events/`).length, 0);
});

test("a walk-out posted by the reader becomes an alarm, through the normal door processing", async () => {
  const who = setUp();
  const r = await directReader(who);
  assert.equal((await post({r: r.readerId, basic: [r.username, r.password], body: bfrmsRead(E(1), 1)})).code, 200);
  await new Promise((done) => setTimeout(done, 5));
  assert.equal((await post({r: r.readerId, k: r.password, body: JSON.stringify(bfrmsRead(E(1), 2))})).code, 200, "text body accepted");
  const events = fake.paths(`companies/${C}/tag_events/`).map(fake.doc);
  assert.equal(events.length, 2);
  assert.ok(events.every((e) => e.source === "bfrms" && e.readerId === r.readerId));
  let n = 0;
  for (const e of events) await fns.doorEventProcessor.run({data: {data: () => e}, params: {companyId: C, eventId: "ev" + (++n)}});
  const [pass] = fake.paths(`companies/${C}/door_passes/`).map(fake.doc);
  assert.equal(pass.direction, "out");
  assert.equal(pass.verdict, "alarm");
  assert.equal(pass.storeName, "Andheri Store");
  assert.equal(fake.paths(`companies/${C}/alerts/`).length, 1);
});

test("heartbeat-tag reads only mark the reader alive; a switched-off reader is accepted but ignored", async () => {
  const who = setUp();
  const r = await directReader(who);
  await post({r: r.readerId, k: r.password, body: bfrmsRead(E(900), 1)});
  assert.equal(fake.paths(`companies/${C}/tag_events/`).length, 0);
  assert.ok(fake.doc(`companies/${C}/readers/${r.readerId}`).lastHeartbeatAt);
  await callReaders(who, {action: "configure", readerId: r.readerId, name: "Main entrance", storeId: "w1",
    antennaRoles: {"1": "in", "2": "out"}, enabled: false});
  const off = await post({r: r.readerId, k: r.password, body: bfrmsRead(E(1), 1)});
  assert.equal(off.code, 200);
  assert.equal(fake.paths(`companies/${C}/tag_events/`).length, 0);
});

test("a new key replaces the old one", async () => {
  const who = setUp();
  const r = await directReader(who);
  const fresh = await callReaders(who, {action: "newKey", readerId: r.readerId});
  assert.equal((await post({r: r.readerId, k: r.password, body: bfrmsRead(E(1), 1)})).code, 401);
  assert.equal((await post({r: r.readerId, k: fresh.password, body: bfrmsRead(E(1), 1)})).code, 200);
});

test("door monitoring: one alert when a reader goes silent, back to ok when heard", async () => {
  setUp();
  const now = 2_000_000_000_000;
  const R = `companies/${C}/readers`;
  Object.assign(fake.state.docs, {
    [`${R}/bf-a`]: {connectionType: "bfrms", enabled: true, name: "Main entrance", storeName: "Andheri Store", heartbeatEpc: E(900), lastHeartbeatAt: now - 6 * 60_000},
    [`${R}/bf-b`]: {connectionType: "bfrms", enabled: true, name: "No heartbeat tag", heartbeatEpc: ""},
    [`${R}/rdr-c`]: {edgeId: "edge1", enabled: true, name: "Back door", connection: "connected"},
    [`${R}/rdr-d`]: {edgeId: "edge1", enabled: false, name: "Off"},
    [`companies/${C}/edges/edge1`]: {lastSeenAt: now - 10 * 60_000},
  });
  const db = getFirestore();
  const first = await checkDoorMonitoring(db, C, now);
  assert.deepEqual(first, {checked: 3, stopped: 2, resumed: 0});
  const alerts = fake.paths(`companies/${C}/alerts/`).map(fake.doc);
  assert.equal(alerts.length, 2);
  assert.ok(alerts.every((a) => a.kind === "monitoring" && a.severity === "high"));
  assert.ok(alerts.some((a) => a.productTitle === "Door monitoring stopped at Andheri Store"));
  assert.equal((await checkDoorMonitoring(db, C, now + 300_000)).stopped, 0, "no repeat alert while still silent");
  fake.state.docs[`${R}/bf-a`].lastHeartbeatAt = now + 590_000;
  const back = await checkDoorMonitoring(db, C, now + 600_000);
  assert.equal(back.resumed, 1);
  assert.equal(fake.doc(`${R}/bf-a`).monitoring, "ok");
  assert.equal(fake.doc(`${R}/bf-b`).monitoring, undefined, "can't judge without a heartbeat tag");
});
