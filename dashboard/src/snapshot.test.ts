import { describe, expect, test } from 'vitest'
import type { Data } from './api.ts'
import { applyResult, EMPTY } from './snapshot.ts'

const data: Data = {
  zones: [
    { zoneId: 'NORTE', name: 'Fondo Norte', capacity: 20000, occupancy: 120, occupancyPct: 0.6,
      alertLevel: 'NORMAL', lastEventAt: '2026-10-25T18:30:05.123Z' },
  ],
  alerts: [],
}
const at = new Date('2026-10-25T18:30:06Z')

describe('applyResult', () => {
  test('a success replaces the data and its time', () => {
    expect(applyResult(EMPTY, { ok: true, data, at })).toEqual({ data, lastSuccessAt: at, failing: false })
  })

  test('a failure keeps the last good data and its time, and says it is failing', () => {
    // Rule 3: never zeros or an empty page as if they were real.
    const good = applyResult(EMPTY, { ok: true, data, at })

    expect(applyResult(good, { ok: false })).toEqual({ data, lastSuccessAt: at, failing: true })
  })

  test('a failure before any success has no data to show', () => {
    expect(applyResult(EMPTY, { ok: false })).toEqual({ failing: true })
  })

  test('a success after a failure clears the warning', () => {
    // Rule 4.
    const later = new Date('2026-10-25T18:31:00Z')
    const failing = applyResult(applyResult(EMPTY, { ok: true, data, at }), { ok: false })

    expect(applyResult(failing, { ok: true, data, at: later })).toEqual({ data, lastSuccessAt: later, failing: false })
  })
})
