import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { Badge, Button, Empty, ErrorNote, Loading, PageHeader, Panel } from "../components/ui";
import { Icon } from "../components/Icon";
import { useCompany } from "../lib/CompanyProvider";
import {
  configureReader, isOnline, removeReader, requestScan, subscribeEdges, subscribeReaders,
  type AntennaRole, type Edge, type ReaderDoc,
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
        subtitle="Fixed readers found by your edge PCs. Assign each to a store and set its antennas."
        actions={<Link className="btn btn--ghost" to="/door"><Icon name="door" size={18} /> Back to Door</Link>}
      />
      {error ? <ErrorNote error={error} /> : null}

      <Panel title="Edge PCs">
        {!edges ? (
          <Loading text="Looking for edge PCs" />
        ) : edges.length === 0 ? (
          <Empty title="No edge PC has connected yet.">
            Install TagScoutEdge on a PC in the store's network and start it in cloud mode. It appears here within a minute.
          </Empty>
        ) : (
          <ul className="edge-list">
            {edges.map((e) => <EdgeRow key={e.id} edge={e} now={now} canScan={canSetUp} />)}
          </ul>
        )}
      </Panel>

      <Panel title="Readers found">
        {!readers ? (
          <Loading text="Loading readers" />
        ) : readers.length === 0 ? (
          <Empty title="No readers found yet.">
            {canSetUp ? "Use Scan network on an edge PC above. Readers on its network appear here with their antennas." : "Ask your owner or manager to scan for readers."}
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
  const lost = inUse.filter((p) => r.antennas.some((a) => a.port === p && !a.connected));
  const conn = r.enabled ? CONNECTION[r.connection] : CONNECTION.stopped;

  return (
    <li className={`reader-card${r.configured ? "" : " is-new"}`}>
      <div className="reader-card__head">
        <div>
          <p className="reader-card__title">
            {r.name || "New reader"} {!r.configured ? <Badge tone="blue">New</Badge> : null}
            {r.configured ? <Badge tone={conn.tone}>{conn.label}</Badge> : null}
          </p>
          <p className="cell-sub">
            {[r.manufacturer, r.modelId && `model ${r.modelId}`, r.firmware && `firmware ${r.firmware}`].filter(Boolean).join(" · ") || "Reader"}
          </p>
          <p className="cell-sub mono">{r.host}{r.mac ? ` · ${r.mac}` : ""}{edge ? ` · via ${edge.name}` : ""}</p>
          {r.configured ? <p className="cell-sub">{storeLabel}: <strong>{r.storeName || "not assigned"}</strong></p> : null}
        </div>
        {canEdit && !editing ? <Button onClick={() => setEditing(true)}>Set up</Button> : null}
      </div>

      <div className="antenna-row" aria-label="Antenna ports">
        {r.antennas.length === 0 ? <span className="cell-sub">No antenna information yet.</span> : null}
        {r.antennas.map((a) => {
          const role = r.antennaRoles[String(a.port)];
          return (
            <span key={a.port} className={`antenna-chip${a.connected ? " is-connected" : ""}`}>
              Port {a.port} · {a.connected ? "connected" : "not connected"}
              {role && role !== "off" ? <strong> · {role === "in" ? "In" : "Out"}</strong> : null}
            </span>
          );
        })}
      </div>
      <p className="cell-sub">Antennas checked {ago(r.lastProbedAt, now)}{r.probeError ? ` · last check failed: ${r.probeError}` : ""}</p>
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
  const connected = r.antennas.filter((a) => a.connected).map((a) => a.port);
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
      await configureReader({ readerId: r.id, name, storeId, antennaRoles: roles, enabled });
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
            <span>Port {a.port} <small className={a.connected ? "ok" : "off"}>{a.connected ? "connected" : "not connected"}</small></span>
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
      <label className="check"><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> Reader on (the edge PC reads from it)</label>
      {message ? <p className="form-message form-message--error">{message}</p> : null}
      <div className="row-actions">
        {r.configured || r.id ? <Button onClick={() => void remove()} disabled={busy}>Remove</Button> : null}
        {r.configured ? <Button onClick={onDone} disabled={busy}>Cancel</Button> : null}
        <Button type="submit" variant="primary" busy={busy}>Save</Button>
      </div>
    </form>
  );
}
