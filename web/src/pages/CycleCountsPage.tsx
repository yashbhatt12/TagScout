import { Fragment, useMemo, useState } from "react";
import { Icon } from "../components/Icon";
import { Badge, Button, Empty, ErrorNote, Loading, PageHeader, Panel, SearchBox } from "../components/ui";
import { useCompany } from "../lib/CompanyProvider";
import { loadCycleCountMovements, type Movement } from "../lib/data";
import { downloadCsv, fmtDateTime, fmtNum } from "../lib/format";
import { useAsync } from "../lib/useAsync";

type CountRun = {
  key: string;
  reference: string;
  when: Date | null;
  bins: string[];
  missing: Movement[];
  unexpected: Movement[];
};

/**
 * The app logs only discrepancies (one cyclecount_adjust movement per
 * missing or unexpected tag), grouped here by the count's reference.
 */
export function CycleCountsPage() {
  const { companyId, label, locationText, productBySku } = useCompany();
  const data = useAsync(() => loadCycleCountMovements(companyId), [companyId]);
  const [open, setOpen] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  const runs = useMemo(() => {
    const map = new Map<string, CountRun>();
    for (const m of data.data ?? []) {
      const key = m.referenceId || `untitled-${m.timestamp ? m.timestamp.toDateString() : "unknown"}`;
      let run = map.get(key);
      if (!run) {
        run = { key, reference: m.referenceId, when: m.timestamp, bins: [], missing: [], unexpected: [] };
        map.set(key, run);
      }
      if (m.timestamp && (!run.when || m.timestamp > run.when)) run.when = m.timestamp;
      const bin = locationText(m.toWarehouseId, m.toBinCode);
      if (bin && !run.bins.includes(bin)) run.bins.push(bin);
      (m.quantity < 0 ? run.missing : run.unexpected).push(m);
    }
    const q = search.trim().toLowerCase();
    return [...map.values()]
      .filter((r) => !q || r.reference.toLowerCase().includes(q) || r.bins.some((b) => b.toLowerCase().includes(q)))
      .sort((a, b) => (b.when?.getTime() ?? 0) - (a.when?.getTime() ?? 0));
  }, [data.data, locationText, search]);

  const title = label("cycleCount", true);

  const exportCsv = () =>
    downloadCsv(
      "tagscout-cycle-count-discrepancies.csv",
      ["Count reference", "Date and time", label("bin"), "Result", "EPC", label("sku"), label("product"), "Note"],
      runs.flatMap((r) =>
        [...r.missing, ...r.unexpected].map((m) => [
          r.reference,
          fmtDateTime(m.timestamp),
          locationText(m.toWarehouseId, m.toBinCode),
          m.quantity < 0 ? "Missing" : "Unexpected",
          m.epc,
          m.sku,
          productBySku.get(m.sku.trim().toLowerCase())?.title ?? "",
          m.notes,
        ]),
      ),
    );

  return (
    <div className="page">
      <PageHeader
        title={title}
        subtitle={`Tags that were missing from, or found unexpectedly in, a ${label("bin").toLowerCase()} during a count`}
        actions={
          <Button icon="refresh" onClick={data.reload} busy={data.loading && !!data.data}>
            Refresh
          </Button>
        }
      />
      {data.error && !data.data ? <ErrorNote error={data.error} onRetry={data.reload} /> : null}
      {!data.data && !data.error ? <Loading text={`Reading ${title.toLowerCase()}`} /> : null}

      {data.data ? (
        <Panel
          title={`${fmtNum(runs.length)} count${runs.length === 1 ? "" : "s"} with discrepancies`}
          aside={
            <div className="toolbar">
              <SearchBox value={search} onChange={setSearch} placeholder={`Search reference or ${label("bin").toLowerCase()}`} />
              <Button icon="download" onClick={exportCsv} disabled={runs.length === 0}>
                CSV
              </Button>
            </div>
          }
        >
          {runs.length === 0 ? (
            <Empty title={search ? "Nothing matches that search." : "No discrepancies recorded."}>
              {search ? "" : `Counts where every tag matched aren't logged, so a clean ${title.toLowerCase()} history looks like this.`}
            </Empty>
          ) : (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Reference</th>
                    <th>When</th>
                    <th>{label("bin")}</th>
                    <th className="num">Missing</th>
                    <th className="num">Unexpected</th>
                    <th aria-label="Details" />
                  </tr>
                </thead>
                <tbody>
                  {runs.map((r) => {
                    const isOpen = open === r.key;
                    return (
                      <Fragment key={r.key}>
                        <tr className="is-clickable" onClick={() => setOpen(isOpen ? null : r.key)}>
                          <td className="cell-title">{r.reference || "No reference"}</td>
                          <td className="nowrap">{fmtDateTime(r.when)}</td>
                          <td className="small">{r.bins.join(", ")}</td>
                          <td className={`num strong ${r.missing.length ? "neg" : "dim"}`}>{r.missing.length}</td>
                          <td className={`num strong ${r.unexpected.length ? "warn" : "dim"}`}>{r.unexpected.length}</td>
                          <td className="chev">
                            <span className={isOpen ? "rot" : ""}>
                              <Icon name="chevron" size={16} />
                            </span>
                          </td>
                        </tr>
                        {isOpen ? (
                          <tr className="detail-row">
                            <td colSpan={6}>
                              <table className="table table--inner">
                                <tbody>
                                  {[...r.missing, ...r.unexpected].map((m) => (
                                    <tr key={m.id}>
                                      <td>
                                        {m.quantity < 0 ? <Badge tone="red">Missing</Badge> : <Badge tone="amber">Unexpected</Badge>}
                                      </td>
                                      <td className="mono small">{m.epc}</td>
                                      <td>{productBySku.get(m.sku.trim().toLowerCase())?.title ?? m.sku}</td>
                                      <td className="small dim">{m.notes}</td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
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
      ) : null}
    </div>
  );
}
