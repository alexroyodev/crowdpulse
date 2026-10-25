import { formatTime } from './format.ts'
import type { Snapshot } from './snapshot.ts'

/** Spec 008, question 4: whether what the page shows is from now. */
export function StatusBar({ snapshot }: { snapshot: Snapshot }) {
  const { lastSuccessAt, failing } = snapshot
  if (!lastSuccessAt) {
    return <p role="status" className="status">{failing ? 'Sin conexión con la API' : 'Cargando…'}</p>
  }
  if (failing) {
    return (
      <p role="status" className="status status-failing">
        Sin conexión con la API: datos de las {formatTime(lastSuccessAt)}
      </p>
    )
  }
  return <p role="status" className="status">Actualizado a las {formatTime(lastSuccessAt)}</p>
}
