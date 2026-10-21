# Spec 002 — Alertas de ocupación por umbrales

- **Estado:** Aprobada (2026-10-21)
- **Fase:** 1 (D4)
- **Fecha:** 2026-10-21
- **Depende de:** [spec 001](../001-ingesta-mvp/spec.md)

## Contexto y objetivo
CrowdPulse ya sabe cuánta gente hay en cada zona, pero nadie se entera si una zona se
está llenando: hay que consultar la API y hacer la cuenta. Esta spec añade alertas:
el sistema detecta solo cuándo una zona se acerca a su capacidad o la supera, lo
registra y lo avisa.

La dificultad no es detectar el umbral, sino no avisar de más. Cerca del umbral la
ocupación sube y baja una persona cada vez (85,0 % → 84,995 % → 85,0 %...). Sin
**histéresis** cada oscilación generaría una alerta nueva; con ella, una zona que entra
en alerta solo sale cuando baja con claridad por debajo del umbral.

## Alcance

### Incluido
- Nivel de alerta de cada zona, recalculado con cada evento que cambia su ocupación.
- Histéresis para que una oscilación alrededor del umbral no genere alertas repetidas.
- Histórico de cambios de nivel, consultable por la API.
- Nivel actual de cada zona en las respuestas de zonas.
- Log `WARN` de cada cambio de nivel.
- Demo reproducible con el simulador.

### Fuera de alcance
- Notificaciones externas (correo, SMS, webhooks, push).
- Umbrales distintos por zona o configurables por API: los mismos para todas las zonas.
- Reconocer o cerrar alertas a mano.
- Alertas por ritmo de entrada, por sensores sin actividad o por anomalías.
- Dashboard y métricas (fase 4).

## Niveles y umbrales
Dos umbrales y 5 puntos de histéresis (D1). Los mismos para todas las zonas.

| Nivel | Se entra cuando la ocupación es | Se sale (baja de nivel) cuando la ocupación es |
|-------|----------------------------------|------------------------------------------------|
| `NORMAL` | — | — |
| `WARNING` (aviso) | ≥ 85 % | < 80 % |
| `CRITICAL` (crítico) | ≥ 95 % | < 90 % (pasa a `WARNING`) |

Ejemplo con `NORTE` (20 000 plazas):

| Ocupación | % | Nivel | ¿Cambio registrado? |
|-----------|---|-------|---------------------|
| 16 999 → 17 000 | 85,0 | `NORMAL` → `WARNING` | Sí |
| 17 000 → 16 999 | 84,995 | `WARNING` | No (histéresis) |
| … → 16 000 | 80,0 | `WARNING` | No |
| 16 000 → 15 999 | 79,995 | `WARNING` → `NORMAL` | Sí |
| … → 19 000 | 95,0 | `WARNING` → `CRITICAL` | Sí (pasando antes por `WARNING` a 17 000) |
| … → 20 500 | 102,5 | `CRITICAL` | No |
| … → 17 999 | 89,995 | `CRITICAL` → `WARNING` | Sí |

## Reglas de negocio
1. El nivel se recalcula después de cada evento que cambia la ocupación de la zona.
   Un duplicado o un `EXIT` anómalo (la zona ya estaba a 0) no cambian la ocupación, así
   que no pueden cambiar el nivel.
2. Se compara el **porcentaje exacto** (ocupación × 100 / capacidad), no el redondeado
   a un decimal que muestra la API. 16 999 de 20 000 (84,995 %) se muestra como `85.0`
   pero sigue en `NORMAL`.
3. Subir de nivel: en cuanto la ocupación alcanza el umbral de entrada del nivel.
4. Bajar de nivel: solo cuando la ocupación queda por debajo del umbral de salida del
   nivel actual (histéresis).
5. Por encima del 100 % la zona sigue en `CRITICAL`; no hay más niveles.
6. Cada cambio de nivel genera **un** registro en el histórico. Permanecer en el mismo
   nivel no genera nada.
7. El registro del evento, la actualización de la ocupación y el cambio de nivel (si lo
   hay) ocurren en la **misma transacción**: o se aplican todos o ninguno.
8. Peticiones concurrentes que cruzan el umbral no generan cambios duplicados: cada
   cruce se registra exactamente una vez.
9. Cada cambio guarda dos tiempos: `occurredAt` del evento que lo provocó (tiempo de
   negocio) y `detectedAt` (cuándo lo detectó el sistema).
10. El nivel es estado persistente: sobrevive a un reinicio de la API.
11. Al desplegar esta spec, cada zona arranca con el nivel que corresponde a su
    ocupación actual, sin generar registros en el histórico.

## API

### `GET /api/v1/zones` y `GET /api/v1/zones/{zoneId}` — campo nuevo
Cada zona incluye `alertLevel` (D2). Es un cambio **compatible**: los clientes existentes ignoran el campo nuevo y el resto no cambia.
```json
{
  "zoneId": "NORTE",
  "name": "Fondo Norte",
  "capacity": 20000,
  "occupancy": 17000,
  "occupancyPct": 85.0,
  "lastEventAt": "2026-10-21T18:30:05.123Z",
  "alertLevel": "WARNING"
}
```

### `GET /api/v1/alerts`
Histórico de cambios de nivel, **los más recientes primero**.
```json
[
  {
    "alertId": "8c0e2f5a-1b3d-4c6e-9f70-2a4b6c8d0e1f",
    "zoneId": "NORTE",
    "from": "NORMAL",
    "to": "WARNING",
    "occupancy": 17000,
    "capacity": 20000,
    "occupancyPct": 85.0,
    "eventId": "3f2b8c1e-6a4d-4e0f-9b7a-2c5d8e1f0a93",
    "occurredAt": "2026-10-21T18:30:05.123Z",
    "detectedAt": "2026-10-21T18:30:05.210Z"
  }
]
```
- `zoneId` (opcional): solo los cambios de esa zona. Si la zona no existe → **404**
  con `ProblemDetail`.
- `limit` (opcional, 1–500, por defecto 50). Fuera de rango o no numérico → **400**
  con `ProblemDetail`.
- Sin cambios registrados → `[]`.

## Simulador y demo
Con las capacidades de la spec 001 (15 000 y 20 000) el simulador nunca llega a los
umbrales: para que una zona de 20 000 llegue al 85 % harían falta 17 000 personas
dentro. Por eso se añade una zona pequeña de demo con una migración nueva (D3):

| zoneId | Nombre | Capacidad |
|--------|--------|-----------|
| `PALCO` | Palco VIP | 200 |

El simulador no cambia: basta con `--zones=PALCO`. Su lista de zonas por defecto
sigue siendo la de la spec 001.

## Criterios de aceptación
- **CA1** — Con `NORTE` a 16 999, un `ENTRY` la deja a 17 000: `GET /api/v1/zones/NORTE`
  devuelve `alertLevel: WARNING` y `GET /api/v1/alerts` contiene un cambio
  `NORMAL → WARNING` con `occupancy: 17000` y el `eventId` de ese evento.
- **CA2** — Histéresis: desde `WARNING` a 17 000, bajar hasta 16 000 (80,0 %) no genera
  ningún cambio; un `EXIT` más (15 999) genera `WARNING → NORMAL`.
- **CA3** — Alternar 10 veces `EXIT` y `ENTRY` entre 17 000 y 16 999 genera **un único**
  cambio de nivel en total.
- **CA4** — A 19 000 (95 %) la zona pasa a `CRITICAL`; a 20 500 (102,5 %) sigue en
  `CRITICAL`; vuelve a `WARNING` solo al bajar a 17 999 (89,995 %).
- **CA5** — Reenviar el evento que provocó un cambio de nivel no genera un segundo
  cambio (sigue siendo 202).
- **CA6** — Si falla el registro del cambio de nivel, tampoco se guardan el evento ni la
  ocupación.
- **CA7** — 20 hilos que envían a la vez los `ENTRY` que cruzan el 85 % generan
  exactamente un cambio `NORMAL → WARNING`.
- **CA8** — `GET /api/v1/alerts` devuelve los cambios ordenados del más reciente al más
  antiguo, respeta `zoneId` y `limit`, responde 404 con una zona inexistente y 400 con un
  `limit` inválido.
- **CA9** — Cada cambio de nivel escribe un log `WARN` con `zoneId`, `from`, `to`,
  `occupancyPct` y `eventId`.
- **CA10** — Tras reiniciar la API, `alertLevel` y el histórico son los mismos.
- **CA11** — Demo: con `--zones=PALCO`, el histórico de `PALCO` muestra al menos
  `NORMAL → WARNING` y `WARNING → CRITICAL`.
- **CA12** — Los criterios de la spec 001 siguen cumpliéndose y `./mvnw verify` pasa en
  verde desde un clon limpio.

## Decisiones del autor (2026-10-21)
| Id | Decisión | Elegida | Descartadas |
|----|----------|---------|-------------|
| D1 | Umbrales e histéresis | `WARNING` ≥ 85 % (sale < 80 %), `CRITICAL` ≥ 95 % (sale < 90 %) | 80/90 %; un solo nivel al 90 % |
| D2 | Nivel en las zonas | Campo nuevo `alertLevel` en `GET /api/v1/zones` (compatible) | Solo en `/api/v1/alerts` |
| D3 | Demo | Zona nueva `PALCO` (200 plazas) por migración | Bajar las capacidades actuales; perfil de llenado en el simulador |
| D4 | Fase | Fase 1: no añade tecnología nueva (constitución enmendada) | Hacer antes la fase 2 (Kafka) |
