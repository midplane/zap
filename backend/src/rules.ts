export const DAY = 86_400_000;
export const MAX_BLOB = 32 * 1024 * 1024;

const CLOCK_SKEW = 5 * 60_000;

export function validID(value: unknown): value is string {
  return typeof value === "string" && /^[a-zA-Z0-9_-]{16,100}$/.test(value);
}

export function retention(value: unknown): number {
  if (!Number.isInteger(value) || Number(value) < 1 || Number(value) > 365) {
    throw new Error("History must be between 1 and 365 days.");
  }
  return Number(value);
}

export function active(createdAt: number, days: number, now = Date.now()): boolean {
  return Number.isSafeInteger(createdAt) && createdAt > now - days * DAY && createdAt <= now + CLOCK_SKEW;
}

export async function digest(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return hex(new Uint8Array(bytes));
}

export function secret(): string {
  return hex(crypto.getRandomValues(new Uint8Array(32)));
}

function hex(bytes: Uint8Array): string {
  return [...bytes].map(b => b.toString(16).padStart(2, "0")).join("");
}
