import { useMemo, useState } from "react";
import { Badge, Button, Empty, ErrorNote, Loading, PageHeader, Panel, SearchBox, Segmented } from "../components/ui";
import { useCompany } from "../lib/CompanyProvider";
import { MOVEMENT_TYPES, movementInfo, referenceLabel, statusInfo } from "../lib/config";
import type { Movement } from "../lib/data";
import { loadMovements, MOVEMENT_LIMIT } from "../lib/data";
import { addDays, dayKey, downloadCsv, fmtDate, fmtDateTime, fmtNum, fmtTime, startOfDay } from "../lib/format";
import { useAsync } from "../lib/useAsync";

type Range = "today" | "7" | "30" | "90" | "custom";
const SHOW_ROWS = 500;

/** "in_stock→sold" → "In stock → Sold" for status changes; otherwise the reference as stored. */
function referenceText(m: Movement): string {
  if (m.referenceType === "status_change" && m.referenceId.includes("→")) {
    return m.referenceId
      .split("→")
      .map((k) => statusInfo(k.trim()).label)
      .join(" → ");
  }
  return m.referenceId;
}

function parseDay(value: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
}

export function MovementsPage() {
  const { companyId, productById, locationText, label, warehouseById } = useCompany();
  const multiWarehouse = warehouseById.size > 1;

  const place = (warehouseId: string, binCode: string) =>
    binCode || warehouseId ? (
      <>
        <p className="cell-title small mono">{binCode}</p>
        {multiWarehouse ? <p className="cell-sub">{warehouseById.get(warehouseId)?.name ?? ""}</p> : null}
      </>
    ) : (
      <span className="dim">—</span>
    );
  const today = startOfDay(new Date());

  const [range, setRange] = useState<Range>("7");
  const [customFrom, setCustomFrom] = useState(dayKey(addDays(today, -30)));
  const [customTo, setCustomTo] = useState(dayKey(today));
  const [type, setType] = useState("all");
  const [search, setSearch] = useState("");

  const { from, to } = useMemo(() => {
    const end = addDays(today, 1);
    if (range === "today") return { from: today, to: end };
    if (range === "custom") {
      const f = parseDay(customFrom) ?? addDays(today, -30);
      const t = parseDay(customTo) ?? today;
      return f <= t ? { from: f, to: addDays(t, 1) } : { from: t, to: addDays(f, 1) };
    }
    return { from: addDays(end, -Number(range)), to: end };
    // today is recomputed every render; its day value is what matters
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [range, customFrom, customTo, dayKey(today)]);

  const result = useAsync(() => loadMovements(companyId, from, to), [companyId, from.getTime(), to.getTime()]);

  const all = result.data?.rows ?? [];
  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    for (const m of all) c[m.type] = (c[m.type] ?? 0) + 1;
    return c;
  }, [all]);

  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    return all.filter((m) => {
      if (type !== "all" && m.type !== type) return false;
      if (!q) return true;
      const title = productById.get(m.productId)?.title ?? "";
      return (
        m.epc.toLowerCase().includes(q) ||
        m.sku.toLowerCase().includes(q) ||
        title.toLowerCase().includes(q) ||
        m.referenceId.toLowerCase().includes(q) ||
        m.notes.toLowerCase().includes(q)
      );
    });
  }, [all, type, search, productById]);

  const typeOptions = [
    { value: "all", label: "All", count: all.length },
    ...Object.entries(MOVEMENT_TYPES)
      .filter(([key]) => counts[key])
      .map(([key, info]) => ({ value: key, label: info.label, count: counts[key] })),
    ...Object.keys(counts)
      .filter((key) => !MOVEMENT_TYPES[key])
      .map((key) => ({ value: key, label: movementInfo(key).label, count: counts[key] })),
  ];

  const exportCsv = () =>
    downloadCsv(
      `tagscout-movements-${dayKey(from)}-to-${dayKey(addDays(to, -1))}.csv`,
      ["Date and time", "Type", "Quantity", label("product"), label("sku"), "EPC", "From", "To", "Reference type", "Reference", "Notes"],
      rows.map((m) => [
        fmtDateTime(m.timestamp),
        movementInfo(m.type).label,
        m.quantity,
        productById.get(m.productId)?.title ?? "",
        m.sku,
        m.epc,
        locationText(m.fromWarehouseId, m.fromBinCode),
        locationText(m.toWarehouseId, m.toBinCode),
        referenceLabel(m.referenceType),
        referenceText(m),
        m.notes,
      ]),
    );

  return (
    <div className="page">
      <PageHeader
        title="Movements"
        subtitle={`Every inward, outward, relocation and status change, ${fmtDate(from)} to ${fmtDate(addDays(to, -1))}`}
        actions={
          <Button icon="refresh" onClick={result.reload} busy={result.loading && !!result.data}>
            Refresh
          </Button>
        }
      />

      <div className="filters">
        <Segmented
          label="Date range"
          value={range}
          onChange={setRange}
          options={[
            { value: "today", label: "Today" },
            { value: "7", label: "7 days" },
            { value: "30", label: "30 days" },
            { value: "90", label: "90 days" },
            { value: "custom", label: "Custom" },
          ]}
        />
        {range === "custom" ? (
          <div className="date-range">
            <label className="field field--inline">
              <span>From</span>
              <input type="date" value={customFrom} max={customTo} onChange={(e) => setCustomFrom(e.target.value)} />
            </label>
            <label className="field field--inline">
              <span>To</span>
              <input type="date" value={customTo} min={customFrom} max={dayKey(today)} onChange={(e) => setCustomTo(e.target.value)} />
            </label>
          </div>
        ) : null}
      </div>

      {result.error && !result.data ? <ErrorNote error={result.error} onRetry={result.reload} /> : null}
      {!result.data && !result.error ? <Loading text="Reading movements" /> : null}

      {result.data ? (
        <Panel
          title={`${fmtNum(rows.length)} movement${rows.length === 1 ? "" : "s"}`}
          aside={
            <div className="toolbar">
              <SearchBox value={search} onChange={setSearch} placeholder="Search EPC, product or reference" />
              <Button icon="download" onClick={exportCsv} disabled={rows.length === 0}>
                CSV
              </Button>
            </div>
          }
        >
          {all.length > 0 ? (
            <div className="panel__filters">
              <Segmented label="Movement type" value={type} onChange={setType} options={typeOptions} />
            </div>
          ) : null}

          {result.data.capped ? (
            <p className="note">
              This range has more than {fmtNum(MOVEMENT_LIMIT)} movements, so only the newest {fmtNum(MOVEMENT_LIMIT)} are loaded. Pick a shorter range to see the rest.
            </p>
          ) : null}

          {rows.length === 0 ? (
            <Empty title={all.length ? "Nothing matches these filters." : "No movements in this date range."}>
              {all.length ? "Clear the search or pick another type." : "Try a longer range."}
            </Empty>
          ) : (
            <>
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>When</th>
                      <th>Type</th>
                      <th className="num">Qty</th>
                      <th>{label("product")}</th>
                      <th>EPC</th>
                      <th>From {label("bin").toLowerCase()}</th>
                      <th>To {label("bin").toLowerCase()}</th>
                      <th>Reference</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.slice(0, SHOW_ROWS).map((m) => {
                      const info = movementInfo(m.type);
                      const p = productById.get(m.productId);
                      return (
                        <tr key={m.id}>
                          <td className="nowrap">
                            <p className="cell-title small">{fmtDate(m.timestamp)}</p>
                            <p className="cell-sub">{fmtTime(m.timestamp)}</p>
                          </td>
                          <td>
                            <Badge tone={info.tone}>{info.label}</Badge>
                          </td>
                          <td className={`num strong ${m.quantity > 0 ? "pos" : m.quantity < 0 ? "neg" : ""}`}>
                            {m.quantity > 0 ? `+${m.quantity}` : m.quantity}
                          </td>
                          <td className="min-wide">
                            <p className="cell-title">{p?.title || m.sku}</p>
                            {p ? <p className="cell-sub mono">{m.sku}</p> : null}
                          </td>
                          <td className="mono epc">{m.epc}</td>
                          <td className="nowrap">{place(m.fromWarehouseId, m.fromBinCode)}</td>
                          <td className="nowrap">{place(m.toWarehouseId, m.toBinCode)}</td>
                          <td className="nowrap">
                            <p className="cell-title small">{referenceLabel(m.referenceType)}</p>
                            {m.referenceId ? <p className="cell-sub nowrap">{referenceText(m)}</p> : null}
                            {m.notes ? <p className="cell-sub">{m.notes}</p> : null}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              {rows.length > SHOW_ROWS ? (
                <p className="note">
                  Showing the newest {fmtNum(SHOW_ROWS)} of {fmtNum(rows.length)}. Download the CSV for all of them.
                </p>
              ) : null}
            </>
          )}
        </Panel>
      ) : null}
    </div>
  );
}
