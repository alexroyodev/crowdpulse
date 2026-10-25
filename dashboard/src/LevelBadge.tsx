import type { AlertLevel } from './api.ts'
import { LEVEL_LABEL } from './format.ts'

/** The level as the processor decided it (rule 2): colour and word (rule 5). */
export function LevelBadge({ level }: { level: AlertLevel }) {
  return <span className={`level level-${level.toLowerCase()}`}>{LEVEL_LABEL[level]}</span>
}
