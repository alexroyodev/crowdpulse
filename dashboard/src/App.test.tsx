import { render, screen, waitFor, within } from '@testing-library/react'
import { expect, test } from 'vitest'
import type { Alert, Data, Zone } from './api.ts'
import { App } from './App.tsx'

const norte: Zone = { zoneId: 'NORTE', name: 'Fondo Norte', capacity: 20000, occupancy: 17200,
  occupancyPct: 86.0, alertLevel: 'WARNING', lastEventAt: '2026-10-25T18:30:05.123Z' }
const sur: Zone = { zoneId: 'SUR', name: 'Fondo Sur', capacity: 20000, occupancy: 300,
  occupancyPct: 1.5, alertLevel: 'NORMAL', lastEventAt: '2026-10-25T18:30:04.000Z' }
const toWarning: Alert = { alertId: 'a2', zoneId: 'NORTE', from: 'NORMAL', to: 'WARNING', occupancy: 17000,
  capacity: 20000, occupancyPct: 85.0, occurredAt: '2026-10-25T18:29:00.000Z', detectedAt: '2026-10-25T18:29:00.100Z' }
const backToNormal: Alert = { alertId: 'a1', zoneId: 'SUR', from: 'WARNING', to: 'NORMAL', occupancy: 15900,
  capacity: 20000, occupancyPct: 79.5, occurredAt: '2026-10-25T18:10:00.000Z', detectedAt: '2026-10-25T18:10:00.100Z' }

/** A fake API: answers `data` while up, fails (like a 503) while down. */
function fakeApi(initial: Data) {
  const api = {
    data: initial,
    up: true,
    load: async (): Promise<Data> => {
      if (!api.up) throw new Error('/api/v1/zones answered 503')
      return api.data
    },
  }
  return api
}

test('shows each zone with its numbers and its level in words', async () => {
  const api = fakeApi({ zones: [norte, sur], alerts: [] })
  render(<App load={api.load} intervalMs={20} />)

  const card = await screen.findByRole('article', { name: 'Fondo Norte' })
  expect(within(card).getByText('86,0 %')).toBeInTheDocument()
  expect(within(card).getByText('17.200 / 20.000 personas')).toBeInTheDocument()
  expect(within(card).getByText('Aviso')).toBeInTheDocument()
  expect(within(screen.getByRole('article', { name: 'Fondo Sur' })).getByText('Normal')).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent(/^Actualizado a las \d\d:\d\d:\d\d$/)
})

test('when the API fails it keeps the last data, says so, and recovers on its own', async () => {
  // Rules 3 and 4 (CA4).
  const api = fakeApi({ zones: [norte], alerts: [] })
  render(<App load={api.load} intervalMs={20} />)
  await screen.findByText('86,0 %')

  api.up = false
  const warning = await screen.findByText(/^Sin conexión con la API: datos de las \d\d:\d\d:\d\d$/)
  expect(warning).toBeInTheDocument()
  expect(screen.getByText('86,0 %')).toBeInTheDocument()

  api.data = { zones: [{ ...norte, occupancy: 17400, occupancyPct: 87.0 }], alerts: [] }
  api.up = true
  await screen.findByText('87,0 %')
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/^Actualizado a las/))
})

test('without any answer yet it shows no stadium at all', async () => {
  const api = fakeApi({ zones: [norte], alerts: [] })
  api.up = false
  render(<App load={api.load} intervalMs={20} />)

  await screen.findByText('Sin conexión con la API')
  expect(screen.queryByRole('article')).not.toBeInTheDocument()
})

test('lists the level changes in the order of the API, by zone name', async () => {
  const api = fakeApi({ zones: [norte, sur], alerts: [toWarning, backToNormal] })
  render(<App load={api.load} intervalMs={20} />)

  const items = within(await screen.findByRole('list')).getAllByRole('listitem')
  expect(items).toHaveLength(2)
  expect(items[0]).toHaveTextContent('Fondo Norte')
  expect(items[0]).toHaveTextContent('Normal → Aviso')
  expect(items[0]).toHaveTextContent('17.000 personas (85,0 %)')
  expect(items[1]).toHaveTextContent('Fondo Sur')
  expect(items[1]).toHaveTextContent('Aviso → Normal')
})

test('says so when there are no level changes yet', async () => {
  render(<App load={fakeApi({ zones: [sur], alerts: [] }).load} intervalMs={20} />)

  expect(await screen.findByText('Sin cambios de nivel todavía')).toBeInTheDocument()
})
