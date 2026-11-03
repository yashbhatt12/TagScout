/**
 * Company settings (the parts GRN checks need) and per-field value checks.
 * A port of the app's CompanyConfigParser / CompanyConfig and FieldValidator:
 * same forgiving parse, same rules, same messages. Values in, stored form out:
 *
 *   text     → trimmed text (max 1000)
 *   number   → number ("6.42 g" with the field's unit, "1,25,000" accepted)
 *   date     → "YYYY-MM-DD" (several day-first formats and Excel serials accepted)
 *   dropdown → the option as defined (matched ignoring case)
 *   boolean  → true / false (yes/no/y/n/1/0)
 *
 * An empty value is absent, never "".
 */

export type FieldType = "text" | "number" | "date" | "dropdown" | "boolean";
export type FieldLevel = "product" | "unit";

export interface FieldDef {
  key: string;
  label: string;
  type: FieldType;
  level: FieldLevel;
  required: boolean;
  options: string[];
  unit: string;
  order: number;
  excelHeaders: string[];
  active: boolean;
}

interface TypeDef {
  key: string;
  label: string;
  enabled: boolean;
  groupIds: string[];
  fields: FieldDef[];
  requiredOverrides: Record<string, boolean>;
}

interface Group {
  id: string;
  capability: string | null;
  fields: FieldDef[];
}

const TYPE_LABELS: Record<string, string> = {
  it_asset: "IT asset", non_it_asset: "Non-IT asset", apparel: "Retail apparel",
  jewellery: "Retail jewellery", food: "Food", general: "General",
};
const TYPE_KEYS = Object.keys(TYPE_LABELS);
const CAPABILITIES = ["batchTracking", "retailSale", "assignment", "audit", "depreciation", "nesting"];
const KEY_PATTERN = /^[a-zA-Z][a-zA-Z0-9_]{0,39}$/;
const RESERVED_KEYS = new Set([
  "id", "sku", "skukey", "name", "title", "shortname", "producttype", "categoryid", "subcategoryid", "brandid", "description",
  "barcode", "unitofmeasure", "uom", "imageurl", "status", "isactive", "trackingmode", "createdat", "createdby", "updatedat",
  "updatedby", "customfields", "epc", "tid", "productid", "serialnumber", "price", "itemcode", "category", "warehouseid",
  "rackid", "binid", "bincode", "currentwarehouseid", "currentrackid", "currentbinid", "currentbincode", "inwardedat",
  "lastmovedat", "dispatchedat", "lastseenat", "lastseenby", "enrolledat", "enrolledby", "previousepcs", "replacedby",
  "batchid", "batchno", "mfgdate", "expirydate", "soldat", "soldby", "parentepc", "unitkind", "childcount", "quantity", "assignedto",
]);

/** Core unit fields a type's `required` map can make mandatory; batch ones only with batch tracking. */
export const CORE = {
  serialNumber: {key: "serialNumber", capability: null},
  price: {key: "price", capability: null},
  batchNo: {key: "batchNo", capability: "batchTracking"},
  mfgDate: {key: "mfgDate", capability: "batchTracking"},
  expiryDate: {key: "expiryDate", capability: "batchTracking"},
} as const;
export type CoreField = keyof typeof CORE;

// ── Settings parse (forgiving: anything malformed is skipped) ─────

const str = (raw: unknown): string | null => (typeof raw === "string" && raw.trim() ? raw.trim() : null);
const strList = (raw: unknown): string[] => (Array.isArray(raw) ? raw.map(str).filter((s): s is string => !!s) : []);
const asRecord = (raw: unknown): Record<string, unknown> | null =>
  (typeof raw === "object" && raw !== null && !Array.isArray(raw) ? raw as Record<string, unknown> : null);
const fromKey = <T extends string>(list: readonly T[], raw: unknown): T | null => {
  const t = typeof raw === "string" ? raw.trim().toLowerCase() : "";
  return list.find((k) => k.toLowerCase() === t) ?? null;
};

function parseField(raw: unknown): FieldDef | null {
  const m = asRecord(raw);
  if (!m) return null;
  const key = str(m.key);
  if (!key || !KEY_PATTERN.test(key) || RESERVED_KEYS.has(key.toLowerCase())) return null;
  const type = fromKey(["text", "number", "date", "dropdown", "boolean"] as const, m.type);
  const level = fromKey(["product", "unit"] as const, m.level);
  if (!type || !level) return null;
  const label = str(m.label) ?? key;
  const options = strList(m.options);
  if (type === "dropdown" && options.length === 0) return null; // can't be filled; don't block saves
  const headers = strList(m.excelHeaders);
  return {
    key, label, type, level,
    required: typeof m.required === "boolean" ? m.required : false,
    options,
    unit: str(m.unit) ?? "",
    order: typeof m.order === "number" ? Math.trunc(m.order) : 0,
    excelHeaders: headers.length ? headers : [label],
    active: typeof m.active === "boolean" ? m.active : true,
  };
}

function parseFieldList(raw: unknown): FieldDef[] {
  const seen = new Set<string>();
  const out: FieldDef[] = [];
  for (const f of Array.isArray(raw) ? raw.map(parseField) : []) {
    if (f && !seen.has(f.key.toLowerCase())) {
      seen.add(f.key.toLowerCase());
      out.push(f);
    }
  }
  return out;
}

export class CompanyConfig {
  private readonly types = new Map<string, TypeDef>();
  private readonly groups = new Map<string, Group>();

  constructor(data: Record<string, unknown> | undefined) {
    // No settings doc: only general and jewellery (the pilot) are on, nothing extra.
    for (const key of TYPE_KEYS) {
      this.types.set(key, {key, label: TYPE_LABELS[key], enabled: !data && (key === "general" || key === "jewellery"),
        groupIds: [], fields: [], requiredOverrides: {}});
    }
    if (!data) return;
    for (const key of TYPE_KEYS) this.types.get(key)!.enabled = key === "general" || key === "jewellery";

    for (const raw of Array.isArray(data.attributeGroups) ? data.attributeGroups : []) {
      const m = asRecord(raw);
      const id = m && str(m.id);
      if (!m || !id || !KEY_PATTERN.test(id) || this.groups.has(id)) continue;
      this.groups.set(id, {id, capability: fromKey(CAPABILITIES, m.capability), fields: parseFieldList(m.fields)});
    }

    const types = asRecord(data.productTypes);
    if (types) {
      for (const [k, v] of Object.entries(types)) {
        const key = fromKey(TYPE_KEYS, k);
        const m = asRecord(v);
        if (!key || !m) continue;
        const overrides: Record<string, boolean> = {};
        for (const [fk, flag] of Object.entries(asRecord(m.required) ?? {})) {
          if (fk.trim() && typeof flag === "boolean") overrides[fk.trim()] = flag;
        }
        this.types.set(key, {
          key,
          label: str(m.label) ?? TYPE_LABELS[key],
          enabled: typeof m.enabled === "boolean" ? m.enabled : true, // mentioned in the doc = on unless disabled
          groupIds: strList(m.groups),
          fields: parseFieldList(m.fields),
          requiredOverrides: overrides,
        });
      }
    }
  }

  /** A product's type: unknown or missing → general. */
  static typeOf(productType: unknown): string {
    return fromKey(TYPE_KEYS, productType) ?? "general";
  }

  typeDef(type: string): TypeDef {
    return this.types.get(type) ?? {key: type, label: TYPE_LABELS[type] ?? type, enabled: false, groupIds: [], fields: [], requiredOverrides: {}};
  }

  private groupsFor(type: string): Group[] {
    return this.typeDef(type).groupIds.map((id) => this.groups.get(id)).filter((g): g is Group => !!g);
  }

  hasCapability(type: string, capability: string): boolean {
    return this.groupsFor(type).some((g) => g.capability === capability);
  }

  /** Active fields of one level: the type's own (by order), then each group's; per-type required overrides applied. */
  fieldsFor(type: string, level: FieldLevel): FieldDef[] {
    const def = this.typeDef(type);
    const byOrder = (a: FieldDef, b: FieldDef) => a.order - b.order;
    const all = [...[...def.fields].sort(byOrder), ...this.groupsFor(type).flatMap((g) => [...g.fields].sort(byOrder))];
    const seen = new Set<string>();
    const out: FieldDef[] = [];
    for (const f of all) {
      if (!f.active || f.level !== level || seen.has(f.key.toLowerCase())) continue;
      seen.add(f.key.toLowerCase());
      const override = def.requiredOverrides[f.key];
      out.push(override === undefined ? f : {...f, required: override});
    }
    return out;
  }

  isCoreRequired(type: string, field: CoreField): boolean {
    const core = CORE[field];
    if (core.capability && !this.hasCapability(type, core.capability)) return false;
    const entry = Object.entries(this.typeDef(type).requiredOverrides).find(([k]) => k.toLowerCase() === core.key.toLowerCase());
    return entry ? entry[1] : false;
  }
}

// ── Value checks ──────────────────────────────────────────────────

export interface FieldCheck {
  value: string | number | boolean | null;
  error: string | null;
}

const MAX_TEXT_LENGTH = 1000;
const TRUE_WORDS = new Set(["true", "yes", "y", "1"]);
const FALSE_WORDS = new Set(["false", "no", "n", "0"]);
const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];

const isBlank = (raw: unknown) => raw === null || raw === undefined || (typeof raw === "string" && raw.trim() === "");

function isoDate(y: number, m: number, d: number): string | null {
  if (y < 1 || m < 1 || m > 12 || d < 1) return null;
  const date = new Date(Date.UTC(y, m - 1, d));
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return null;
  return date.toISOString().slice(0, 10);
}

/** Excel stores dates as days since 1899-12-30. */
function excelSerialToDate(serial: number): string | null {
  if (!(serial >= 1 && serial < 2958466)) return null;
  const d = new Date(Date.UTC(1899, 11, 30) + Math.trunc(serial) * 86400000);
  return d.toISOString().slice(0, 10);
}

/** The app's accepted date formats: yyyy-MM-dd, d-M-yyyy / d/M/yyyy / d.M.yyyy, d MMM yyyy, d-MMM-yyyy; or an Excel serial. */
export function parseDateText(text: string): string | null {
  if (/^\d+$/.test(text)) {
    const n = Number(text);
    if (n >= 20000 && n <= 80000) return excelSerialToDate(n);
  }
  let m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
  if (m) return isoDate(+m[1], +m[2], +m[3]);
  m = /^(\d{1,2})([-/.])(\d{1,2})\2(\d{4})$/.exec(text);
  if (m) return isoDate(+m[4], +m[3], +m[1]);
  m = /^(\d{1,2})([ -])([A-Za-z]{3})\2(\d{4})$/.exec(text);
  if (m) {
    const month = MONTHS.indexOf(m[3].toLowerCase());
    return month < 0 ? null : isoDate(+m[4], month + 1, +m[1]);
  }
  return null;
}

export function check(def: Pick<FieldDef, "label" | "type" | "required" | "options" | "unit">, raw: unknown): FieldCheck {
  if (isBlank(raw)) return def.required ? {value: null, error: `${def.label} is required`} : {value: null, error: null};
  const text = String(raw).trim();
  switch (def.type) {
  case "text":
    return text.length > MAX_TEXT_LENGTH ?
      {value: null, error: `${def.label} can be at most ${MAX_TEXT_LENGTH} characters`} : {value: text, error: null};
  case "number": {
    let s = text;
    if (def.unit && s.toLowerCase().endsWith(def.unit.toLowerCase())) s = s.slice(0, -def.unit.length).trim();
    s = s.replace(/,/g, "").replace(/ /g, "");
    const n = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(s) ? Number(s) : NaN;
    return Number.isFinite(n) ? {value: n, error: null} : {value: null, error: `${def.label} must be a number`};
  }
  case "date": {
    const d = parseDateText(text);
    return d ? {value: d, error: null} : {value: null, error: `${def.label} must be a date, e.g. 31-03-2027`};
  }
  case "dropdown": {
    const option = def.options.find((o) => o.toLowerCase() === text.toLowerCase());
    return option !== undefined ? {value: option, error: null} :
      {value: null, error: `${def.label} must be one of: ${def.options.join(", ")}`};
  }
  case "boolean": {
    const word = text.toLowerCase();
    if (TRUE_WORDS.has(word)) return {value: true, error: null};
    if (FALSE_WORDS.has(word)) return {value: false, error: null};
    return {value: null, error: `${def.label} must be Yes or No`};
  }
  }
}

/** Checks every active field of [type] at [level]; [input] is field key → raw value. */
export function validateFields(config: CompanyConfig, type: string, level: FieldLevel, input: Record<string, unknown>) {
  const values: Record<string, string | number | boolean> = {};
  const errors: Record<string, string> = {};
  for (const def of config.fieldsFor(type, level)) {
    const result = check(def, input[def.key]);
    if (result.error) errors[def.key] = result.error;
    else if (result.value !== null) values[def.key] = result.value;
  }
  return {values, errors};
}

/** Lenient header form: lowercase, spaces / _ / - / . removed. "Gross Wt" == "gross_wt". */
export const headerKey = (header: string) => header.trim().toLowerCase().replace(/[\s_.-]+/g, "");

export function matchesHeader(def: FieldDef, header: string): boolean {
  const key = headerKey(header);
  return key !== "" && [...def.excelHeaders, def.label, def.key].some((h) => headerKey(h) === key);
}
