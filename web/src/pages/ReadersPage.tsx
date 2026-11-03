import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { Badge, Button, Empty, ErrorNote, Loading, PageHeader, Panel } from "../components/ui";
import { Icon } from "../components/Icon";
import { useCompany } from "../lib/CompanyProvider";
import {
  configureReader, createDirectReader, isOnline, newReaderKey, removeReader, requestScan, subscribeEdges, subscribeReaders,
  type AntennaRole, type DirectLogin, type Edge, type ReaderDoc,
} from "../lib/door";
import { fmtDateTime } from "../lib/format";
import { teamErrorMessage } from "../lib/team";

const ago = (d: Date | null, now: number) => {
  if (!d) return "never";
  const s = Math.max(0, Math.round((now - d.getTime()) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  return fmtDateTime(d);
};

export function ReadersPage() {
  const { companyId, role, label, locations } = useCompany();
  const canSetUp = role === "owner" || role === "manager";
  const [edges, setEdges] = useState<Edge[]>();
  const [readers, setReaders] = useState<ReaderDoc[]>();
  const [error, setError] = useState<Error>();
  const [now, setNow] = useState(Date.now());

  useEffect(() => subscribeEdges(companyId, setEdges, setError), [companyId]);
  useEffect(() => subscribeReaders(companyId, setReaders, setError), [companyId]);
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 10_000);
    return () => window.clearInterval(t);
  }, []);

  const storeLabel = label("warehouse");
  return (
    <div className="page">
      <PageHeader
        title="Readers"
        subtitle="Your door readers: assign each to a store and say which antenna faces the shop and which faces the street."
        actions={<Link className="btn btn--ghost" to="/door"><Icon name="door" size={18} /> Back to Door</Link>}
      />
      {error ? <ErrorNote error={error} /> : null}

      <Panel title="Readers">
        {!readers ? (
          <Loading text="Loading readers" />
        ) : readers.length === 0 ? (
          <Empty title="No readers yet.">
            {canSetUp ? "Add a direct reader below, or scan with a store connector." : "Ask your owner or manager to add the door readers."}
          </Empty>
        ) : (
          <ul className="reader-cards">
            {readers.map((r) => (
              <ReaderCard key={r.id} reader={r} edge={edges?.find((e) => e.id === r.edgeId)} now={now}
                canEdit={canSetUp} stores={locations.warehouses} storeLabel={storeLabel} />
            ))}
          </ul>
        )}
      </Panel>
      {canSetUp ? <AddDirectReader stores={locations.warehouses} storeLabel={storeLabel} /> : null}

      <Panel title="Store connectors">
        <p className="add-person__hint">
          Only for readers without a direct cloud connection: a small PC or box at the store that relays the reader to TagScout.
          Bluebird readers with BFRMS don't need one; add them above as direct readers.
        </p>
        {!edges ? (
          <Loading text="Looking for edge PCs" />
        ) : edges.length === 0 ? (
          <Empty title="No store connector.">
            Not needed for direct readers. For other readers, install TagScoutEdge on a PC in the store's network; it appears here within a minute.
          </Empty>
        ) : (
          <ul className="edge-list">
            {edges.map((e) => <EdgeRow key={e.id} edge={e} now={now} canScan={canSetUp} />)}
          </ul>
        )}
      </Panel>

    </div>
  );
}

function EdgeRow({ edge, now, canScan }: { edge: Edge; now: number; canScan: boolean }) {
  const online = isOnline(edge, now);
  const [ip, setIp] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const scanning = edge.scanStatus === "requested" || edge.scanStatus === "scanning";

  const scan = async (hosts: string[]) => {
    setBusy(true);
    setMessage("");
    try {
      await requestScan(edge.id, hosts);
      setIp("");
    } catch (err) {
      setMessage(teamErrorMessage(err));
    } finally {
      setBusy(false);
    }
  };
  const checkIp = (e: FormEvent) => {
    e.preventDefault();
    if (ip.trim()) void scan(ip.split(/[\s,]+/).filter(Boolean));
  };

  return (
    <li>
      <div className="edge-list__main">
        <p className="cell-title">
          <span className={`dot ${online ? "dot--on" : "dot--off"}`} aria-hidden="true" />
          {edge.name} <Badge tone={online ? "teal" : "slate"}>{online ? "Online" : "Offline"}</Badge>
        </p>
        <p className="cell-sub">
          {edge.ips.join(", ") || "IP unknown"} · last seen {ago(edge.lastSeenAt, now)}{edge.version ? ` · v${edge.version}` : ""}
        </p>
        <p className="cell-sub">
          {scanning ? "Scanning the network…" :
            edge.scanStatus === "done" ? `Last scan ${ago(edge.scanCompletedAt, now)}: ${edge.scanFound ?? 0} reader(s) found` :
              edge.scanStatus === "error" ? `Last scan failed: ${edge.scanError}` : "Not scanned yet"}
        </p>
        {message ? <p className="form-message form-message--error">{message}</p> : null}
      </div>
      {canScan ? (
        <div className="edge-list__actions">
          <Button variant="primary" onClick={() => void scan([])} busy={busy || scanning} disabled={!online}>
            Scan network
          </Button>
          <form className="ip-check" onSubmit={checkIp}>
            <input value={ip} onChange={(e) => setIp(e.target.value)} placeholder="Reader IP, e.g. 192.168.1.111" disabled={!online} />
            <Button type="submit" disabled={!online || busy || scanning || !ip.trim()}>Check</Button>
          </form>
        </div>
      ) : null}
    </li>
  );
}

const CONNECTION: Record<string, { label: string; tone: "teal" | "amber" | "red" | "slate" }> = {
  connected: { label: "Reading", tone: "teal" },
  connecting: { label: "Connecting", tone: "amber" },
  error: { label: "Can't connect", tone: "red" },
  stopped: { label: "Off", tone: "slate" },
  "": { label: "Off", tone: "slate" },
};

function ReaderCard({ reader: r, edge, now, canEdit, stores, storeLabel }: {
  reader: ReaderDoc; edge?: Edge; now: number; canEdit: boolean; stores: { id: string; name: string }[]; storeLabel: string;
}) {
  const [editing, setEditing] = useState(!r.configured && canEdit);
  const inUse = Object.entries(r.antennaRoles).filter(([, role]) => role !== "off").map(([p]) => Number(p));
  const lost = inUse.filter((p) => r.antennas.some((a) => a.port === p && a.connected === false));
  const direct = r.connectionType === "bfrms";
  const heardRecently = !!r.lastContactAt && now - r.lastContactAt.getTime() < 120_000;
  const conn = !r.enabled ? CONNECTION.stopped
    : direct ? (heardRecently ? CONNECTION.connected : { label: "Waiting for reader", tone: "amber" as const })
      : CONNECTION[r.connection];
  const [login, setLogin] = useState<DirectLogin | null>(null);
  const [keyBusy, setKeyBusy] = useState(false);
  const rotate = async () => {
    if (!window.confirm("Make a new password? The reader stops sending until you paste the new one into its BFRMS page.")) return;
    setKeyBusy(true);
    try {
      setLogin(await newReaderKey(r.id));
    } finally {
      setKeyBusy(false);
    }
  };

  return (
    <li className={`reader-card${r.configured ? "" : " is-new"}`}>
      <div className="reader-card__head">
        <div>
          <p className="reader-card__title">
            {r.name || "New reader"} {!r.configured ? <Badge tone="blue">New</Badge> : null}
            {r.configured ? <Badge tone={conn.tone}>{conn.label}</Badge> : null}
          </p>
          {direct ? (
            <p className="cell-sub">Direct to cloud (Bluebird BFRMS) · last heard {ago(r.lastContactAt, now)}</p>
          ) : (
            <>
              <p className="cell-sub">
                {[r.manufacturer, r.modelId && `model ${r.modelId}`, r.firmware && `firmware ${r.firmware}`].filter(Boolean).join(" · ") || "Reader"}
              </p>
              <p className="cell-sub mono">{r.host}{r.mac ? ` · ${r.mac}` : ""}{edge ? ` · via ${edge.name}` : ""}</p>
            </>
          )}
          {r.configured ? <p className="cell-sub">{storeLabel}: <strong>{r.storeName || "not assigned"}</strong></p> : null}
        </div>
        {canEdit && !editing ? (
          <div className="row-actions">
            {direct ? <Button onClick={() => void rotate()} busy={keyBusy}>New password</Button> : null}
            <Button onClick={() => setEditing(true)}>Set up</Button>
          </div>
        ) : null}
      </div>

      <div className="antenna-row" aria-label="Antenna ports">
        {r.antennas.length === 0 ? <span className="cell-sub">No antenna information yet.</span> : null}
        {r.antennas.map((a) => {
          const role = r.antennaRoles[String(a.port)];
          return (
            <span key={a.port} className={`antenna-chip${a.connected === true || (a.connected === null && role && role !== "off") ? " is-connected" : ""}`}>
              Port {a.port}{a.connected === null ? "" : a.connected ? " · connected" : " · not connected"}
              {role && role !== "off" ? <strong> · {role === "in" ? "In" : "Out"}</strong> : null}
            </span>
          );
        })}
      </div>
      {direct ? (
        <p className="cell-sub">
          {r.heartbeatEpc ? `Heartbeat tag last seen ${ago(r.lastHeartbeatAt, now)}` : "No heartbeat tag: add one in Set up so TagScout notices if this reader goes silent."}
        </p>
      ) : (
        <p className="cell-sub">Antennas checked {ago(r.lastProbedAt, now)}{r.probeError ? ` · last check failed: ${r.probeError}` : ""}</p>
      )}
      {r.enabled && r.monitoring === "stopped" ? (
        <p className="form-message form-message--error">
          Door monitoring stopped {ago(r.monitoringSince, now)}: TagScout hasn't heard from this reader. Check its power and network.
        </p>
      ) : null}
      {login ? <LoginCard login={login} onDone={() => setLogin(null)} /> : null}
      {lost.length ? (
        <p className="form-message form-message--error">
          Antenna port {lost.join(", ")} is set up but no longer connected. Check the cable.
        </p>
      ) : null}
      {r.enabled && r.connection === "error" && r.connectionError ? (
        <p className="form-message form-message--error">Can't read tags: {r.connectionError}</p>
      ) : null}

      {editing ? <ReaderForm reader={r} stores={stores} storeLabel={storeLabel} onDone={() => setEditing(false)} /> : null}
    </li>
  );
}

function ReaderForm({ reader: r, stores, storeLabel, onDone }: {
  reader: ReaderDoc; stores: { id: string; name: string }[]; storeLabel: string; onDone: () => void;
}) {
  const connected = r.antennas.filter((a) => a.connected !== false).map((a) => a.port);
  const [heartbeat, setHeartbeat] = useState(r.heartbeatEpc);
  const initialRoles = (): Record<string, AntennaRole> => {
    if (Object.keys(r.antennaRoles).length) return { ...r.antennaRoles };
    // Sensible start: first connected antenna In, second Out, the rest unused.
    const roles: Record<string, AntennaRole> = {};
    r.antennas.forEach((a) => {
      const i = connected.indexOf(a.port);
      roles[String(a.port)] = i === 0 ? "in" : i === 1 ? "out" : "off";
    });
    return roles;
  };
  const [name, setName] = useState(r.name || "Main entrance");
  const [storeId, setStoreId] = useState(r.storeId || stores[0]?.id || "");
  const [roles, setRoles] = useState<Record<string, AntennaRole>>(initialRoles);
  const [enabled, setEnabled] = useState(r.configured ? r.enabled : true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const inCount = Object.values(roles).filter((v) => v === "in").length;
  const outCount = Object.values(roles).filter((v) => v === "out").length;
  const mode = inCount && outCount ? "In and Out antennas set: passes get a direction (in / out)." :
    inCount || outCount ? "One side only: any pass of an unsold piece raises an alert (no direction)." :
      "No antenna in use yet.";

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      await configureReader({ readerId: r.id, name, storeId, antennaRoles: roles, enabled,
        ...(r.connectionType === "bfrms" ? { heartbeatEpc: heartbeat.trim() } : {}) });
      onDone();
    } catch (err) {
      setMessage(teamErrorMessage(err));
      setBusy(false);
    }
  };
  const remove = async () => {
    if (!window.confirm(`Remove ${r.name || r.host}? The edge PC stops reading from it. A later scan finds it again.`)) return;
    setBusy(true);
    try {
      await removeReader(r.id);
    } catch (err) {
      setMessage(teamErrorMessage(err));
      setBusy(false);
    }
  };

  return (
    <form className="reader-form" onSubmit={save}>
      <div className="reader-form__row">
        <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} required maxLength={60} /></label>
        <label className="field"><span>{storeLabel}</span>
          <select value={storeId} onChange={(e) => setStoreId(e.target.value)}>
            <option value="">— Not assigned —</option>
            {stores.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </label>
      </div>
      <fieldset className="antenna-roles">
        <legend>Antennas</legend>
        {r.antennas.map((a) => (
          <label key={a.port} className="antenna-roles__row">
            <span>Port {a.port} {a.connected === null ? null : <small className={a.connected ? "ok" : "off"}>{a.connected ? "connected" : "not connected"}</small>}</span>
            <select value={roles[String(a.port)] ?? "off"}
              onChange={(e) => setRoles({ ...roles, [String(a.port)]: e.target.value as AntennaRole })}>
              <option value="in">In antenna (shop side)</option>
              <option value="out">Out antenna (street side)</option>
              <option value="off">Not used</option>
            </select>
          </label>
        ))}
        <p className="add-person__hint">{mode} A piece read by the In antenna and then the Out antenna went out; the reverse came in.</p>
      </fieldset>
      {r.connectionType === "bfrms" ? (
        <label className="field">
          <span>Heartbeat tag EPC (a tag fixed permanently near one antenna)</span>
          <input className="mono" value={heartbeat} onChange={(e) => setHeartbeat(e.target.value)} placeholder="E280…" />
          <small className="add-person__hint">The reader reports it every few seconds. If it stops, TagScout raises a "Door monitoring stopped" alert. Its reads never count as passes.</small>
        </label>
      ) : null}
      <label className="check"><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> Reader on</label>
      {message ? <p className="form-message form-message--error">{message}</p> : null}
      <div className="row-actions">
        {r.configured || r.id ? <Button onClick={() => void remove()} disabled={busy}>Remove</Button> : null}
        {r.configured ? <Button onClick={onDone} disabled={busy}>Cancel</Button> : null}
        <Button type="submit" variant="primary" busy={busy}>Save</Button>
      </div>
    </form>
  );
}

// ── Direct readers (Bluebird BFRMS) ──────────────────────────────

function CopyField({ label, value, secret }: { label: string; value: string; secret?: boolean }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="copy-field">
      <span className="copy-field__label">{label}</span>
      <code className={secret ? "copy-field__value is-secret" : "copy-field__value"}>{value}</code>
      <Button onClick={() => void navigator.clipboard.writeText(value).then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 1500); })}>
        {copied ? "Copied" : "Copy"}
      </Button>
    </div>
  );
}

function LoginCard({ login, onDone }: { login: DirectLogin; onDone: () => void }) {
  return (
    <div className="login-card">
      <p className="login-card__title">Paste these into the reader's web page</p>
      <p className="add-person__hint">
        Open the reader in a browser (its IP address; login root / bluebird unless changed) → <strong>Communication → BFRMS → Configuration</strong> →
        Add Endpoint → <strong>HTTP POST</strong>. This password is shown only now; use New password if it's lost.
      </p>
      <CopyField label="URL" value={login.url} />
      <CopyField label="User ID" value={login.username} />
      <CopyField label="Password" value={login.password} secret />
      <p className="add-person__hint">
        If the reader's Authentication Type has no Basic option, choose None and use this URL instead:
      </p>
      <CopyField label="URL with password" value={`${login.url}&k=${encodeURIComponent(login.password)}`} secret />
      <ol className="login-card__steps">
        <li><strong>Mode:</strong> Simple. <strong>Start trigger:</strong> Immediately. <strong>Stop trigger:</strong> None.</li>
        <li><strong>Report filter:</strong> duration 2, type PER_ANTENNA. <strong>Tag metadata:</strong> Antenna and RSSI on.</li>
        <li><strong>Antennas:</strong> Auto, or tick the ports you set as In and Out here.</li>
        <li><strong>Interface configuration:</strong> Tag Data Interface 1 = this endpoint. Then Update.</li>
        <li><strong>Connection:</strong> tick Auto Connect, click Connect. This reader shows "Reading" here within a minute of the first tag.</li>
      </ol>
      <div className="row-actions"><Button variant="primary" onClick={onDone}>Done, I've pasted them</Button></div>
    </div>
  );
}

function AddDirectReader({ stores, storeLabel }: { stores: { id: string; name: string }[]; storeLabel: string }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("Main entrance");
  const [storeId, setStoreId] = useState(stores[0]?.id ?? "");
  const [ports, setPorts] = useState<4 | 8>(4);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [login, setLogin] = useState<DirectLogin | null>(null);

  const add = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setMessage("");
    try {
      setLogin(await createDirectReader({ name, storeId, ports }));
    } catch (err) {
      setMessage(teamErrorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Add a direct reader" aside={!open && !login ? <Button variant="primary" onClick={() => setOpen(true)}>Add reader</Button> : null}>
      {login ? (
        <LoginCard login={login} onDone={() => { setLogin(null); setOpen(false); }} />
      ) : open ? (
        <form className="reader-form" onSubmit={add}>
          <p className="add-person__hint">For Bluebird FR900 / FR901 readers: they send tags straight to TagScout over the internet, with no PC at the store.</p>
          <div className="reader-form__row">
            <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} required maxLength={60} /></label>
            <label className="field"><span>{storeLabel}</span>
              <select value={storeId} onChange={(e) => setStoreId(e.target.value)} required>
                {stores.map((st) => <option key={st.id} value={st.id}>{st.name}</option>)}
              </select>
            </label>
          </div>
          <label className="field"><span>Antenna ports on the reader</span>
            <select value={ports} onChange={(e) => setPorts(Number(e.target.value) === 8 ? 8 : 4)}>
              <option value={4}>4</option>
              <option value={8}>8 (FR901)</option>
            </select>
          </label>
          {message ? <p className="form-message form-message--error">{message}</p> : null}
          <div className="row-actions">
            <Button onClick={() => setOpen(false)} disabled={busy}>Cancel</Button>
            <Button type="submit" variant="primary" busy={busy}>Create and show the login</Button>
          </div>
        </form>
      ) : (
        <p className="add-person__hint">Bluebird readers with BFRMS connect straight to TagScout: no PC or box needed at the store.</p>
      )}
    </Panel>
  );
}
