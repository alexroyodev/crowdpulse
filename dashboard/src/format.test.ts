import { expect, test } from 'vitest'
import { formatCount, formatPct, formatTime, LEVEL_LABEL } from './format.ts'

test('times are local, with seconds', () => {
  // The API sends UTC; the page shows the browser's own time (rule 6), whatever zone runs the test.
  const instant = '2026-10-25T18:30:05.123Z'
  const local = new Date(instant)
  const pad = (n: number) => String(n).padStart(2, '0')

  expect(formatTime(instant)).toBe(`${pad(local.getHours())}:${pad(local.getMinutes())}:${pad(local.getSeconds())}`)
})

test('counts and percentages in Spanish', () => {
  expect(formatCount(20000)).toBe('20.000')
  expect(formatPct(61.7)).toBe('61,7 %')
  expect(formatPct(85)).toBe('85,0 %')
})

test('every level has a word', () => {
  expect(LEVEL_LABEL).toEqual({ NORMAL: 'Normal', WARNING: 'Aviso', CRITICAL: 'Crítico' })
})
