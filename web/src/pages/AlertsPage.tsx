import { useMemo, useState } from "react";
import { Badge, Empty, ErrorNote, Loading, PageHeader, Panel, Segmented } from "../components/ui";
import { useCompany } from "../lib/CompanyProvider";
import { fmtDateTime, fmtMoney, fmtNum } from "../lib/format";

export function AlertsPage() {
  const { alerts, label } = useCompany();
  const [show, setShow] = useState<"unread" | "all">("unread");

  const rows = useMemo(() => (show === "all" ? alerts.list : alerts.list.filter((a) => !a.read)), [alerts.list, show]);
  const title = label("managerAlerts", true);

  return (
    <div className="page">
      <PageHeader
        title={title}
        subtitle="Unsold items seen by the door reader. This page updates live."
        actions={
          <Segmented
            label="Which alerts"
            value={show}
            onChange={setShow}
            options={[
              { value: "unread", label: "Unread", count: alerts.unread },
              { value: "all", label: "All", count: alerts.list.length },
            ]}
          />
        }
      />
      {alerts.error ? <ErrorNote error={alerts.error} /> : null}
      {alerts.loading ? <Loading text={`Reading ${title.toLowerCase()}`} /> : null}

      {!alerts.loading && !alerts.error ? (
        <Panel title={`${fmtNum(rows.length)} ${show === "unread" ? "unread" : "latest"}`}>
          {rows.length === 0 ? (
            <Empty title={show === "unread" ? "No unread alerts." : "No alerts yet."}>
              {show === "unread" ? "Everything the door reader flagged has been reviewed in the app." : "Alerts appear here when the door reader sees an unsold item."}
            </Empty>
          ) : (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>When</th>
                    <th>Status</th>
                    <th>Item</th>
                    <th>EPC</th>
                    <th className="num">Tag price</th>
                    <th>Reader</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((a) => (
                    <tr key={a.id} className={a.read ? "" : "is-unread"}>
                      <td className="nowrap">{fmtDateTime(a.createdAt)}</td>
                      <td>{a.read ? <Badge tone="slate">Read</Badge> : <Badge tone="red">Unread</Badge>}</td>
                      <td>
                        <p className="cell-title">{a.itemCode || "Unknown item"}</p>
                        {a.category ? <p className="cell-sub">{a.category}</p> : null}
                      </td>
                      <td className="mono small">{a.epc}</td>
                      <td className="num">{a.price !== null ? fmtMoney(a.price) : ""}</td>
                      <td className="small">
                        {a.readerId}
                        {a.antennaPort !== null ? `, antenna ${a.antennaPort}` : ""}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
      ) : null}
    </div>
  );
}
