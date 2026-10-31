import { useMemo } from "react";
import { Link } from "react-router-dom";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { TONE_COLOURS, tooltipStyle, useChartTheme } from "../components/charts";
import { SignalRings } from "../components/Logo";
import { Badge, Button, Empty, ErrorNote, Loading, Panel } from "../components/ui";
import { useCompany } from "../lib/CompanyProvider";
import { movementInfo, STOCK_STATUSES, UNIT_STATUSES } from "../lib/config";
import { loadMovements, loadSkuAggregates, loadStatusCounts } from "../lib/data";
import { addDays, dayKey, fmtDateTime, fmtDay, fmtNum, greeting, startOfDay } from "../lib/format";
import { useAsync } from "../lib/useAsync";

const DAYS = 30;

export function OverviewPage() {
  const company = useCompany();
  const { companyId, label, profile, locations, products, productById, locationText } = company;
  const theme = useChartTheme();
  const tip = tooltipStyle(theme);

  const data = useAsync(async () => {
    const to = addDays(startOfDay(new Date()), 1);
    const from = addDays(to, -DAYS);
    const [counts, aggregates, movements] = await Promise.all([
      loadStatusCounts(companyId),
      loadSkuAggregates(companyId),
      loadMovements(companyId, from, to),
    ]);
    return { counts, aggregates, movements, from, loadedAt: new Date() };
  }, [companyId]);

  const view = useMemo(() => {
    const d = data.data;
    if (!d) return null;

    const onHand = STOCK_STATUSES.reduce((sum, s) => sum + (d.counts.byStatus[s] ?? 0), 0);

    const statusSlices = UNIT_STATUSES.map((s) => ({
      name: s.label,
      value: d.counts.byStatus[s.key] ?? 0,
      colour: TONE_COLOURS[s.tone],
    })).filter((s) => s.value > 0);

    // Inward vs outward per day
    const days = Array.from({ length: DAYS }, (_, i) => {
      const day = addDays(d.from, i);
      return { key: dayKey(day), day: fmtDay(day), Inward: 0, Outward: 0 };
    });
    const dayIndex = new Map(days.map((x, i) => [x.key, i]));
    for (const m of d.movements.rows) {
      if (!m.timestamp) continue;
      const i = dayIndex.get(dayKey(m.timestamp));
      if (i === undefined) continue;
      const qty = Math.abs(m.quantity) || 1;
      if (m.type === "inward") days[i].Inward += qty;
      else if (m.type === "outward") days[i].Outward += qty;
    }
    const flowTotal = days.reduce((s, x) => s + x.Inward + x.Outward, 0);

    // Stock by warehouse, or by bin when there's only one warehouse
    const byWarehouse = new Map<string, number>();
    const byBin = new Map<string, number>();
    for (const a of d.aggregates) {
      for (const [key, qty] of Object.entries(a.byLocation)) {
        const warehouseId = key.split("/")[0];
        byWarehouse.set(warehouseId, (byWarehouse.get(warehouseId) ?? 0) + qty);
        byBin.set(key, (byBin.get(key) ?? 0) + qty);
      }
    }
    const multiWarehouse = locations.warehouses.length > 1 || byWarehouse.size > 1;
    const locationBars = multiWarehouse
      ? [...byWarehouse.entries()].map(([id, qty]) => ({ name: company.warehouseById.get(id)?.name ?? "Unknown", qty }))
      : [...byBin.entries()].map(([key, qty]) => {
          const [w, ...rest] = key.split("/");
          return { name: rest.join("/") || locationText(w, ""), qty };
        });
    locationBars.sort((a, b) => b.qty - a.qty);

    const topProducts = d.aggregates
      .filter((a) => a.totalQuantity > 0)
      .sort((a, b) => b.totalQuantity - a.totalQuantity)
      .slice(0, 8)
      .map((a) => {
        const p = productById.get(a.productId);
        const name = p?.title || a.sku || "Unknown";
        return { name: name.length > 26 ? `${name.slice(0, 25)}…` : name, qty: a.totalQuantity, sku: a.sku };
      });

    return {
      onHand,
      sold: d.counts.byStatus.sold ?? 0,
      total: d.counts.total,
      statusSlices,
      days,
      flowTotal,
      multiWarehouse,
      locationBars: locationBars.slice(0, 8),
      topProducts,
      recent: d.movements.rows.slice(0, 6),
      loadedAt: d.loadedAt,
    };
  }, [data.data, locations.warehouses.length, company.warehouseById, productById, locationText]);

  const company_name = profile.companyName || profile.name;

  return (
    <div className="page">
      <section className="hero">
        <SignalRings className="hero__rings" />
        <div className="hero__top">
          <div>
            <p className="hero__hello">
              {greeting()}
              {company_name ? `, ${company_name}` : ""}
            </p>
            <p className="hero__updated">
              {view ? `Updated ${fmtDateTime(view.loadedAt)}` : "Reading your stock"}
            </p>
          </div>
          <Button icon="refresh" variant="quiet" onClick={data.reload} busy={data.loading && !!data.data}>
            Refresh
          </Button>
        </div>

        <div className="hero__figure">
          <span className="hero__number">{view ? fmtNum(view.onHand) : "—"}</span>
          <span className="hero__caption">{label("unit", true).toLowerCase()} on hand right now</span>
        </div>

        <dl className="hero__stats">
          <div>
            <dt>{label("product", true)} in catalog</dt>
            <dd>{fmtNum(products.length)}</dd>
          </div>
          <div>
            <dt>{label("warehouse", true)}</dt>
            <dd>{fmtNum(locations.warehouses.length)}</dd>
          </div>
          <div>
            <dt>{label("bin", true)}</dt>
            <dd>{fmtNum(locations.bins.length)}</dd>
          </div>
          <div>
            <dt>Sold</dt>
            <dd>{view ? fmtNum(view.sold) : "—"}</dd>
          </div>
          <div>
            <dt>Tags enrolled</dt>
            <dd>{view ? fmtNum(view.total) : "—"}</dd>
          </div>
        </dl>
      </section>

      {data.error && !data.data ? <ErrorNote error={data.error} onRetry={data.reload} /> : null}
      {!view && !data.error ? <Loading text="Reading stock and movements" /> : null}

      {view ? (
        <div className="dash-grid">
          <Panel title={`Inward and outward, last ${DAYS} days`} className="span-2">
            {view.flowTotal === 0 ? (
              <Empty title={`No inward or outward in the last ${DAYS} days.`}>
                Enrollments, GRNs, sales and dispatches from the app will show up here.
              </Empty>
            ) : (
              <div className="chart chart--tall">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={view.days} margin={{ top: 8, right: 8, bottom: 0, left: -12 }} barGap={2}>
                    <CartesianGrid vertical={false} stroke={theme.grid} />
                    <XAxis
                      dataKey="day"
                      tick={{ fill: theme.muted, fontSize: 12 }}
                      tickLine={false}
                      axisLine={{ stroke: theme.grid }}
                      interval="preserveStartEnd"
                      minTickGap={18}
                    />
                    <YAxis allowDecimals={false} tick={{ fill: theme.muted, fontSize: 12 }} tickLine={false} axisLine={false} />
                    <Tooltip {...tip} cursor={{ fill: theme.grid, opacity: 0.5 }} />
                    <Legend wrapperStyle={{ fontSize: 13, color: theme.text }} iconType="circle" />
                    <Bar dataKey="Inward" fill="#14A38B" radius={[6, 6, 0, 0]} maxBarSize={18} />
                    <Bar dataKey="Outward" fill="#F2A531" radius={[6, 6, 0, 0]} maxBarSize={18} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            )}
          </Panel>

          <Panel title={`${label("unit", true)} by status`}>
            {view.statusSlices.length === 0 ? (
              <Empty title="No tags enrolled yet." />
            ) : (
              <div className="donut">
                <div className="chart chart--donut">
                  <PieChart width={200} height={200}>
                    <Pie
                      data={view.statusSlices}
                      dataKey="value"
                      nameKey="name"
                      cx={100}
                      cy={100}
                      innerRadius={62}
                      outerRadius={94}
                      paddingAngle={2}
                      stroke="none"
                      isAnimationActive={false}
                    >
                      {view.statusSlices.map((s) => (
                        <Cell key={s.name} fill={s.colour} />
                      ))}
                    </Pie>
                    <Tooltip {...tip} formatter={(v) => fmtNum(Number(v))} />
                  </PieChart>
                  <div className="donut__centre">
                    <span>{fmtNum(view.total)}</span>
                    <small>tags</small>
                  </div>
                </div>
                <ul className="legend-list">
                  {view.statusSlices.map((s) => (
                    <li key={s.name}>
                      <span className="legend-list__dot" style={{ background: s.colour }} />
                      <span>{s.name}</span>
                      <strong>{fmtNum(s.value)}</strong>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </Panel>

          <Panel title={view.multiWarehouse ? `Stock by ${label("warehouse").toLowerCase()}` : `Busiest ${label("bin", true).toLowerCase()}`}>
            {view.locationBars.length === 0 ? (
              <Empty title="Nothing on hand yet." />
            ) : (
              <HorizontalBars data={view.locationBars} colour="#2F6FE4" />
            )}
          </Panel>

          <Panel
            title={`Top ${label("product", true).toLowerCase()} on hand`}
            aside={
              <Link className="panel__link" to="/stock">
                All stock
              </Link>
            }
          >
            {view.topProducts.length === 0 ? (
              <Empty title="Nothing on hand yet." />
            ) : (
              <HorizontalBars data={view.topProducts} colour="#7B5CE6" />
            )}
          </Panel>

          <Panel
            title="Latest movements"
            aside={
              <Link className="panel__link" to="/movements">
                All movements
              </Link>
            }
          >
            {view.recent.length === 0 ? (
              <Empty title={`No movements in the last ${DAYS} days.`} />
            ) : (
              <ul className="feed">
                {view.recent.map((m) => {
                  const info = movementInfo(m.type);
                  const p = productById.get(m.productId);
                  const where = m.type === "outward" ? locationText(m.fromWarehouseId, m.fromBinCode) : locationText(m.toWarehouseId, m.toBinCode);
                  return (
                    <li key={m.id} className="feed__item">
                      <Badge tone={info.tone}>{info.label}</Badge>
                      <div className="feed__main">
                        <p className="feed__title">{p?.title || m.sku || "Unknown item"}</p>
                        <p className="feed__meta">
                          <span className="mono">{m.epc}</span>
                          {where ? <span>{where}</span> : null}
                        </p>
                      </div>
                      <time className="feed__time">{fmtDateTime(m.timestamp)}</time>
                    </li>
                  );
                })}
              </ul>
            )}
          </Panel>
        </div>
      ) : null}
    </div>
  );
}

function HorizontalBars({ data, colour }: { data: { name: string; qty: number }[]; colour: string }) {
  const theme = useChartTheme();
  const tip = tooltipStyle(theme);
  const height = Math.max(160, data.length * 38 + 20);
  const labelWidth = window.innerWidth < 600 ? 108 : 150;
  return (
    <div className="chart" style={{ height }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} layout="vertical" margin={{ top: 0, right: 36, bottom: 0, left: 4 }}>
          <XAxis type="number" hide allowDecimals={false} />
          <YAxis
            type="category"
            dataKey="name"
            width={labelWidth}
            tick={{ fill: theme.text, fontSize: 13 }}
            tickLine={false}
            axisLine={false}
          />
          <Tooltip {...tip} cursor={{ fill: theme.grid, opacity: 0.5 }} formatter={(v) => [fmtNum(Number(v)), "On hand"]} />
          <Bar dataKey="qty" radius={[0, 8, 8, 0]} maxBarSize={22} label={{ position: "right", fill: theme.muted, fontSize: 12 }}>
            {data.map((d, i) => (
              <Cell key={d.name + i} fill={colour} fillOpacity={Math.max(0.35, 1 - i * 0.09)} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
