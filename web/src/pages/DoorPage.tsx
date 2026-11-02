import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { Badge, Button, Empty, ErrorNote, Loading, PageHeader, Panel, Segmented } from "../components/ui";
import { Icon } from "../components/Icon";
import { useCompany } from "../lib/CompanyProvider";
import type { Tone } from "../lib/config";
import { markAlertsRead, type Alert, type Detail } from "../lib/data";
import { subscribePasses, type Direction, type DoorPass, type Verdict } from "../lib/door";
import { fmtDateTime, fmtMoney, fmtNum, startOfDay } from "../lib/format";

const DIRECTION: Record<string, { label: string; tone: Tone }> = {
  out: { label: "Out →", tone: "amber" },
  in: { label: "← In", tone: "teal" },
  passed: { label: "At door", tone: "slate" },
  unknown: { label: "Not left yet", tone: "blue" },
};
const VERDICT: Record<Verdict, { label: string; tone: Tone }> = {
  alarm: { label: "Alarm", tone: "red" },
  ok: { label: "OK", tone: "teal" },
  unknown_tag: { label: "Unknown tag", tone: "violet" },
  pending: { label: "Waiting", tone: "slate" },
};
const timeOnly = new Intl.DateTimeFormat("en-IN", { hour: "numeric", minute: "2-digit", second: "2-digit" });
const whenText = (d: Date | null) => {
  if (!d) return "";
  return d >= startOfDay(new Date()) ? timeOnly.format(d) : fmtDateTime(d);
};

/** A short two-tone alarm beep (Web Audio; needs a click first, which the switch provides). */
function beep(ctx: AudioContext) {
  [880, 660].forEach((freq, i) => {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.frequency.value = freq;
    gain.gain.setValueAtTime(0.18, ctx.currentTime + i * 0.22);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + i * 0.22 + 0.2);
    osc.connect(gain).connect(ctx.destination);
    osc.start(ctx.currentTime + i * 0.22);
    osc.stop(ctx.currentTime + i * 0.22 + 0.21);
  });
}

export function DoorPage() {
  const { companyId, uid, role, label, alerts } = useCompany();
  const canSetUp = role === "owner" || role === "manager";
  const [passes, setPasses] = useState<DoorPass[]>();
  const [passError, setPassError] = useState<Error>();
  const [show, setShow] = useState<"unread" | "all">("unread");
  const [busy, setBusy] = useState(false);
  const [sound, setSound] = useState(false);
  const audio = useRef<AudioContext | null>(null);
  const seen = useRef<Set<string> | null>(null);

  useEffect(() => subscribePasses(companyId, 200, setPasses, setPassError), [companyId]);

  // Beep for alerts that arrive while the page is open (not for ones already there).
  useEffect(() => {
    if (alerts.loading) return;
    const ids = alerts.list.map((a) => a.id);
    if (seen.current === null) {
      seen.current = new Set(ids);
      return;
    }
    const fresh = ids.filter((id) => !seen.current!.has(id));
    fresh.forEach((id) => seen.current!.add(id));
    if (fresh.length && sound && audio.current) beep(audio.current);
  }, [alerts.list, alerts.loading, sound]);

  const toggleSound = () => {
    if (!audio.current) audio.current = new AudioContext();
    void audio.current.resume();
    if (!sound) beep(audio.current);
    setSound(!sound);
  };

  const shownAlerts = show === "unread" ? alerts.list.filter((a) => !a.read) : alerts.list;
  const markRead = async (ids: string[]) => {
    setBusy(true);
    try {
      await markAlertsRead(companyId, ids, uid);
    } finally {
      setBusy(false);
    }
  };

  const today = useMemo(() => {
    const from = startOfDay(new Date()).getTime();
    const t = (passes ?? []).filter((p) => p.lastSeenAt >= from);
    return {
      out: t.filter((p) => p.direction === "out" || p.direction === "passed").length,
      in: t.filter((p) => p.direction === "in").length,
      alarms: alerts.list.filter((a) => (a.createdAt?.getTime() ?? 0) >= from).length,
      unknown: t.filter((p) => p.verdict === "unknown_tag").length,
    };
  }, [passes, alerts.list]);

  const unitLabel = label("unit").toLowerCase();

  return (
    <div className="page">
      <PageHeader
        title="Door"
        subtitle={`Alerts and every tagged ${unitLabel} passing your fixed readers, live.`}
        actions={
          <>
            <button type="button" className={`sound-toggle${sound ? " is-on" : ""}`} onClick={toggleSound} aria-pressed={sound}>
              <Icon name="alerts" size={18} />
              Alarm sound {sound ? "on" : "off"}
            </button>
            {canSetUp ? (
              <Link className="btn btn--ghost" to="/door/readers">
                <Icon name="settings" size={18} /> Configure readers
              </Link>
            ) : null}
          </>
        }
      />

      <Panel
        title="Alerts"
        aside={
          <div className="row-actions">
            <Segmented
              label="Show"
              value={show}
              onChange={setShow}
              options={[
                { value: "unread", label: "Unread", count: alerts.unread },
                { value: "all", label: "All" },
              ]}
            />
            {alerts.unread > 0 ? (
              <Button onClick={() => void markRead(alerts.list.filter((a) => !a.read).map((a) => a.id))} disabled={busy}>
                Mark all read
              </Button>
            ) : null}
          </div>
        }
      >
        {alerts.error ? <ErrorNote error={alerts.error} /> : null}
        {alerts.loading ? (
          <Loading text="Loading alerts" />
        ) : shownAlerts.length === 0 ? (
          <Empty title={show === "unread" ? "No unread alerts." : "No alerts yet."}>
            An alert appears here the moment an unsold {unitLabel} goes out through a door reader.
          </Empty>
        ) : (
          <ul className="alert-list">
            {shownAlerts.map((a) => (
              <AlertCard key={a.id} alert={a} onRead={() => void markRead([a.id])} busy={busy} unitLabel={label("unit")} />
            ))}
          </ul>
        )}
      </Panel>

      <div className="door-stats" aria-label="Today">
        <div><span>{fmtNum(today.out)}</span>Out today</div>
        <div><span>{fmtNum(today.in)}</span>In today</div>
        <div className={today.alarms ? "is-alarm" : ""}><span>{fmtNum(today.alarms)}</span>Alerts today</div>
        <div><span>{fmtNum(today.unknown)}</span>Unknown tags today</div>
      </div>

      <PassesPanel passes={passes} error={passError} unitLabel={label("unit")} canSetUp={canSetUp} />
    </div>
  );
}

function DetailList({ title, items }: { title: string; items: Detail[] }) {
  if (!items.length) return null;
  return (
    <div className="alert-details">
      <p className="alert-details__title">{title}</p>
      <dl>
        {items.map((d, i) => (
          <div key={`${d.label}-${i}`}>
            <dt>{d.label}</dt>
            <dd>{d.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function AlertCard({ alert: a, onRead, busy, unitLabel }: { alert: Alert; onRead: () => void; busy: boolean; unitLabel: string }) {
  const u = a.unit;
  const title = u?.productTitle || a.itemCode || "Unknown item";
  const main: Detail[] = [
    u?.category || a.category ? { label: "Category", value: u?.category || a.category } : null,
    u?.brand ? { label: "Brand", value: u.brand } : null,
    a.price !== null ? { label: "Tag price", value: fmtMoney(a.price) } : null,
    u?.status ? { label: "Status", value: u.status.replace(/_/g, " ") } : null,
    u?.stockedAt ? { label: "Stocked at", value: u.stockedAt } : null,
  ].filter((d): d is Detail => d !== null);

  return (
    <li className={`alert-card${a.read ? "" : " is-unread"}`}>
      <div className="alert-card__photo">
        {u?.imageUrl ? <img src={u.imageUrl} alt="" loading="lazy" /> : <Icon name="tag" size={28} />}
      </div>
      <div className="alert-card__body">
        <div className="alert-card__head">
          <div>
            <p className="alert-card__title">{title}</p>
            <p className="cell-sub mono">{a.itemCode ? `${a.itemCode} · ` : ""}{a.epc}</p>
          </div>
          {!a.read ? <Button onClick={onRead} disabled={busy}>Mark read</Button> : <Badge tone="slate">Read</Badge>}
        </div>
        <p className="alert-card__where">
          <Badge tone="red">{a.direction === "out" ? "Went out" : "Seen at door"}</Badge>
          <strong>{a.storeName || "Store not set"}</strong>
          <span>· {a.readerName}</span>
          <span>· {whenText(a.createdAt)}</span>
        </p>
        <DetailList title={unitLabel} items={main} />
        <DetailList title="Product details" items={u?.productDetails ?? []} />
        <DetailList title={`This ${unitLabel.toLowerCase()}`} items={u?.unitDetails ?? []} />
      </div>
    </li>
  );
}

function PassesPanel({ passes, error, unitLabel, canSetUp }: { passes?: DoorPass[]; error?: Error; unitLabel: string; canSetUp: boolean }) {
  const [open, setOpen] = useState<string | null>(null);
  const [store, setStore] = useState("all");
  const stores = [...new Set((passes ?? []).map((p) => p.storeName).filter(Boolean))];
  const shown = (passes ?? []).filter((p) => store === "all" || p.storeName === store);
  return (
    <Panel
      title="Live passes"
      aside={
        stores.length > 1 ? (
          <Segmented label="Store" value={store} onChange={setStore}
            options={[{ value: "all", label: "All stores" }, ...stores.map((s) => ({ value: s, label: s }))]} />
        ) : null
      }
    >
      {error ? <ErrorNote error={error} /> : null}
      {!passes ? (
        <Loading text="Listening to the door" />
      ) : shown.length === 0 ? (
        <Empty title="No passes yet.">
          {canSetUp ? (<>They appear here once a reader is set up and switched on. <Link to="/door/readers">Configure readers</Link>.</>)
            : "They appear here the moment a tag crosses a door reader."}
        </Empty>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>When</th>
                <th>{unitLabel}</th>
                <th>Way</th>
                <th>Result</th>
                <th>Store · Reader</th>
                <th aria-label="Details" />
              </tr>
            </thead>
            <tbody>
              {shown.map((p) => {
                const isOpen = open === p.id;
                return (
                  <Fragment key={p.id}>
                    <tr className={`is-clickable${p.verdict === "alarm" ? " is-alarm" : ""}`} onClick={() => setOpen(isOpen ? null : p.id)}>
                      <td className="nowrap small">{whenText(new Date(p.lastSeenAt))}</td>
                      <td className="min-wide">
                        <p className="cell-title">{p.unit ? p.unit.productTitle || p.unit.sku || "Untitled" : "Not enrolled"}</p>
                        <p className="cell-sub mono">{p.unit?.sku ? `${p.unit.sku} · ` : ""}{p.epc}</p>
                      </td>
                      <td><Badge tone={DIRECTION[p.direction as Direction].tone}>{DIRECTION[p.direction as Direction].label}</Badge></td>
                      <td><Badge tone={VERDICT[p.verdict].tone}>{VERDICT[p.verdict].label}</Badge></td>
                      <td className="small">{[p.storeName, p.readerName].filter(Boolean).join(" · ")}</td>
                      <td className="chev"><span className={isOpen ? "rot" : ""}><Icon name="chevron" size={16} /></span></td>
                    </tr>
                    {isOpen ? (
                      <tr className="detail-row">
                        <td colSpan={6}>
                          <dl className="pass-details">
                            <div><dt>Status now</dt><dd>{p.unit ? p.unit.status.replace(/_/g, " ") : "—"}</dd></div>
                            <div><dt>Category</dt><dd>{p.unit?.category || "—"}</dd></div>
                            <div><dt>Tag price</dt><dd>{p.unit?.price != null ? fmtMoney(p.unit.price) : "—"}</dd></div>
                            <div><dt>Stocked at</dt><dd>{p.unit?.stockedAt || "—"}</dd></div>
                            <div><dt>Antennas</dt><dd>{p.antennas.join(", ") || "—"}</dd></div>
                            <div><dt>First seen</dt><dd>{timeOnly.format(new Date(p.firstSeenAt))}</dd></div>
                            <div><dt>Reads</dt><dd>{fmtNum(p.eventCount)}</dd></div>
                          </dl>
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}
