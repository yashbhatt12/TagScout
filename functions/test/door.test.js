// Fixed reader: tag reads → passes (direction, verdict) → alerts; reader settings.
const fake = require("./fake");
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fns = require("../lib/index.js");

const C = "c1";
const E = (n) => "E2000000000000000000" + String(n).padStart(4, "0");
const T0 = 1_800_000_000_000;

function setUp(readers = {}) {
  fake.reset({
    [`companies/${C}/inventory_units/${E(1)}`]: {status: "in_stock", sku: "JW-1", productId: "p1", price: 52000},
    [`companies/${C}/inventory_units/${E(2)}`]: {status: "sold", sku: "JW-2", productId: "p1"},
    [`companies/${C}/products/p1`]: {title: "Temple Necklace", categoryId: "cat1"},
    [`companies/${C}/categories/cat1`]: {name: "Necklace"},
    [`companies/${C}/readers/door1`]: {name: "Main entrance", location: "Andheri Store", insideAntennas: [1], outsideAntennas: [2], windowMs: 10000},
    ...Object.fromEntries(Object.entries(readers).map(([id, r]) => [`companies/${C}/readers/${id}`, r])),
  });
}
let n = 0;
const read = (epc, antennaPort, at, readerId = "door1", extra = {}) => fns.doorEventProcessor.run({
  data: {data: () => ({epc, antennaPort, rssi: -55, firstSeenAt: at, lastSeenAt: at + 600, readerId, ...extra})},
  params: {companyId: C, eventId: "ev" + (++n)},
});
const passes = () => fake.paths(`companies/${C}/door_passes/`).map(fake.doc);
const alerts = () => fake.paths(`companies/${C}/alerts/`).map(fake.doc);

test("unsold piece seen inside then outside: out, alarm, one alert the app can show", async () => {
  setUp();
  await read(E(1).toLowerCase(), 1, T0);
  assert.equal(passes()[0].verdict, "pending", "only inside so far: not left yet");
  assert.equal(alerts().length, 0);
  await read(E(1), 2, T0 + 900);
  const [pass] = passes();
  assert.equal(passes().length, 1);
  assert.equal(pass.direction, "out");
  assert.equal(pass.verdict, "alarm");
  assert.deepEqual(pass.antennas, [1, 2]);
  assert.equal(pass.eventCount, 2);
  assert.equal(pass.unit.productTitle, "Temple Necklace");
  const [alert] = alerts();
  assert.equal(alerts().length, 1);
  for (const f of ["epc", "itemCode", "category", "price", "readerId", "antennaPort", "rssi", "firstSeenAt", "severity", "read", "createdAt"]) {
    assert.ok(f in alert, `alert has ${f}`);
  }
  assert.equal(alert.itemCode, "JW-1");
  assert.equal(alert.category, "Necklace");
  assert.equal(alert.direction, "out");
  await read(E(1), 2, T0 + 2000); // lingering at the outside antenna
  assert.equal(alerts().length, 1, "still one alert for the pass");
});

test("coming in, sold pieces and unknown tags don't alarm", async () => {
  setUp();
  await read(E(1), 2, T0);
  await read(E(1), 1, T0 + 800);
  assert.equal(passes()[0].direction, "in");
  assert.equal(passes()[0].verdict, "ok");
  await read(E(2), 1, T0 + 60000);
  await read(E(2), 2, T0 + 60800);
  assert.equal(passes().find((p) => p.epc === E(2)).verdict, "ok");
  await read(E(99), 1, T0 + 120000);
  await read(E(99), 2, T0 + 120500);
  assert.equal(passes().find((p) => p.epc === E(99)).verdict, "unknown_tag");
  assert.equal(alerts().length, 0);
});

test("reads further apart than the window are separate passes", async () => {
  setUp();
  await read(E(1), 1, T0);
  await read(E(1), 2, T0 + 30000);
  assert.equal(passes().length, 2);
  assert.ok(passes().every((p) => p.direction === "unknown"));
  assert.equal(alerts().length, 0);
});

test("a reader without in/out settings works as before: any read of unsold stock alarms", async () => {
  setUp({door2: {name: "Back door", insideAntennas: [], outsideAntennas: []}});
  await read(E(1), 3, T0, "door2");
  await read(E(1), 1, T0 + 50000, "unregistered-reader");
  assert.deepEqual(passes().map((p) => [p.readerId, p.direction, p.verdict]),
    [["door2", "passed", "alarm"], ["unregistered-reader", "passed", "alarm"]]);
  assert.equal(alerts().length, 2);
});

test("a disabled reader is ignored", async () => {
  setUp({door3: {name: "Off", insideAntennas: [1], outsideAntennas: [2], enabled: false}});
  await read(E(1), 1, T0, "door3");
  assert.equal(passes().length, 0);
});

test("reader settings: owners and managers, sensible antennas", async () => {
  setUp();
  const staff = fake.member(C, "st", "staff");
  const manager = fake.member(C, "mg", "manager");
  const call = (who, data) => fns.readersV1.run(fake.request(who.uid, who.claims, data));
  await fake.rejects(call(staff, {action: "save", readerId: "door9", name: "X"}), "permission-denied");
  await fake.rejects(call(manager, {action: "save", readerId: "bad id!", name: "X"}), "invalid-argument");
  await fake.rejects(call(manager, {action: "save", readerId: "door9", name: "X", insideAntennas: [1], outsideAntennas: [1]}), "invalid-argument");
  await fake.rejects(call(manager, {action: "save", readerId: "door9", name: "X", insideAntennas: [1]}), "invalid-argument");
  await call(manager, {action: "save", readerId: "fr901-main-exit", name: "Main exit", location: "Andheri", insideAntennas: [1, 3], outsideAntennas: [2, 4]});
  assert.deepEqual(fake.doc(`companies/${C}/readers/fr901-main-exit`).outsideAntennas, [2, 4]);
  await call(manager, {action: "delete", readerId: "fr901-main-exit"});
  assert.equal(fake.doc(`companies/${C}/readers/fr901-main-exit`), undefined);
});
