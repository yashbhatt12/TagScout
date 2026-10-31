import { Fragment, useMemo, useState } from "react";
import { Badge, Button, Empty, ErrorNote, Loading, PageHeader, Panel, SearchBox, Segmented } from "../components/ui";
import { Icon } from "../components/Icon";
import { useCompany } from "../lib/CompanyProvider";
import { loadSkuAggregates, type SkuAggregate } from "../lib/data";
import { downloadCsv, fmtDateTime, fmtNum } from "../lib/format";
import { useAsync } from "../lib/useAsync";

type Tab = "product" | "location";

export function StockPage() {
  const { companyId, label } = useCompany();
  const [tab, setTab] = useState<Tab>("product");
  const aggregates = useAsync(() => loadSkuAggregates(companyId), [companyId]);

  const lastComputed = useMemo(() => {
    const times = (aggregates.data ?? []).map((a) => a.lastComputedAt?.getTime() ?? 0);
    const max = Math.max(0, ...times);
    return max ? new Date(max) : null;
  }, [aggregates.data]);

  return (
    <div className="page">
      <PageHeader
        title="Stock"
        subtitle={lastComputed ? `Last stock change ${fmtDateTime(lastComputed)}` : "On-hand stock from every enrolled tag"}
        actions={
          <>
            <Segmented
              label="Group stock by"
              value={tab}
              onChange={setTab}
              options={[
                { value: "product", label: `By ${label("product").toLowerCase()}` },
                { value: "location", label: `By ${label("bin").toLowerCase()}` },
              ]}
            />
            <Button icon="refresh" onClick={aggregates.reload} busy={aggregates.loading && !!aggregates.data}>
              Refresh
            </Button>
          </>
        }
      />
      {aggregates.error && !aggregates.data ? <ErrorNote error={aggregates.error} onRetry={aggregates.reload} /> : null}
      {!aggregates.data && !aggregates.error ? <Loading text="Reading stock" /> : null}
      {aggregates.data ? (
        tab === "product" ? <ByProduct aggregates={aggregates.data} /> : <ByLocation aggregates={aggregates.data} />
      ) : null}
    </div>
  );
}

type Aggregates = SkuAggregate[];

function ByProduct({ aggregates }: { aggregates: Aggregates }) {
  const { products, productById, categoryById, config, label, locationText } = useCompany();
  const [search, setSearch] = useState("");
  const [show, setShow] = useState<"stocked" | "all">("stocked");
  const [open, setOpen] = useState<string | null>(null);

  const rows = useMemo(() => {
    const byProduct = new Map(aggregates.map((a) => [a.productId, a]));
    const list = products.map((p) => {
      const a = byProduct.get(p.id);
      return {
        id: p.id,
        title: p.title,
        sku: p.sku,
        type: config.productTypeLabels[p.productType] ?? p.productType,
        category: categoryById.get(p.categoryId)?.name ?? "",
        qty: a?.totalQuantity ?? 0,
        byLocation: a?.byLocation ?? {},
        discontinued: p.status === "discontinued" || !p.isActive,
      };
    });
    // Stock for products no longer in the catalog
    for (const a of aggregates) {
      if (!productById.has(a.productId) && a.totalQuantity > 0) {
        list.push({
          id: a.productId,
          title: "Not in catalog",
          sku: a.sku,
          type: "",
          category: "",
          qty: a.totalQuantity,
          byLocation: a.byLocation,
          discontinued: true,
        });
      }
    }
    const q = search.trim().toLowerCase();
    return list
      .filter((r) => (show === "all" ? true : r.qty > 0))
      .filter((r) => !q || r.title.toLowerCase().includes(q) || r.sku.toLowerCase().includes(q) || r.category.toLowerCase().includes(q))
      .sort((a, b) => b.qty - a.qty || a.title.localeCompare(b.title));
  }, [aggregates, products, productById, categoryById, config.productTypeLabels, search, show]);

  const total = rows.reduce((s, r) => s + r.qty, 0);

  const exportCsv = () =>
    downloadCsv(
      `tagscout-stock-by-product.csv`,
      [label("product"), label("sku"), "Type", "Category", "On hand", "Locations"],
      rows.map((r) => [
        r.title,
        r.sku,
        r.type,
        r.category,
        r.qty,
        Object.entries(r.byLocation)
          .map(([k, n]) => `${locationText(k.split("/")[0], k.split("/").slice(1).join("/"))}: ${n}`)
          .join("; "),
      ]),
    );

  return (
    <Panel
      title={`${fmtNum(rows.length)} ${label("product", rows.length !== 1).toLowerCase()}, ${fmtNum(total)} on hand`}
      aside={
        <div className="toolbar">
          <SearchBox value={search} onChange={setSearch} placeholder={`Search ${label("product").toLowerCase()} or ${label("sku")}`} />
          <Segmented
            label="Which products"
            value={show}
            onChange={setShow}
            options={[
              { value: "stocked", label: "In stock" },
              { value: "all", label: "All" },
            ]}
          />
          <Button icon="download" onClick={exportCsv} disabled={rows.length === 0}>
            CSV
          </Button>
        </div>
      }
    >
      {rows.length === 0 ? (
        <Empty title={search ? "Nothing matches that search." : "No stock on hand yet."}>
          {search ? "Try part of the name or SKU." : "Enroll tags in the TagScout app and they appear here."}
        </Empty>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>{label("product")}</th>
                <th>Type</th>
                <th>Category</th>
                <th className="num">On hand</th>
                <th className="num">{label("bin", true)}</th>
                <th aria-label="Details" />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const locs = Object.entries(r.byLocation).sort((a, b) => b[1] - a[1]);
                const isOpen = open === r.id;
                return (
                  <Fragment key={r.id}>
                    <tr className={locs.length ? "is-clickable" : ""} onClick={() => locs.length && setOpen(isOpen ? null : r.id)}>
                      <td>
                        <p className="cell-title">
                          {r.title}
                          {r.discontinued ? (
                            <Badge tone="slate">{r.title === "Not in catalog" ? "Removed" : "Discontinued"}</Badge>
                          ) : null}
                        </p>
                        <p className="cell-sub mono">{r.sku}</p>
                      </td>
                      <td>{r.type}</td>
                      <td>{r.category}</td>
                      <td className="num strong">{fmtNum(r.qty)}</td>
                      <td className="num">{locs.length || ""}</td>
                      <td className="chev">{locs.length ? <span className={isOpen ? "rot" : ""}><Icon name="chevron" size={16} /></span> : null}</td>
                    </tr>
                    {isOpen ? (
                      <tr className="detail-row">
                        <td colSpan={6}>
                          <ul className="chips">
                            {locs.map(([key, n]) => {
                              const [w, ...rest] = key.split("/");
                              return (
                                <li key={key}>
                                  {locationText(w, rest.join("/"))}
                                  <strong>{fmtNum(n)}</strong>
                                </li>
                              );
                            })}
                          </ul>
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

function ByLocation({ aggregates }: { aggregates: Aggregates }) {
  const { locations, rackById, productById, label, warehouseById } = useCompany();
  const [warehouse, setWarehouse] = useState<string>("all");
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState<string | null>(null);

  const { rows, perWarehouse } = useMemo(() => {
    // binKey → [{productId, sku, qty}]
    const contents = new Map<string, { productId: string; sku: string; qty: number }[]>();
    for (const a of aggregates) {
      for (const [key, qty] of Object.entries(a.byLocation)) {
        const list = contents.get(key) ?? [];
        list.push({ productId: a.productId, sku: a.sku, qty });
        contents.set(key, list);
      }
    }

    const perWarehouse = new Map<string, number>();
    const all = locations.bins.map((bin) => {
      const key = `${bin.warehouseId}/${bin.binCode}`;
      const items = contents.get(key) ?? [];
      contents.delete(key);
      const qty = items.reduce((s, x) => s + x.qty, 0);
      perWarehouse.set(bin.warehouseId, (perWarehouse.get(bin.warehouseId) ?? 0) + qty);
      return {
        key,
        warehouseId: bin.warehouseId,
        warehouse: warehouseById.get(bin.warehouseId)?.name ?? "",
        rack: rackById.get(bin.rackId)?.name ?? "",
        binCode: bin.binCode,
        binName: bin.name,
        qty,
        items,
      };
    });
    // Stock recorded against bins that no longer exist
    for (const [key, items] of contents) {
      const [w, ...rest] = key.split("/");
      const qty = items.reduce((s, x) => s + x.qty, 0);
      perWarehouse.set(w, (perWarehouse.get(w) ?? 0) + qty);
      all.push({
        key,
        warehouseId: w,
        warehouse: warehouseById.get(w)?.name ?? "Unknown",
        rack: "",
        binCode: rest.join("/"),
        binName: "Not found in set-up",
        qty,
        items,
      });
    }
    return { rows: all, perWarehouse };
  }, [aggregates, locations.bins, rackById, warehouseById]);

  const q = search.trim().toLowerCase();
  const visible = rows
    .filter((r) => warehouse === "all" || r.warehouseId === warehouse)
    .filter((r) => !q || r.binCode.toLowerCase().includes(q) || r.rack.toLowerCase().includes(q) || r.binName.toLowerCase().includes(q))
    .sort((a, b) => a.warehouse.localeCompare(b.warehouse) || a.rack.localeCompare(b.rack, undefined, { numeric: true }) || a.binCode.localeCompare(b.binCode, undefined, { numeric: true }));

  const total = visible.reduce((s, r) => s + r.qty, 0);

  const exportCsv = () =>
    downloadCsv(
      "tagscout-stock-by-location.csv",
      [label("warehouse"), label("rack"), label("bin"), "Name", "On hand", label("product", true)],
      visible.map((r) => [r.warehouse, r.rack, r.binCode, r.binName, r.qty, r.items.length]),
    );

  if (locations.bins.length === 0 && rows.length === 0) {
    return (
      <Panel>
        <Empty title={`No ${label("bin", true).toLowerCase()} set up yet.`}>
          Add {label("warehouse", true).toLowerCase()}, {label("rack", true).toLowerCase()} and {label("bin", true).toLowerCase()} in Company Set Up in the app.
        </Empty>
      </Panel>
    );
  }

  const warehouseOptions = [
    { value: "all", label: "All", count: [...perWarehouse.values()].reduce((s, n) => s + n, 0) },
    ...locations.warehouses.map((w) => ({ value: w.id, label: w.name, count: perWarehouse.get(w.id) ?? 0 })),
  ];

  return (
    <Panel
      title={`${fmtNum(visible.length)} ${label("bin", visible.length !== 1).toLowerCase()}, ${fmtNum(total)} on hand`}
      aside={
        <div className="toolbar">
          {locations.warehouses.length > 1 ? (
            <Segmented label={label("warehouse")} value={warehouse} onChange={setWarehouse} options={warehouseOptions} />
          ) : null}
          <SearchBox value={search} onChange={setSearch} placeholder={`Search ${label("rack").toLowerCase()} or ${label("bin").toLowerCase()}`} />
          <Button icon="download" onClick={exportCsv} disabled={visible.length === 0}>
            CSV
          </Button>
        </div>
      }
    >
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              {locations.warehouses.length > 1 ? <th>{label("warehouse")}</th> : null}
              <th>{label("rack")}</th>
              <th>{label("bin")}</th>
              <th className="num">On hand</th>
              <th className="num">{label("product", true)}</th>
              <th aria-label="Details" />
            </tr>
          </thead>
          <tbody>
            {visible.map((r) => {
              const isOpen = open === r.key;
              const cols = locations.warehouses.length > 1 ? 6 : 5;
              return (
                <Fragment key={r.key}>
                  <tr className={r.items.length ? "is-clickable" : ""} onClick={() => r.items.length && setOpen(isOpen ? null : r.key)}>
                    {locations.warehouses.length > 1 ? <td>{r.warehouse}</td> : null}
                    <td>{r.rack}</td>
                    <td>
                      <p className="cell-title mono">{r.binCode}</p>
                      {r.binName && r.binName !== r.binCode ? <p className="cell-sub">{r.binName}</p> : null}
                    </td>
                    <td className="num strong">{r.qty ? fmtNum(r.qty) : <span className="dim">0</span>}</td>
                    <td className="num">{r.items.length || ""}</td>
                    <td className="chev">{r.items.length ? <span className={isOpen ? "rot" : ""}><Icon name="chevron" size={16} /></span> : null}</td>
                  </tr>
                  {isOpen ? (
                    <tr className="detail-row">
                      <td colSpan={cols}>
                        <ul className="chips">
                          {[...r.items]
                            .sort((a, b) => b.qty - a.qty)
                            .map((x) => (
                              <li key={x.productId}>
                                {productById.get(x.productId)?.title ?? x.sku}
                                <strong>{fmtNum(x.qty)}</strong>
                              </li>
                            ))}
                        </ul>
                      </td>
                    </tr>
                  ) : null}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
    </Panel>
  );
}
