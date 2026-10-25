# Tareas 008 — Dashboard

- **Estado:** Terminado (2026-10-25)
- **Spec:** [`spec.md`](spec.md) · **Plan:** [`plan.md`](plan.md)

Una tarea cada vez. Está terminada cuando su verificación pasa, `npm test` y `npm run build`
están en verde en `dashboard/` y `MEMORY.md` está actualizado. Al terminar se marca `[x]`.

---

- [x] **T01 — Esqueleto** (plan §1, §2)
  - Plantilla `react-ts` de Vite en `dashboard/`, sin `oxlint`; `engines` (Node ≥ 22.12).
  - `vite.config.ts`: puerto 5173 fijo, *proxy* `/api` → `http://127.0.0.1:8080`, bloque `test`
    de Vitest (`jsdom`, `setupFiles` con `jest-dom`).
  - Dependencias de test; scripts `dev`, `build`, `test` (`vitest run`).
  - `.gitignore`; `<html lang="es">`, título «CrowdPulse».
  - **Verificación:** `npm ci`, `npm test`, `npm run build`; con la `api` en marcha,
    `curl localhost:5173/api/v1/zones` responde las zonas a través del *proxy*.
  - Hecho así (2026-10-25): plantilla de `npm create vite` (react-ts) generada en el día; se quitan
    `oxlint` y sus ficheros, el CSS y los recursos de ejemplo; se queda `@types/node`, que hace falta
    para comprobar los tipos de `vite.config.ts`. Favicon propio. `npm test` (1 test trivial) y
    `npm run build` en verde; `curl localhost:5173/api/v1/zones` devuelve las zonas de la API.

- [x] **T02 — Datos, frescura y actualización** (plan §3–§5; CA4, CA6)
  - `api.ts` (`fetchData`), `snapshot.ts` (`applyResult`), `usePolling.ts`.
  - `snapshot.test.ts`.
  - **Mutación:** vaciar `data` al fallar → falla; restaurar.
  - **Verificación:** `npm test`, `npm run build`.
  - Hecho así (2026-10-25): `AbortSignal.any` junta la cancelación al desmontar y el límite de 3 s.
    Mutación (`return { failing: true }` al fallar) → falla el test de fallo con datos
    previos («a failure keeps the last good data…»); restaurado. 5 tests y `build` en verde.

- [x] **T03 — Pantalla** (plan §6, §7; CA1, CA3, CA5, CA6)
  - `format.ts`, `StatusBar`, `ZoneCard`, `AlertList`, `App`, `styles.css`.
  - `format.test.ts` y `App.test.tsx` (zonas, nivel en texto, `503` con aviso, recuperación,
    lista de cambios).
  - **Mutación:** la del T02, ahora también contra `App.test.tsx`; restaurar.
  - **Verificación:** `npm test`, `npm run build`.
  - Hecho así (2026-10-25): `LevelBadge` aparte (lo usan la tarjeta y la lista). `App` recibe
    `load` e `intervalMs` para los tests (por defecto `fetchData` y 2 s). Sin `globals` en Vitest,
    Testing Library no desmonta entre tests: `afterEach(cleanup)` en `test-setup.ts`. En español,
    `Intl` no separa los miles con 4 cifras (1500), sí con 5 (20.000). Mutación del T02 → fallan
    `snapshot.test.ts` y el test del `503` de `App.test.tsx`; restaurado. 12 tests en verde.

- [x] **T04 — Navegador, documentación y cierre** (plan §8, §9; CA1–CA8)
  - Verificación con el MCP de Chrome DevTools (plan §8), capturas aquí.
  - Búsqueda de umbrales en `dashboard/src` (CA7).
  - `CLAUDE.md`, README, `MEMORY.md`.
  - **Verificación:** clon limpio → `npm ci && npm test && npm run build` en `dashboard/`.
  - Hecho así (2026-10-25), con la `api`, el `processor` y `npm run dev`, en Chrome (MCP):
    - CA1: las 5 zonas con los números de `curl /api/v1/zones`.
    - CA3: 172 `ENTRY` en `PALCO` (200 plazas) → «Aviso», 86,0 %, y «Normal → Aviso, 170 personas
      (85,0 %)» el primero de la lista ([captura](escritorio-palco-aviso.png)).
    - CA2: con el simulador a 50 ev/s, 6 valores distintos de Fondo Norte en 10 s y una antigüedad
      máxima de los datos en pantalla de 2,8 s (la hora se muestra al segundo).
    - CA4: `docker compose pause postgres` (la API da 503) → aviso «Sin conexión con la API: datos de
      las 15:07:00» y datos quietos ([captura](escritorio-bd-caida.png)); `unpause` → se quita solo.
      Con la `api` parada (el *proxy* da 502), lo mismo, y al rearrancarla se pone al día.
    - CA5: 375 × 812, `scrollWidth` = 375, una columna ([captura](movil-375.png)); consola tras
      recargar: solo `debug`/`info` de Vite y React. Las peticiones fallidas mientras la API está
      caída las anota el navegador como error («Failed to load resource: 502»): no se pueden
      evitar desde la página y es lo esperable.
    - **Cambio durante la verificación:** el `role="status"` estaba en la línea de la hora, y un
      lector de pantalla la leería cada 2 s; ahora solo el aviso es región viva (tests ajustados).
    - CA7: ningún 80/85/90/95 en `dashboard/src` fuera de los datos de ejemplo de los tests.
    - CA8: clon limpio → `npm ci` (0 vulnerabilidades), 12 tests y `build` en verde.

## Verificación de los criterios de aceptación

| CA | Cómo se verifica | Resultado |
|----|------------------|-----------|
| CA1 | Navegador: 5 zonas = `curl /api/v1/zones`; `App.test.tsx` | ✅
| CA2 | Navegador con el simulador: datos con ≤ 2,8 s de antigüedad, valores cambiando sin recargar | ✅
| CA3 | Navegador: `PALCO` a aviso → nivel y primera fila de la lista; `App.test.tsx` (lista) | ✅
| CA4 | `snapshot.test.ts`, `App.test.tsx` (vaciar datos al fallar → fallan); navegador con PostgreSQL en pausa y con la `api` parada | ✅
| CA5 | Navegador a 375 px (sin desplazamiento horizontal); consola limpia con todo en marcha | ✅
| CA6 | `npm test` (12) + mutación | ✅
| CA7 | Búsqueda en `dashboard/src`: sin umbrales | ✅
| CA8 | Clon limpio: `npm ci`, `npm test`, `npm run build` | ✅
