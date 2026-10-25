# ADR 0007 — Trazas con OpenTelemetry y Jaeger

- **Estado:** Aceptada
- **Fecha:** 2026-10-25
- **Spec:** [007 — Trazas](../../specs/007-trazas/spec.md) (D1, D2)

## Contexto
Las métricas (spec 006) dicen cuántos eventos van bien o mal, no qué le pasó a uno concreto.
Para seguir un evento había que buscar su `eventId` en los logs de dos servicios y reconstruir
a mano el orden y los tiempos. Un evento cruza un proceso (`api`), un broker (Kafka) y otro
proceso (`processor`); la traza tiene que atravesar los tres sin cambiar el cuerpo del mensaje.

## Decisión
1. **Instrumentación de Spring Boot, no propia.** `spring-boot-starter-opentelemetry` trae
   Micrometer Tracing con el puente a OpenTelemetry y el exportador OTLP. Spring MVC ya crea una
   *observation* por petición; Spring Kafka la crea al publicar y al consumir con
   `spring.kafka.template/listener.observation-enabled`, y escribe y lee la cabecera W3C
   `traceparent`. El código propio solo añade atributos de negocio (`TraceAttributes`) y un
   tramo para el envío al DLT.
2. **El contexto viaja en las cabeceras de Kafka.** `SensorEventMessage` v1 no cambia; un
   mensaje sin `traceparent` (anterior a esta spec u otro productor) empieza una traza nueva.
3. **Atributos de alta cardinalidad.** Con la API de Observation, los valores
   `highCardinalityKeyValue` van solo al tramo; los `lowCardinality`, también a la métrica. Así
   el `eventId` está en la traza (para buscarla) y nunca en una etiqueta de Prometheus (spec 006,
   regla 5).
4. **Jaeger 2 como backend** (D1): un contenedor sin configuración, en memoria, que recibe OTLP
   por HTTP (4318) y busca por atributo. UI y OTLP solo en `127.0.0.1`.
5. **Exportación asíncrona.** Los tramos van a un `BatchSpanProcessor`: una cola en memoria y un
   hilo aparte que envía. Si Jaeger no responde, se pierden tramos; los eventos no esperan.
6. **Sin tramos de SQL** (D2): el tramo de cada entrega ya mide la transacción y los reintentos.

## Alternativas descartadas
- **Agente Java de OpenTelemetry:** instrumenta todo sin tocar código, pero es magia en tiempo
  de arranque (un `-javaagent` por servicio) y duplicaría lo que Spring Boot ya hace.
- **Grafana Tempo:** se integra con el Grafana de la spec 006, pero necesita su propio fichero de
  configuración y una fuente de datos más; la búsqueda se hace con TraceQL.
- **OpenTelemetry Collector delante del backend:** permite cambiar de backend sin tocar las
  aplicaciones; hoy no resuelve ningún problema (principio 1).
- **Etiquetar con la API de tramos (`Tracer.currentSpan().tag`)**: funciona igual, pero ata el
  código a Micrometer Tracing y no deja visible la diferencia entre alta y baja cardinalidad.

## Consecuencias
- Una traza por recorrido: `http post /api/v1/events` → `crowdpulse.sensor-events send` (api) →
  `crowdpulse.sensor-events process` (processor), un tramo por intento; si va al DLT,
  `crowdpulse.dead.letter` → `crowdpulse.sensor-events.dlt send`. Un reproceso del DLT es la
  traza de su petición: el `KafkaTemplate` sustituye la `traceparent` vieja por la suya.
- La observation de la entrega ya se ha cerrado cuando el gestor de errores llama al recoverer
  (su contexto sigue activo), así que el envío al DLT necesita su propia observation. Como toda
  observation, también es un timer: `crowdpulse_dead_letter_seconds`.
- Los logs ECS llevan `traceId` y `spanId` (nombres del MDC; no `trace.id`).
- `/actuator/*` y las tareas `@Scheduled` no generan trazas. El predicado que excluye actuator
  quita también su métrica HTTP, que nadie usa.
- Muestreo al 100 % por defecto (`CROWDPULSE_TRACING_SAMPLING` lo baja). Las trazas se pierden
  al parar Jaeger.
- `TracingIT` (api y processor) lo comprueba con un exportador en memoria y el OTLP real
  apuntando a un servidor que nunca responde. Validado con mutaciones (sin observation en la
  plantilla o el listener, `outcome` antes de `process`, sin el predicado, sin `traceId` en el MDC).
