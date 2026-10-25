# Spec 008 — Dashboard

- **Estado:** Terminada (2026-10-25); D1–D3 con las opciones recomendadas
- **Fase:** 4
- **Fecha:** 2026-10-25
- **Depende de:** [spec 007](../007-trazas/spec.md)

## Contexto y objetivo
Grafana (spec 006) y Jaeger (spec 007) son herramientas para quien opera el **sistema**:
tasas, latencias, trazas. Quien opera el **estadio** quiere otra cosa: ver de un vistazo cómo
está cada zona y enterarse en cuanto una se acerca a su capacidad. Hoy eso solo se ve con
`curl` a `GET /api/v1/zones` y `GET /api/v1/alerts`. Es la última pieza de la fase 4
(métricas → trazas → dashboard).

Objetivo: una página web, en español y usable en el móvil, que muestre en vivo la ocupación y
el nivel de alerta de cada zona y los últimos cambios de nivel, y que diga claramente cuándo
lo que enseña no está al día.

Preguntas que la página tiene que contestar:
1. ¿Cuánta gente hay en cada zona y qué porcentaje de su capacidad es?
2. ¿Qué zonas están en aviso o en crítico?
3. ¿Qué ha cambiado últimamente (qué zona cambió de nivel, cuándo y con qué ocupación)?
4. ¿Estoy viendo datos de ahora o de hace un rato?

## Alcance

### Incluido
- Módulo nuevo `dashboard/`: React + TypeScript.
- Vista de zonas (nombre, ocupación, capacidad, porcentaje, nivel) y lista de los últimos
  cambios de nivel, con los datos de los endpoints que ya existen.
- Actualización automática, sin recargar la página (D1).
- Indicador de frescura: hora de la última actualización y aviso visible si la API no responde.
- Tests unitarios del código del dashboard y verificación en el navegador (consola sin errores,
  vista móvil), como pide `CLAUDE.md`.
- Comandos para levantarlo documentados en `CLAUDE.md` y en el README.

### Fuera de alcance
- Escribir nada: el dashboard solo lee. Ni reproceso del DLT ni cambios de capacidad.
- Autenticación, despliegue o servirlo en producción (no hay despliegue en el proyecto).
- Gráficas históricas: para eso está Grafana (spec 006).
- Notificaciones del navegador o sonidos al cambiar de nivel.
- Integración continua: es otra pieza de la fase siguiente (GitHub Actions).
- Tests de extremo a extremo con navegador automatizado (Playwright, Cypress).

## Reglas de negocio
1. **La API es la fuente de verdad** (principio 6). El dashboard enseña lo que devuelve
   `GET /api/v1/zones` y `GET /api/v1/alerts`; nunca calcula la ocupación sumando eventos ni
   guarda estado propio que la sustituya.
2. **El nivel de alerta lo decide el `processor`, no el dashboard.** El color y el texto salen
   del campo `alertLevel` de la API. Los umbrales (85/80, 95/90) no aparecen en el código del
   dashboard (`CLAUDE.md`: los umbrales los fija la spec 002, no el código).
3. **Nunca datos inventados.** Si la API no responde o responde un error (p. ej. `503` con la
   base de datos caída), se siguen mostrando los últimos datos buenos con un aviso claro y la
   hora de la última actualización buena. Nunca se muestran ceros ni una página vacía como si
   fueran datos reales.
4. **Se recupera solo.** Cuando la API vuelve, la página se pone al día sin recargarla y el
   aviso desaparece.
5. **El color nunca va solo.** Cada nivel se indica también con texto («Normal», «Aviso»,
   «Crítico»), para quien no distingue colores o lo ve en blanco y negro.
6. **Instantes en la hora del navegador.** La API da UTC; la página muestra la hora local de
   quien la mira (en el estadio, la de Madrid), con segundos.

## Contrato
El dashboard usa la API tal como está (spec 001 y 002); **no se cambia ningún endpoint**.

| Endpoint | Qué se usa |
|---|---|
| `GET /api/v1/zones` | `zoneId`, `name`, `capacity`, `occupancy`, `occupancyPct`, `alertLevel`, `lastEventAt` |
| `GET /api/v1/alerts?limit=N` | `zoneId`, `from`, `to`, `occupancy`, `capacity`, `occupancyPct`, `occurredAt`, `detectedAt` |

Textos de la interfaz en español. La página en `http://localhost:5173` (puerto por defecto
de la herramienta de desarrollo; se confirma en el plan).

## Criterios de aceptación
- **CA1** — Con el entorno, la `api` y el `processor` en marcha, los comandos documentados
  levantan el dashboard y la página muestra las 5 zonas (V2 + `PALCO`) con nombre, ocupación,
  capacidad, porcentaje y nivel, iguales a los de `GET /api/v1/zones`.
- **CA2** — Con el simulador en marcha, la ocupación cambia en pantalla sin recargar, con
  menos de 5 s de retraso respecto a la API.
- **CA3** — Cuando una zona cruza un umbral, su nivel cambia en pantalla (color y texto) y el
  cambio aparece el primero en la lista de cambios, con zona, niveles, ocupación y hora.
- **CA4** — Con PostgreSQL en pausa (la API responde `503`) o con la `api` parada, la página
  sigue mostrando los últimos datos, con un aviso y la hora de la última actualización buena;
  al volver, el aviso desaparece y los datos se ponen al día sin recargar.
- **CA5** — En una pantalla de 375 px de ancho no hay desplazamiento horizontal y todas las
  zonas se leen; la consola del navegador no muestra errores ni avisos (verificado con el MCP
  de Chrome DevTools).
- **CA6** — Los tests del dashboard pasan en verde. El del aviso de la regla 3 se valida
  quitando el mecanismo (p. ej. vaciar los datos al fallar) y viéndolo fallar (principio 2).
- **CA7** — El código del dashboard no contiene los umbrales de alerta (regla 2): el nivel se
  lee de la API.
- **CA8** — Desde un clon limpio, con Docker y Node, los comandos documentados bastan para
  levantarlo y pasar sus tests (principio 7).

## Decisiones
- **D1 — Resuelta: opción a)** (2026-10-25).
  **D1 — Cómo se entera la página de los cambios.**
  - **a) (Recomendada)** Preguntar a la API cada pocos segundos (*polling*, p. ej. cada 2 s)
    con los endpoints que ya existen. Sin cambios en el backend; si la API cae, el siguiente
    intento lo detecta solo, que es justo lo que pide la regla 4. Coste: unas pocas peticiones
    por segundo por pantalla abierta, irrelevante aquí.
  - b) *Server-Sent Events*: un endpoint nuevo que empuja los cambios. Más inmediato, pero
    cambia la API, hay que decidir de dónde salen los cambios (la API no los ve: los aplica el
    `processor`) y gestionar reconexiones.
  - c) WebSocket: lo mismo que b), con un protocolo bidireccional que aquí no hace falta.
- **D2 — Resuelta: opción a)** (2026-10-25).
  **D2 — Cómo llega el navegador a la API.** La página se sirve en un puerto (5173) y la API
  en otro (8080); el navegador bloquea esas peticiones si la API no lo permite (CORS).
  - **a) (Recomendada)** El servidor de desarrollo del dashboard reenvía `/api` a
    `localhost:8080` (*proxy*). Para el navegador todo es el mismo origen: la API no cambia.
  - b) Permitir CORS en la API para `localhost:5173`: cambia la API para un caso solo local.
  - c) Compilar el dashboard y servirlo desde la propia `api`: un solo puerto, pero ata la
    compilación de Java a la de Node.
- **D3 — Resuelta: opción a)** (2026-10-25).
  **D3 — Con qué se construye y se prueba.**
  - **a) (Recomendada)** Vite (servidor de desarrollo y compilación) + Vitest y React Testing
    Library para los tests, CSS propio sin librería de componentes, `fetch` sin librería de
    datos. Lo mínimo que el autor puede explicar línea a línea.
  - b) Añadir una librería de componentes (MUI, etc.) y otra de datos (TanStack Query): menos
    código propio, más dependencias y más «magia» que defender (principio 1).

## Notas para el autor
- Crea un módulo nuevo (`dashboard/`) y añade dependencias de Node: aprobar esta spec cuenta
  como el «pregunta antes» de `CLAUDE.md` para las dos cosas. No toca `compose.yaml` ni la API.
- El dashboard no entra en el reactor de Maven: tiene sus propios comandos (`npm`). Requiere
  Node instalado (aquí, 24); la versión mínima, en el plan.
- Versiones exactas de React, Vite y el resto, en el plan.
