import type { Data } from './api.ts'

/** What the page knows: the last good data, when it arrived, and whether the API is failing now. */
export interface Snapshot {
  data?: Data
  lastSuccessAt?: Date
  failing: boolean
}

export type FetchResult = { ok: true; data: Data; at: Date } | { ok: false }

export const EMPTY: Snapshot = { failing: false }

/**
 * Spec 008, rules 3 and 4. A failure keeps the last good data and its time, and only says so:
 * the page never shows zeros or an empty stadium as if they were real. The next success replaces
 * everything and clears the warning.
 */
export function applyResult(prev: Snapshot, result: FetchResult): Snapshot {
  if (result.ok) {
    return { data: result.data, lastSuccessAt: result.at, failing: false }
  }
  return { ...prev, failing: true }
}
