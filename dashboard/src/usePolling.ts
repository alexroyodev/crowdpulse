import { useEffect, useState } from 'react'
import type { Data } from './api.ts'
import { applyResult, EMPTY, type Snapshot } from './snapshot.ts'

/** A request that has not answered in this time counts as a failure (e.g. the API hangs). */
const REQUEST_TIMEOUT_MS = 3000

/**
 * Spec 008, D1: asks, waits for the answer, and only then schedules the next request, so a slow
 * API never piles up requests. Stops (timer and request in flight) when the page goes away.
 */
export function usePolling(load: (signal: AbortSignal) => Promise<Data>, intervalMs: number): Snapshot {
  const [snapshot, setSnapshot] = useState<Snapshot>(EMPTY)

  useEffect(() => {
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const controller = new AbortController()

    async function poll() {
      const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(REQUEST_TIMEOUT_MS)])
      try {
        const data = await load(signal)
        if (!stopped) setSnapshot((prev) => applyResult(prev, { ok: true, data, at: new Date() }))
      } catch {
        if (!stopped) setSnapshot((prev) => applyResult(prev, { ok: false }))
      }
      if (!stopped) timer = setTimeout(poll, intervalMs)
    }

    poll()
    return () => {
      stopped = true
      clearTimeout(timer)
      controller.abort()
    }
  }, [load, intervalMs])

  return snapshot
}
