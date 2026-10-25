import { AlertList } from './AlertList.tsx'
import { fetchData, type Data } from './api.ts'
import { StatusBar } from './StatusBar.tsx'
import { usePolling } from './usePolling.ts'
import { ZoneCard } from './ZoneCard.tsx'

interface Props {
  /** Replaced in the tests; must not change between renders (it restarts the polling). */
  load?: (signal: AbortSignal) => Promise<Data>
  intervalMs?: number
}

export function App({ load = fetchData, intervalMs = 2000 }: Props) {
  const snapshot = usePolling(load, intervalMs)
  const { data } = snapshot
  return (
    <>
      <header className="page-header">
        <h1>CrowdPulse</h1>
        <StatusBar snapshot={snapshot} />
      </header>
      {/* Without data yet there is nothing to show: never an empty stadium as if it were real. */}
      {data && (
        <main>
          <section aria-labelledby="zones-title">
            <h2 id="zones-title" className="section-title">Zonas</h2>
            <div className="zones">
              {data.zones.map((zone) => <ZoneCard key={zone.zoneId} zone={zone} />)}
            </div>
          </section>
          <section aria-labelledby="alerts-title">
            <h2 id="alerts-title" className="section-title">Últimos cambios de nivel</h2>
            <AlertList alerts={data.alerts} zones={data.zones} />
          </section>
        </main>
      )}
    </>
  )
}
