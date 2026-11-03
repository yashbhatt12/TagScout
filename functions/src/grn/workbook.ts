/**
 * Reads a GRN Excel file the way the app's GrnExcelParser did: first sheet,
 * header in row 1 (case-insensitive, a trailing "*" ignored), required
 * columns SKU, EPC, Bin Code, Warehouse Name; optional Serial Number and
 * Notes; every other column kept per row as an "extra" for GrnService to
 * match. Empty rows are skipped; structural problems are one error.
 */
import ExcelJS from "exceljs";

export interface GrnRow {
  rowNumber: number; // the Excel row number (header = 1)
  sku: string;
  epc: string;
  serialNumber: string;
  binCode: string;
  warehouseName: string;
  notes: string;
  parseErrors: string[];
  extras: Record<string, string>; // header as written → cell text (blank cells omitted)
}

export type ParseResult = { rows: GrnRow[]; error?: undefined } | { error: string; rows?: undefined };

const CORE_HEADERS = new Set(["sku", "epc", "epc code", "serial number", "serial", "bin code", "bin", "warehouse name", "warehouse", "notes"]);

/** A cell as text, like the app's reader: formulas give their result, dates give YYYY-MM-DD. */
export function cellText(value: ExcelJS.CellValue): string {
  if (value === null || value === undefined) return "";
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? "" : value.toISOString().slice(0, 10);
  }
  if (typeof value === "object") {
    const v = value as unknown as Record<string, unknown>;
    if (Array.isArray(v.richText)) return (v.richText as { text: string }[]).map((t) => t.text).join("").trim();
    if ("result" in v) return cellText(v.result as ExcelJS.CellValue);
    if ("text" in v) return cellText(v.text as ExcelJS.CellValue);
    if ("error" in v) return "";
    return "";
  }
  return String(value).trim();
}

export async function parseGrnWorkbook(buffer: Buffer): Promise<ParseResult> {
  const workbook = new ExcelJS.Workbook();
  try {
    await workbook.xlsx.load(buffer as unknown as ArrayBuffer);
  } catch (e) {
    return {error: `Could not read the file: ${e instanceof Error ? e.message : "not an Excel (.xlsx) file"}`};
  }
  const sheet = workbook.worksheets[0];
  if (!sheet || sheet.actualRowCount === 0) return {error: "The file is empty."};

  // Header → column number (lowercase for core columns; as written for extras)
  const header = sheet.getRow(1);
  const headerIndex = new Map<string, number>();
  const extraColumns: [string, number][] = [];
  header.eachCell({includeEmpty: false}, (cell, col) => {
    const raw = cellText(cell.value);
    if (!raw) return;
    const lower = raw.toLowerCase().replace(/\*$/, "").trim();
    if (CORE_HEADERS.has(lower)) {
      if (!headerIndex.has(lower)) headerIndex.set(lower, col);
    } else {
      extraColumns.push([raw.replace(/\*$/, "").trim(), col]);
    }
  });

  const pick = (...names: string[]) => names.map((n) => headerIndex.get(n)).find((c) => c !== undefined);
  const skuCol = pick("sku");
  const epcCol = pick("epc", "epc code");
  const serialCol = pick("serial number", "serial");
  const binCol = pick("bin code", "bin");
  const warehouseCol = pick("warehouse name", "warehouse");
  const notesCol = pick("notes");

  const missing = [
    skuCol === undefined && "SKU", epcCol === undefined && "EPC",
    binCol === undefined && "Bin Code", warehouseCol === undefined && "Warehouse Name",
  ].filter(Boolean);
  if (missing.length) return {error: `Missing required column(s): ${missing.join(", ")}`};
  if (sheet.actualRowCount < 2) return {error: "No data rows found under the header."};

  const rows: GrnRow[] = [];
  sheet.eachRow({includeEmpty: false}, (row, rowNumber) => {
    if (rowNumber === 1) return;
    const at = (col: number | undefined) => (col === undefined ? "" : cellText(row.getCell(col).value));
    const sku = at(skuCol);
    const epc = at(epcCol);
    const serialNumber = at(serialCol);
    const binCode = at(binCol);
    const warehouseName = at(warehouseCol);
    const notes = at(notesCol);
    const extras: Record<string, string> = {};
    for (const [h, col] of extraColumns) {
      const v = at(col);
      if (v) extras[h] = v;
    }
    if (!sku && !epc && !serialNumber && !binCode && !warehouseName && Object.keys(extras).length === 0) return;
    const parseErrors: string[] = [];
    if (!sku) parseErrors.push("SKU is blank");
    if (!epc) parseErrors.push("EPC is blank");
    if (!binCode) parseErrors.push("Bin Code is blank");
    if (!warehouseName) parseErrors.push("Warehouse Name is blank");
    rows.push({rowNumber, sku, epc, serialNumber, binCode, warehouseName, notes, parseErrors, extras});
  });

  if (rows.length === 0) return {error: "No usable data rows found in the file."};
  return {rows};
}
