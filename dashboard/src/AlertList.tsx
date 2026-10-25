import type { Alert, Zone } from './api.ts'
import { formatCount, formatPct, formatTime } from './format.ts'
import { LevelBadge } from './LevelBadge.tsx'

/** The latest level changes, newest first, as the API orders them (question 3). */
export function AlertList({ alerts, zones }: { alerts: Alert[]; zones: Zone[] }) {
  if (alerts.length === 0) {
    return <p className="empty">Sin cambios de nivel todavía</p>
  }
  const nameOf = (zoneId: string) => zones.find((zone) => zone.zoneId === zoneId)?.name ?? zoneId
  return (
    <ol className="alerts">
      {alerts.map((alert) => (
        <li key={alert.alertId} className="alert">
          <time dateTime={alert.occurredAt}>{formatTime(alert.occurredAt)}</time>
          <span className="alert-zone">{nameOf(alert.zoneId)}</span>
          <span className="alert-change">
            <LevelBadge level={alert.from} /> → <LevelBadge level={alert.to} />
          </span>
          <span className="alert-count">
            {formatCount(alert.occupancy)} personas ({formatPct(alert.occupancyPct)})
          </span>
        </li>
      ))}
    </ol>
  )
}
