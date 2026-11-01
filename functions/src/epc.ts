/**
 * EPC rule, same as RfidConstants.isValidEpc in the app:
 * hex, 16–124 characters, a whole number of 16-bit words (length divisible by 4).
 * Stored uppercase; the unit's document ID is the EPC.
 */

export const EPC_RULE_TEXT = "hex, 16–124 characters in groups of 4";

export function normalizeEpc(raw: unknown): string {
  return typeof raw === "string" ? raw.trim().toUpperCase() : "";
}

export function isValidEpc(epc: string): boolean {
  return epc.length >= 16 && epc.length <= 124 && epc.length % 4 === 0 && /^[0-9A-F]+$/.test(epc);
}
