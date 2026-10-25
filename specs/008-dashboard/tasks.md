# Tareas 008 — Dashboard

- **Estado:** Aprobado (2026-10-25, por delegación del autor); en curso
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
    Mutación (`return { failing: true }` al fallar) → fallan los dos tests de fallo con datos
    previos; restaurado. 5 tests en verde; `build` en verde.

- [ ] **T03 — Pantalla** (plan §6, §7; CA1, CA3, CA5, CA6)
  - `format.ts`, `StatusBar`, `ZoneCard`, `AlertList`, `App`, `styles.css`.
  - `format.test.ts` y `App.test.tsx` (zonas, nivel en texto, `503` con aviso, recuperación,
    lista de cambios).
  - **Mutación:** la del T02, ahora también contra `App.test.tsx`; restaurar.
  - **Verificación:** `npm test`, `npm run build`.

- [ ] **T04 — Navegador, documentación y cierre** (plan §8, §9; CA1–CA8)
  - Verificación con el MCP de Chrome DevTools (plan §8), capturas aquí.
  - Búsqueda de umbrales en `dashboard/src` (CA7).
  - `CLAUDE.md`, README, `MEMORY.md`.
  - **Verificación:** clon limpio → `npm ci && npm test && npm run build` en `dashboard/`.

## Verificación de los criterios de aceptación

| CA | Cómo se verifica | Resultado |
|----|------------------|-----------|
| CA1 | Navegador: 5 zonas = `curl /api/v1/zones` | |
| CA2 | Navegador con el simulador en marcha | |
| CA3 | Navegador: zona a aviso → nivel y lista; `App.test.tsx` (lista) | |
| CA4 | `snapshot.test.ts`, `App.test.tsx`; navegador con PostgreSQL en pausa y con la `api` parada | |
| CA5 | Navegador a 375 px; consola | |
| CA6 | `npm test` + mutación | |
| CA7 | Búsqueda en `dashboard/src` | |
| CA8 | Clon limpio | |
