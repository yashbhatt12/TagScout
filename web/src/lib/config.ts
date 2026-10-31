/**
 * Read-only view of companies/{cid}/settings/config.
 *
 * Mirrors the forgiving parse in the Android app (CompanyConfigParser):
 * a missing or malformed value falls back to its default, never an error.
 * Only the parts the dashboard needs are read: labels, features, template
 * and product type names.
 */

export type LabelText = { singular: string; plural: string };

const DEFAULT_LABELS: Record<string, LabelText> = {
  warehouse: { singular: "Warehouse", plural: "Warehouses" },
  rack: { singular: "Rack", plural: "Racks" },
  bin: { singular: "Bin", plural: "Bins" },
  product: { singular: "Product", plural: "Products" },
  sku: { singular: "SKU", plural: "SKUs" },
  unit: { singular: "Unit", plural: "Units" },
  cycleCount: { singular: "Cycle count", plural: "Cycle counts" },
  managerAlerts: { singular: "Alert", plural: "Alerts" },
};

/** Same defaults as Feature.defaultOn in the app: customer features on, admin tools off. */
const DEFAULT_FEATURES: Record<string, boolean> = {
  quickScan: true,
  inventoryScan: true,
  orderPicking: true,
  writeTag: true,
  killTag: true,
  locateTag: true,
  wms: true,
  retail: true,
  managerAlerts: true,
  shop: true,
  catalogExcel: false,
};

const DEFAULT_TYPE_LABELS: Record<string, string> = {
  it_asset: "IT asset",
  non_it_asset: "Non-IT asset",
  apparel: "Retail apparel",
  jewellery: "Retail jewellery",
  food: "Food",
  general: "General",
};

const TEMPLATE_LABELS: Record<string, string> = {
  standard: "Standard",
  warehouse: "Warehouse",
  jewellery: "Jewellery retail",
  apparel: "Apparel retail",
  assets: "Asset tracking",
  food: "Food",
};

export type CompanyConfig = {
  /** False when the company has no settings doc yet (app runs on defaults). */
  exists: boolean;
  template: string;
  templateLabel: string;
  features: Record<string, boolean>;
  labels: Record<string, LabelText>;
  productTypeLabels: Record<string, string>;
};

function text(raw: unknown): string | undefined {
  return typeof raw === "string" && raw.trim() !== "" ? raw.trim() : undefined;
}

function isRecord(raw: unknown): raw is Record<string, unknown> {
  return typeof raw === "object" && raw !== null && !Array.isArray(raw);
}

function parseLabels(raw: unknown): Record<string, LabelText> {
  const labels = { ...DEFAULT_LABELS };
  if (!isRecord(raw)) return labels;
  for (const [key, value] of Object.entries(raw)) {
    const single = text(value);
    if (single) {
      labels[key] = { singular: single, plural: `${single}s` };
    } else if (isRecord(value)) {
      const singular = text(value.singular);
      if (singular) labels[key] = { singular, plural: text(value.plural) ?? `${singular}s` };
    }
  }
  return labels;
}

function parseFeatures(raw: unknown): Record<string, boolean> {
  const features = { ...DEFAULT_FEATURES };
  if (!isRecord(raw)) return features;
  for (const [key, value] of Object.entries(raw)) {
    if (typeof value === "boolean") features[key] = value;
  }
  return features;
}

function parseTypeLabels(raw: unknown): Record<string, string> {
  const labels = { ...DEFAULT_TYPE_LABELS };
  if (!isRecord(raw)) return labels;
  for (const [key, value] of Object.entries(raw)) {
    if (isRecord(value)) {
      const label = text(value.label);
      if (label) labels[key] = label;
    }
  }
  return labels;
}

export function parseConfig(data: Record<string, unknown> | undefined): CompanyConfig {
  const template = text(data?.template) ?? "";
  return {
    exists: data !== undefined,
    template,
    templateLabel: TEMPLATE_LABELS[template] ?? (template ? humanize(template) : ""),
    features: parseFeatures(data?.features),
    labels: parseLabels(data?.labels),
    productTypeLabels: parseTypeLabels(data?.productTypes),
  };
}

// ── Unit statuses and movement types (keys match the app; never rename) ──

export type Tone = "teal" | "blue" | "violet" | "amber" | "orange" | "red" | "slate" | "pink";

export const UNIT_STATUSES: { key: string; label: string; tone: Tone }[] = [
  { key: "in_stock", label: "In stock", tone: "teal" },
  { key: "picked", label: "Picked", tone: "blue" },
  { key: "dispatched", label: "Dispatched", tone: "violet" },
  { key: "assigned", label: "Assigned", tone: "blue" },
  { key: "sold", label: "Sold", tone: "amber" },
  { key: "in_repair", label: "In repair", tone: "orange" },
  { key: "returned", label: "Returned", tone: "pink" },
  { key: "expired", label: "Expired", tone: "red" },
  { key: "lost", label: "Lost", tone: "red" },
  { key: "disposed", label: "Disposed", tone: "slate" },
  { key: "retagged", label: "Re-tagged", tone: "slate" },
];

/** Statuses that count as stock on hand (STOCK_STATUSES in InventoryRepository). */
export const STOCK_STATUSES = ["in_stock", "picked"];

export const MOVEMENT_TYPES: Record<string, { label: string; tone: Tone }> = {
  inward: { label: "Inward", tone: "teal" },
  outward: { label: "Outward", tone: "amber" },
  relocation: { label: "Relocation", tone: "blue" },
  status_change: { label: "Status change", tone: "violet" },
  cyclecount_adjust: { label: "Cycle count", tone: "pink" },
};

const REFERENCE_TYPES: Record<string, string> = {
  grn: "GRN",
  enrollment: "Enrollment",
  status_change: "Status change",
  cyclecount: "Cycle count",
  pick_list: "Pick list",
  dispatch: "Dispatch",
  manual: "Manual",
};

export function statusInfo(key: string): { label: string; tone: Tone } {
  return UNIT_STATUSES.find((s) => s.key === key) ?? { label: humanize(key || "unknown"), tone: "slate" };
}

export function movementInfo(type: string): { label: string; tone: Tone } {
  return MOVEMENT_TYPES[type] ?? { label: humanize(type || "unknown"), tone: "slate" };
}

export function referenceLabel(type: string): string {
  return REFERENCE_TYPES[type] ?? humanize(type);
}

export function humanize(key: string): string {
  const spaced = key.replace(/[_-]+/g, " ").replace(/([a-z])([A-Z])/g, "$1 $2").trim().toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}
