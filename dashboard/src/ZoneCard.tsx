import type { Zone } from './api.ts'
import { formatCount, formatPct } from './format.ts'
import { LevelBadge } from './LevelBadge.tsx'

/** One zone, as GET /api/v1/zones gives it. No thresholds here: the bar only shows the percentage. */
export function ZoneCard({ zone }: { zone: Zone }) {
  return (
    <article className={`zone zone-${zone.alertLevel.toLowerCase()}`} aria-label={zone.name}>
      <header className="zone-header">
        <h2>{zone.name}</h2>
        <LevelBadge level={zone.alertLevel} />
      </header>
      <p className="zone-pct">{formatPct(zone.occupancyPct)}</p>
      {/* The percentage is already written above: the bar is only for the eye. */}
      <div className="bar" aria-hidden="true">
        <div className="bar-fill" style={{ width: `${Math.min(zone.occupancyPct, 100)}%` }} />
      </div>
      <p className="zone-count">
        {formatCount(zone.occupancy)} / {formatCount(zone.capacity)} personas
      </p>
    </article>
  )
}
