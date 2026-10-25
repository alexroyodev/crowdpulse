// The two read endpoints of the API, as the dashboard uses them (spec 008, contract).

export type AlertLevel = 'NORMAL' | 'WARNING' | 'CRITICAL'

export interface Zone {
  zoneId: string
  name: string
  capacity: number
  occupancy: number
  occupancyPct: number
  alertLevel: AlertLevel
  lastEventAt: string | null
}

export interface Alert {
  alertId: string
  zoneId: string
  from: AlertLevel
  to: AlertLevel
  occupancy: number
  capacity: number
  occupancyPct: number
  occurredAt: string
  detectedAt: string
}

/** One picture of the stadium: zones and the latest level changes, fetched together. */
export interface Data {
  zones: Zone[]
  alerts: Alert[]
}

/** How many level changes the page shows. */
export const ALERTS_SHOWN = 10

/**
 * Both endpoints at once. If either fails (not 2xx, network, broken JSON), the whole picture
 * fails: zones and alerts always come from the same moment.
 */
export async function fetchData(signal: AbortSignal): Promise<Data> {
  const [zones, alerts] = await Promise.all([
    getJson<Zone[]>('/api/v1/zones', signal),
    getJson<Alert[]>(`/api/v1/alerts?limit=${ALERTS_SHOWN}`, signal),
  ])
  return { zones, alerts }
}

async function getJson<T>(path: string, signal: AbortSignal): Promise<T> {
  // Relative path: the dev server's proxy sends it to the API (spec 008, D2).
  const response = await fetch(path, { signal })
  if (!response.ok) {
    throw new Error(`${path} answered ${response.status}`)
  }
  return (await response.json()) as T
}
