# Spec 006 — Métricas

- **Estado:** Aprobada (2026-10-25), con las opciones recomendadas en D1 y D2
- **Fase:** 4
- **Fecha:** 2026-10-25
- **Depende de:** [spec 005](../005-remates-de-fiabilidad/spec.md)

## Contexto y objetivo
Hasta ahora, para saber cómo va el sistema hay que leer logs o consultar la API. El
principio 9 de la constitución pide que en la fase 4 los logs de anomalías «se conviertan
en métricas, no se reinventen». La fase 4 trae tres cosas nuevas (métricas, trazas y
dashboard); esta spec solo hace la primera, para meter una sola tecnología cada vez.

Objetivo: que, con el simulador en marcha, un panel muestre en vivo cuántos eventos
entran, qué les pasa, cuánto tardan en procesarse y cómo está cada zona; y que una caída
de PostgreSQL o de Kafka se **vea** en ese panel sin mirar logs.

Preguntas que el panel tiene que contestar:
1. ¿Cuántos eventos por segundo acepta la API y cuántos rechaza (y por qué)?
2. ¿Qué pasa con los eventos en el `processor`: aplicados, duplicados, `EXIT` en 0, DLT?
3. ¿Cuánto tarda un evento desde que la API lo acepta hasta que cuenta en la ocupación?
4. ¿Va el `processor` al día o se le acumulan mensajes en Kafka?
5. ¿Cuál es la ocupación y el nivel de alerta de cada zona?
6. ¿Está el `processor` esperando a que vuelva la base de datos?

## Alcance

### Incluido
- `api` y `processor` exponen sus métricas en formato Prometheus.
- Métricas propias de negocio (ver Contrato), además de las que Spring Boot ya da (HTTP,
  JVM, pool de conexiones, cliente de Kafka).
- Prometheus y Grafana en `compose.yaml`, con un panel de Grafana guardado en el
  repositorio que se carga solo al arrancar (sin pasos a mano).
- Una demo reproducible: simulador + pausa de PostgreSQL, vista en el panel.

### Fuera de alcance
- Trazas (OpenTelemetry) y dashboard en React: specs siguientes de la fase 4.
- Alertas de Prometheus/Alertmanager: las alertas de negocio ya las genera el `processor`.
- Métricas del simulador: es un proceso corto que ya imprime su informe.
- Retención larga, alta disponibilidad o autenticación de Prometheus/Grafana.

## Reglas de negocio
1. **Las métricas no son la fuente de verdad** (principio 6). La ocupación de la métrica
   se lee de PostgreSQL; si no coincide con `GET /zones`, manda la base de datos. Ningún
   contador en memoria sustituye a lo que hay en la tabla.
2. **Un evento duplicado se cuenta como duplicado, no como aplicado.** La suma de
   aplicados por zona tiene que cuadrar con los eventos distintos que se enviaron.
3. **Los contadores cuentan lo que se ha confirmado.** En el `processor`, un evento cuenta
   como aplicado solo si su transacción se confirmó; si se revierte y se reintenta, no
   cuenta dos veces.
4. **La latencia se mide con relojes del sistema** (principio 4): `processedAt − receivedAt`.
   `occurredAt` viene del reloj del sensor y puede ir desfasado; su diferencia con
   `receivedAt` se mide aparte como «retraso del sensor» y no se mezcla con la latencia.
5. **Etiquetas con pocos valores.** Se puede etiquetar por zona, tipo y resultado; nunca
   por `eventId`, `sensorId` ni nada que crezca sin límite.

## Contrato
Métricas propias (nombres en formato Prometheus; prefijo `crowdpulse_`):

| Métrica | Tipo | Servicio | Etiquetas | Qué cuenta |
|---|---|---|---|---|
| `crowdpulse_events_processed_total` | contador | `processor` | `zone`, `outcome` = `applied` \| `duplicate` \| `exit_at_zero` | Eventos con transacción confirmada |
| `crowdpulse_events_dead_lettered_total` | contador | `processor` | `reason` = `content` \| `other` | Mensajes enviados al DLT |
| `crowdpulse_event_processing_latency_seconds` | histograma | `processor` | — | `processedAt − receivedAt` de los aplicados |
| `crowdpulse_event_sensor_delay_seconds` | histograma | `processor` | — | `receivedAt − occurredAt` de los aplicados |
| `crowdpulse_zone_occupancy` | gauge | `processor` | `zone` | Ocupación actual (leída de la BD) |
| `crowdpulse_zone_capacity` | gauge | `processor` | `zone` | Capacidad de la zona |
| `crowdpulse_zone_alert_level` | gauge | `processor` | `zone` | 0 normal, 1 aviso, 2 crítico |
| `crowdpulse_alerts_total` | contador | `processor` | `zone`, `level` | Cambios de nivel registrados en `alert` |
| `crowdpulse_database_outage` | gauge | `processor` | — | 1 mientras espera a que vuelva la BD; 0 si no |

Las peticiones a la `api` (pregunta 1) se cuentan con la métrica HTTP de Spring Boot,
`http_server_requests_seconds_count{method="POST", uri="/api/v1/events"}` por `status`
(plan, sección 0, aprobado el 2026-10-25); no se crea una propia.

El retraso de consumo de Kafka (pregunta 4) sale de las métricas del cliente de Kafka que
ya publica Spring Boot; no se crea una propia.

Grafana en `http://localhost:3000` y Prometheus en `http://localhost:9090`.

## Criterios de aceptación
- **CA1** — `GET /actuator/prometheus` responde en la `api` y en el `processor` con las
  métricas del contrato.
- **CA2** — Tras enviar N eventos distintos y D duplicados de ellos, `events_processed_total`
  suma N con `outcome=applied`/`exit_at_zero` y D con `outcome=duplicate`. Si se quita la
  distinción de duplicados, el test falla (principio 2).
- **CA3** — Un evento cuya transacción se revierte y luego se reintenta con éxito cuenta
  una sola vez como aplicado.
- **CA4** — `zone_occupancy` coincide con `GET /zones/{id}` después de procesar eventos,
  también tras reiniciar el `processor` (la métrica no arranca en 0).
- **CA5** — Mientras PostgreSQL está en pausa, `database_outage` vale 1 y la `api` cuenta
  respuestas `503`; al volver, `database_outage` vale 0.
- **CA6** — `docker compose up -d` levanta Prometheus y Grafana; el panel aparece cargado
  y Prometheus ve los dos servicios como `up`, sin pasos a mano.
- **CA7** — Demo: simulador a 100 ev/s durante 60 s con PostgreSQL en pausa 30 s → en el
  panel se ven la caída de aplicados, la subida del retraso de consumo y de `database_outage`,
  y la recuperación; al final, la ocupación del panel coincide con la de la API.
- **CA8** — `./mvnw verify` (por módulos) pasa en verde desde un clon limpio.

## Decisiones
- **D1 — Resuelta: opción a)** (2026-10-25).
  **D1 — Cómo llega Prometheus al `processor`.** Prometheus corre en
  Docker y el `processor` solo escucha en `127.0.0.1` (spec 005, regla 1). En Docker
  Desktop, una conexión desde un contenedor al equipo no llega por `127.0.0.1`, así que
  Prometheus no lo vería. Opciones:
  - **a) (Recomendada)** Puerto de gestión aparte en el `processor` (p. ej. 8082) solo con
    `health` y `prometheus`, abierto a todas las direcciones; el endpoint de reproceso
    sigue solo en `127.0.0.1`. Matiza la regla 1 de la 005: lo que se abre es de solo
    lectura.
  - b) Prometheus fuera de Docker: rompe el principio 7 (un `docker compose up` basta).
  - c) Red `host` en Docker Desktop: experimental y depende de la versión instalada.
- **D2 — Resuelta: se acepta** (2026-10-25).
  **D2 — Exposición del endpoint en la `api`.** La `api` ya escucha en
  todas las direcciones, así que `/actuator/prometheus` quedaría visible en la red local.
  Propuesta: aceptarlo (no hay datos personales ni secretos, solo recuentos) y anotarlo con
  el problema abierto de la autenticación.

## Notas para el autor
- Añade dependencias (registro de Prometheus para Micrometer) y modifica `compose.yaml`:
  aprobar esta spec cuenta como el «pregunta antes» de `CLAUDE.md` para ambas cosas.
- Las versiones de las imágenes de Prometheus y Grafana se fijarán en el plan.
