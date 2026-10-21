# Spec 001 — Ingesta de eventos y ocupación por zona (MVP)

- **Estado:** Terminada (2026-10-21)
- **Fase:** 1
- **Fecha:** 2026-10-19

## Contexto y objetivo
Primera versión funcional de CrowdPulse. Un simulador genera eventos de entrada y
salida de varias zonas del estadio y los envía a una API. La API los valida, actualiza
la ocupación de cada zona y permite consultarla. Todo se levanta con Docker Compose.

El objetivo es tener una demo que funciona de extremo a extremo y una base sobre la
que introducir Kafka en la fase 2 sin cambiar el contrato de la API.

## Alcance

### Incluido
- Catálogo fijo de zonas con su capacidad, creado por migración.
- Endpoint para recibir eventos `ENTRY` / `EXIT`.
- Validación de eventos y rechazo de los inválidos con un error claro.
- Actualización de la ocupación por zona, persistida en PostgreSQL.
- Detección de eventos duplicados por `eventId` (no cambian la ocupación).
- Endpoints de consulta de ocupación (todas las zonas y una zona).
- Simulador ejecutable que genera tráfico configurable.
- `compose.yaml` con PostgreSQL; la API arranca contra él.
- Tests unitarios del dominio y de integración con Testcontainers.

### Fuera de alcance (fases posteriores)
- Kafka y procesamiento asíncrono (fase 2).
- Alertas y umbrales (spec propia).
- Dashboard web (fase 4).
- Métricas Prometheus y trazas (fase 4); solo `/actuator/health` en esta fase.
- Autenticación, gestión de zonas por API, multi-estadio.

## Zonas
Datos iniciales (capacidades ficticias, suman 70 000):

| zoneId | Nombre | Capacidad |
|--------|--------|-----------|
| `NORTE` | Fondo Norte | 20 000 |
| `SUR` | Fondo Sur | 20 000 |
| `ESTE` | Lateral Este | 15 000 |
| `OESTE` | Lateral Oeste | 15 000 |

## Contrato del evento
```json
{
  "eventId": "3f2b8c1e-6a4d-4e0f-9b7a-2c5d8e1f0a93",
  "sensorId": "NORTE-P03",
  "zoneId": "NORTE",
  "type": "ENTRY",
  "occurredAt": "2026-10-19T18:30:05.123Z"
}
```

| Campo | Tipo | Reglas |
|-------|------|--------|
| `eventId` | UUID | Obligatorio. Lo genera el sensor. Clave de idempotencia. |
| `sensorId` | string | Obligatorio, 1–50 caracteres. |
| `zoneId` | string | Obligatorio. Debe existir en el catálogo de zonas. |
| `type` | enum | Obligatorio. `ENTRY` o `EXIT`. Cada evento es 1 persona. |
| `occurredAt` | ISO-8601 UTC | Obligatorio. No más de 5 minutos en el futuro respecto al reloj del servidor. |

El sistema añade `receivedAt` al aceptarlo.

## API

### `POST /api/v1/events`
- **202 Accepted**: evento aceptado (nuevo o duplicado). Cuerpo vacío.
  Se usa 202 y no 201 para que el contrato siga valiendo cuando el procesamiento sea
  asíncrono.
- **400 Bad Request**: JSON mal formado, campos obligatorios ausentes, formato
  inválido u `occurredAt` demasiado en el futuro. Cuerpo `ProblemDetail` indicando
  los campos erróneos.
- **422 Unprocessable Content**: `zoneId` no existe en el catálogo.

### `GET /api/v1/zones`
Lista todas las zonas ordenadas por `zoneId`:
```json
[
  {
    "zoneId": "NORTE",
    "name": "Fondo Norte",
    "capacity": 20000,
    "occupancy": 12345,
    "occupancyPct": 61.7,
    "lastEventAt": "2026-10-19T18:30:05.123Z"
  }
]
```
- `occupancyPct` con un decimal.
- `lastEventAt`: `occurredAt` más reciente aplicado a la zona, `null` si no hay ninguno.

### `GET /api/v1/zones/{zoneId}`
Mismo objeto para una zona. **404** con `ProblemDetail` si no existe.

## Reglas de negocio
1. `ENTRY` suma 1 a la ocupación de la zona; `EXIT` resta 1.
2. Un evento con un `eventId` ya procesado se acepta (202) pero no cambia nada.
3. Registrar el evento y actualizar la ocupación ocurre en la misma transacción: o se
   aplican ambos o ninguno.
4. Peticiones concurrentes sobre la misma zona no pierden actualizaciones.
5. La ocupación nunca es negativa. Qué hacer con un `EXIT` que la dejaría por debajo de 0: se acepta,
   la ocupación se queda en 0, el evento se guarda marcado como anomalía y se registra en el log con su
   `eventId`. Refleja que en un sistema real los eventos llegan desordenados y no se pueden rechazar sin perder
   información.
6. La ocupación puede superar la capacidad: se muestra el porcentaje real (> 100 %).
   Es información que más adelante disparará alertas, no un error.
7. Se guarda el histórico completo, servirá para recalcular ocupación y para la fase 3.

## Simulador
- Aplicación ejecutable independiente de la API (módulo `simulator/`).
- Parámetros: URL de la API, eventos por segundo, duración, zonas.
- Genera sobre todo `ENTRY` al principio y `EXIT` al final, simulando la llegada y la
  salida del público, sin que la ocupación simulada de una zona sea negativa.
- Opción para reenviar un porcentaje de eventos con el mismo `eventId` (duplicados).
- Al terminar muestra: eventos enviados, duplicados enviados, respuestas por código.

## Criterios de aceptación
- **CA1** — Dada la zona `NORTE` con ocupación 0, cuando se envían 3 `ENTRY` y 1 `EXIT`
  válidos, entonces `GET /api/v1/zones/NORTE` devuelve `occupancy: 2`.
- **CA2** — Cuando se envía dos veces el mismo evento, ambas respuestas son 202 y la
  ocupación solo cambia una vez.
- **CA3** — Cuando falta un campo obligatorio o `type` no es válido, la respuesta es
  400 con `ProblemDetail` que nombra el campo, y la ocupación no cambia.
- **CA4** — Cuando `zoneId` no existe, la respuesta es 422.
- **CA5** — Cuando `occurredAt` está más de 5 minutos en el futuro, la respuesta es
  400. A 4 minutos en el futuro, se acepta. (Test con `Clock` fijo.)
- **CA6** — Cuando se envían 1 000 `ENTRY` distintos a la misma zona desde 20 hilos
  en paralelo, la ocupación final es exactamente 1 000.
- **CA7** — Comportamiento del `EXIT` con ocupación 0 según la opción elegida en la
  regla 5.
- **CA8** — Tras reiniciar la API, la ocupación consultada es la misma que antes.
- **CA9** — `GET /api/v1/zones/NOEXISTE` devuelve 404.
- **CA10** — Con `docker compose up -d` y la API en marcha, el simulador envía al
  menos 100 eventos/s durante 60 s sin errores 5xx, y la suma de ocupaciones coincide
  con entradas − salidas únicas enviadas.
- **CA11** — `./mvnw verify` pasa en verde desde un clon limpio con Docker en marcha.

  
