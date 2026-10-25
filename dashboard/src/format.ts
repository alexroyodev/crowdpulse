// Spanish texts and numbers for the page (spec 008). Times in the browser's own time zone (rule 6).
import type { AlertLevel } from './api.ts'

const time = new Intl.DateTimeFormat('es-ES', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
const count = new Intl.NumberFormat('es-ES')
const percent = new Intl.NumberFormat('es-ES', { minimumFractionDigits: 1, maximumFractionDigits: 1 })

/** 18:30:05, in the browser's time zone; the API sends UTC. */
export function formatTime(instant: string | Date): string {
  return time.format(typeof instant === 'string' ? new Date(instant) : instant)
}

/** 20.000 (Spanish groups thousands from five digits: 1500 stays 1500). */
export function formatCount(n: number): string {
  return count.format(n)
}

/** 61,7 % */
export function formatPct(n: number): string {
  return `${percent.format(n)} %`
}

/** Rule 5: every level also has a word, never only a colour. */
export const LEVEL_LABEL: Record<AlertLevel, string> = {
  NORMAL: 'Normal',
  WARNING: 'Aviso',
  CRITICAL: 'Crítico',
}
