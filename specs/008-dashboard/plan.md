# Plan 008 — Dashboard

- **Estado:** Aprobado (2026-10-25, por delegación del autor: «decide tú», como en la 007)
- **Spec:** [`spec.md`](spec.md)
- **Fase:** 4
- **Fecha:** 2026-10-25

**Qué se añade:** el módulo `dashboard/` (fuera del reactor de Maven) con las dependencias de
la plantilla oficial `react-ts` de Vite, más las de los tests. Sin cambios en la API, en
`compose.yaml` ni en la base de datos. Lo cubre la aprobación de la spec.

| Paquete | Versión | Para qué |
|---|---|---|
| `react`, `react-dom` | 19.3 | La interfaz |
| `vite`, `@vitejs/plugin-react` | 8.3, 6.1 | Servidor de desarrollo (con el *proxy*, D2) y compilación |
| `typescript` | 6.0 | Tipos; la versión que fija la plantilla de Vite (la 7 es reciente) |
| `@types/react`, `@types/react-dom` | 19.3 | Tipos de React |
| `vitest`, `jsdom` | 5.0, 30.1 | Tests, con un DOM simulado |
| `@testing-library/react`, `@testing-library/dom`, `@testing-library/jest-dom` | 16.3, 10.4, 7.0 | Renderizar componentes y comprobar lo que ve el usuario |

Las versiones exactas quedan en `package-lock.json` (se instala con `npm ci`). De la plantilla
se quita `oxlint` y `@types/node` si no hacen falta: `tsc -b` ya comprueba los tipos y un
linter más es otra herramienta que explicar (principio 1). Node ≥ 22.12 (lo que pide Vite 8),
en `engines` de `package.json`.

---

## 1. Estructura

```
dashboard/
  index.html
  package.json, package-lock.json, tsconfig*.json, vite.config.ts
  src/
    main.tsx            monta <App/>
    App.tsx             cabecera con la frescura, zonas y cambios de nivel
    api.ts              tipos de la API y las dos llamadas
    snapshot.ts         estado de la página: datos, última actualización buena, error (puro)
    usePolling.ts       hook: pide, espera, vuelve a pedir
    format.ts           horas, números y nombres de nivel en español
    ZoneCard.tsx, AlertList.tsx, StatusBar.tsx
    styles.css
    *.test.ts(x)        junto a lo que prueban
```

Un componente por fichero, sin carpetas por tipo: son pocos.

## 2. Llegar a la API (D2)

```ts
server: {
  port: 5173,
  strictPort: true,           // if 5173 is taken, fail instead of moving to 5174
  proxy: {
    // Same origin for the browser: no CORS in the API (spec 008, D2).
    '/api': 'http://127.0.0.1:8080',
  },
},
```

- Sin `rewrite`: la API ya vive en `/api/v1/...`.
- `127.0.0.1` y no `localhost`, por lo mismo que en el resto del proyecto (en Windows
  `localhost` puede ir a `::1`).
- El código usa rutas relativas (`/api/v1/zones`), así que no sabe en qué puerto está la API.

## 3. Datos: `api.ts`

```ts
export type AlertLevel = 'NORMAL' | 'WARNING' | 'CRITICAL'
export interface Zone { zoneId: string; name: string; capacity: number; occupancy: number;
  occupancyPct: number; alertLevel: AlertLevel; lastEventAt: string | null }
export interface Alert { alertId: string; zoneId: string; from: AlertLevel; to: AlertLevel;
  occupancy: number; capacity: number; occupancyPct: number; occurredAt: string; detectedAt: string }
export interface Data { zones: Zone[]; alerts: Alert[] }

export async function fetchData(signal: AbortSignal): Promise<Data>
```

- `fetchData` pide `/api/v1/zones` y `/api/v1/alerts?limit=10` a la vez (`Promise.all`): las
  dos cosas son una sola foto, con una sola hora de actualización. Si cualquiera falla
  (respuesta no 2xx, red, JSON roto), falla la foto entera.
- Los tipos describen el contrato de la spec; no se valida el JSON en tiempo de ejecución (la
  API es nuestra y está probada). *Descartado:* una librería de esquemas (zod).

## 4. Frescura y fallos: `snapshot.ts` (reglas 3 y 4; CA4, CA6)

El corazón de la spec es una función pura, sin React, que se prueba sola:

```ts
export interface Snapshot { data?: Data; lastSuccessAt?: Date; failing: boolean }

export function applyResult(prev: Snapshot, result: { ok: true; data: Data; at: Date } | { ok: false }): Snapshot
```

- **Éxito:** datos nuevos, `lastSuccessAt = at`, `failing = false` (regla 4).
- **Fallo:** **se conservan** `data` y `lastSuccessAt`; `failing = true` (regla 3).
- Con `failing` y sin datos (la API nunca respondió) la página dice «Sin conexión con la API»,
  no muestra zonas vacías.

## 5. Actualización: `usePolling.ts` (D1; CA2)

- Pide, **espera a la respuesta**, y entonces programa la siguiente petición a los 2 s
  (`setTimeout`, no `setInterval`). Con la API colgada no se acumulan peticiones.
- Cada petición tiene un límite de 3 s (`AbortSignal.timeout`): una API que no contesta cuenta
  como fallo, no deja la página esperando para siempre. Es lo que pasa con PostgreSQL en pausa si
  algo se cuelga en vez de dar 503.
- Al desmontar (cerrar la página) se cancela el temporizador y la petición en curso.
- El intervalo es un parámetro (2 s en la app; mucho menos en los tests).
- Retraso máximo de un cambio en pantalla: 2 s de espera + lo que tarde la petición, por debajo
  de los 5 s del CA2.

## 6. Pantalla

- **Cabecera (`StatusBar`):** «Actualizado a las 18:30:05» y, si `failing`, un aviso
  destacado: «Sin conexión con la API: datos de las 18:30:05». `role="status"` para lectores de
  pantalla.
- **Zonas (`ZoneCard`):** nombre, `12.345 / 20.000 personas`, `61,7 %`, una barra con el ancho
  de `occupancyPct` (tope visual en 100 %), y una etiqueta de nivel con texto y color
  (regla 5). Sin marcas de umbral en la barra: el dashboard no conoce los umbrales (regla 2).
  En el orden en que las da la API (`zone_id`).
- **Cambios de nivel (`AlertList`):** los 10 últimos, el más reciente primero (como los da la
  API): hora de `occurredAt`, zona (por su nombre), «Normal → Aviso» y la ocupación de ese
  momento. Vacía: «Sin cambios de nivel todavía».
- **`format.ts`:** `Intl.DateTimeFormat('es-ES', { hour, minute, second })` sin zona horaria
  explícita (la del navegador, regla 6); `Intl.NumberFormat('es-ES')` para personas y
  porcentajes; `NORMAL → Normal`, `WARNING → Aviso`, `CRITICAL → Crítico`.
- **CSS propio** (`styles.css`): variables de color, rejilla `repeat(auto-fill, minmax(16rem, 1fr))`
  que en el móvil queda en una columna; sin anchos fijos, para que a 375 px no haya
  desplazamiento horizontal (CA5). Colores de nivel con contraste suficiente sobre el fondo.
- `<html lang="es">` y título «CrowdPulse».

## 7. Tests (CA6, CA7)

| Test | Qué comprueba |
|---|---|
| `snapshot.test.ts` | Éxito reemplaza; fallo conserva datos y hora y marca `failing`; fallo sin datos previos; éxito tras fallo quita `failing` |
| `format.test.ts` | Hora local con segundos, separador de miles, porcentaje con coma, nombres de nivel |
| `App.test.tsx` (con `fetch` simulado por `vi.stubGlobal`) | Pinta las zonas y su nivel en texto; con la API respondiendo `503` mantiene las zonas y enseña el aviso con la hora buena; al volver la API, desaparece el aviso y se ven los datos nuevos |
| `App.test.tsx` | La lista de cambios en el orden de la API, con el nombre de la zona |

- **Mutación (CA6):** en `applyResult`, vaciar `data` al fallar → fallan el test de
  `snapshot` y el de `App` del `503`.
- **CA7:** búsqueda en `dashboard/src` de `85`, `95`, `80`, `90` en la tarea final; no es un
  test, porque un test que lee el código fuente probaría el texto, no el comportamiento.
- Intervalo de 20 ms en los tests de `App` y esperas con `findBy…` de Testing Library, sin
  temporizadores falsos (con promesas de por medio complican más de lo que ayudan).

## 8. Verificación en el navegador (CA1–CA5)

Con el entorno, la `api`, el `processor` y `npm run dev`, con el MCP de Chrome DevTools:
- CA1: las 5 zonas con los mismos números que `curl /api/v1/zones`.
- CA2/CA3: simulador en marcha; la ocupación cambia sin recargar; una zona se lleva a aviso
  (simulador con más entradas en una zona o eventos a mano con `curl`) y aparece en la lista.
- CA4: `docker compose pause postgres` → aviso y datos quietos; `unpause` → se quita solo.
  Luego, la `api` parada → el mismo aviso.
- CA5: emulación a 375 px (captura) y consola sin errores ni avisos.
- Capturas en `specs/008-dashboard/`.

## 9. Documentación
- `CLAUDE.md` › Comandos: `cd dashboard && npm ci`, `npm run dev`, `npm test`, `npm run build`.
- README: sección «Dashboard» (qué enseña, cómo se levanta, qué pasa si la API cae) y el
  módulo en la lista de piezas.
- Sin ADR propio: las decisiones son pocas y están en la spec (D1–D3) y en este plan.
- `.gitignore` de `dashboard/` (`node_modules`, `dist`).

## Orden propuesto para `tasks.md`
1. Esqueleto: plantilla de Vite, dependencias, *proxy*, `npm test` con un test trivial que
   pase; `npm run dev` enseña algo detrás del *proxy* (secciones 1, 2).
2. `api.ts`, `snapshot.ts` y `usePolling.ts`, con sus tests y la mutación (secciones 3–5).
3. Pantalla: componentes, `format.ts`, estilos y `App.test.tsx` (secciones 6, 7).
4. Verificación en el navegador, documentación y cierre (secciones 8, 9).
