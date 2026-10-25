import { formatTime } from './format.ts'
import type { Snapshot } from './snapshot.ts'

/**
 * Spec 008, question 4: whether what the page shows is from now. Only the warning is a live region
 * (role="status"): the time changes every 2 s and a screen reader would read it out each time.
 */
export function StatusBar({ snapshot }: { snapshot: Snapshot }) {
  const { lastSuccessAt, failing } = snapshot
  if (failing) {
    return (
      <p role="status" className="status status-failing">
        {lastSuccessAt
          ? `Sin conexión con la API: datos de las ${formatTime(lastSuccessAt)}`
          : 'Sin conexión con la API'}
      </p>
    )
  }
  return (
    <p className="status">{lastSuccessAt ? `Actualizado a las ${formatTime(lastSuccessAt)}` : 'Cargando…'}</p>
  )
}
