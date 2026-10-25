# Plan 007 — Trazas

- **Estado:** Aprobado (2026-10-25); implementado (desviaciones en `tasks.md`, «Hecho así»)
- **Spec:** [`spec.md`](spec.md)
- **Fase:** 4
- **Fecha:** 2026-10-25

**Qué se añade:** `org.springframework.boot:spring-boot-starter-opentelemetry` en `api` y
`processor` (Micrometer Tracing con el puente a OpenTelemetry y el exportador OTLP; versión de
Spring Boot 4.1.1), `io.opentelemetry:opentelemetry-sdk-testing` solo para tests (versión del
BOM de OpenTelemetry que importa Spring Boot) y Jaeger en `compose.yaml`. Sin módulos nuevos ni
migraciones. Lo cubre la aprobación de la spec.

**Idea general:** casi todo lo da Spring Boot con configuración. Spring MVC ya crea una
*observation* por petición HTTP; Spring Kafka la crea al publicar y al consumir si se le
activa, y escribe/lee la cabecera `traceparent`. Micrometer Tracing convierte cada
observation en un tramo y lo exporta por OTLP. El código propio se limita a **poner los
atributos de negocio** en el tramo que ya existe.

---

## 0. Ajustes a la spec (ya aplicados al aprobarla)

1. **Nombres de los campos de log:** `traceId` y `spanId`, no `trace.id`/`span.id`. El formato
   ECS de Spring Boot 4.1.1 copia el MDC tal cual (documentación, «Structured Logging › Elastic
   Common Schema»; y en la clase `ElasticCommonSchemaStructuredLogFormatter` no hay ninguna
   conversión a `trace.id`). Renombrarlos exigiría un formateador propio para un cambio de
   nombre: no compensa (principio 1).
2. **Atributos en las peticiones rechazadas:** se ponen en el controlador, así que solo existen
   si la petición pasó Bean Validation. Un 400 no los lleva; un 422 por zona desconocida y un
   503 sí.
3. **Reproceso del DLT:** es una sola traza (la de la petición de reproceso) con un tramo de
   publicación por mensaje y, colgando de cada uno, su proceso en el `processor`.

## 1. Dependencia y configuración común (CA9, CA11)

En `application.yml` de los dos servicios:

```yaml
management:
  tracing:
    sampling:
      # Spec 007: keep every trace locally (default 0.1); CROWDPULSE_TRACING_SAMPLING lowers it.
      probability: ${CROWDPULSE_TRACING_SAMPLING:1.0}
  opentelemetry:
    tracing:
      export:
        otlp:
          # 127.0.0.1, not localhost: on Windows localhost may go to ::1, and compose publishes
          # Jaeger only on 127.0.0.1.
          endpoint: ${CROWDPULSE_OTLP_ENDPOINT:http://127.0.0.1:4318/v1/traces}
  observations:
    enable:
      # The zone gauges refresh every 5 s (spec 006): a trace each time would be pure noise (rule 6).
      tasks.scheduled: false
spring:
  kafka:
    template:
      observation-enabled: true   # api: publish span + traceparent header
    listener:
      observation-enabled: true   # processor: consume span, parent read from the header
```

- `spring.application.name` ya existe en los dos (`crowdpulse-api`, `crowdpulse-processor`): es
  el `service.name` con el que aparecen en Jaeger.
- **Exportación asíncrona (regla 1):** Spring Boot pone el exportador detrás de un
  `BatchSpanProcessor`: los tramos se encolan en memoria y un hilo aparte los envía. Si Jaeger
  no está, el envío falla en ese hilo (un `WARN` del SDK) y la cola, que tiene límite, descarta
  tramos. La petición y el listener nunca esperan al exportador. Esto lo comprueba el CA6.
- **Nombres exactos de las propiedades:** los de arriba salen de la documentación de Spring
  Boot 4.1 (salvo las de Kafka, comprobadas en sus metadatos). Al añadir la dependencia se
  comprueban en `spring-configuration-metadata.json`; si alguna difiere, se usa la real y se
  anota en `tasks.md`.
- **Integración con Docker Compose:** Spring Boot puede tener una «service connection» para el
  contenedor de trazas que fije el endpoint sola. Se comprueba; si la hay, se deja (da el mismo
  resultado) y se anota.

## 2. Actuator sin trazas (regla 6; CA8)

No hay propiedad para excluir actuator. Un bean `ObservationPredicate` en cada servicio
(`common/ObservationConfig`):

```java
@Bean
ObservationPredicate noActuatorObservations() {
    // Spec 007, rule 6: Prometheus reads /actuator/prometheus every 5 s.
    return (name, context) -> !(context instanceof ServerRequestObservationContext request
            && request.getCarrier().getRequestURI().startsWith("/actuator"));
}
```

- **Coste:** el predicado quita la observation entera, así que también desaparece
  `http_server_requests` para `/actuator/*`. Nadie la usa (el panel de la 006 solo mira
  `/api/v1/events`). *Descartado:* filtrar solo en la exportación de tramos
  (`SpanExportingPredicate`): conserva esa métrica, pero el tramo se sigue creando y sus logs
  llevarían un `traceId` que no está en Jaeger.
- En el `processor`, actuator vive en su propio servidor (8082, ADR 0006); se comprueba que el
  predicado también le aplica.

## 3. Atributos de negocio (contrato; CA1, CA2)

Con la API de Observation, no con la de tramos: `ObservationRegistry.getCurrentObservation()`
y `highCardinalityKeyValue(...)`.

- **Por qué alta cardinalidad:** en Micrometer, los *low cardinality* van a las métricas **y**
  a los tramos; los *high cardinality* solo a los tramos. Es exactamente la regla 5 de la spec
  (el `eventId` en la traza) sin romper la 5 de la spec 006 (nunca en una métrica). Usar la
  API de tramos (`Tracer.currentSpan().tag(...)`) también funcionaría, pero ata el código a
  Micrometer Tracing y no deja clara esa diferencia.
- **Si no hay observation** (tests sin trazas, o un hilo sin contexto) `getCurrentObservation()`
  devuelve `null`: no se hace nada.

Una clase pequeña por servicio, `TraceAttributes` (paquete `event`), con los nombres del
contrato como constantes:

| Dónde | Qué pone |
|---|---|
| `api`, `EventController.accept`, antes de `publish` | `crowdpulse.event.id`, `crowdpulse.zone.id`, `crowdpulse.event.type` en la observation HTTP |
| `processor`, `SensorEventListener.onMessage`, tras leer el JSON | los tres anteriores y `crowdpulse.event.occurred_at` (ISO-8601) |
| `processor`, `SensorEventListener.onMessage`, tras `process` | `crowdpulse.outcome` = `processed.outcome()` en minúsculas |

- El `outcome` se pone **después** de que `process` vuelva, como la métrica (spec 006, regla 3):
  un intento que se revierte lanza la excepción antes y no lo lleva; la observation lo marca
  como error con la excepción (regla 3 de esta spec). Cada reentrega del `DefaultErrorHandler`
  vuelve a pasar por el listener, así que es una observation nueva con el mismo padre (el de la
  cabecera): un tramo por intento en la misma traza, sin código propio.
- Los intentos que fallan antes de leer el JSON (JSON roto, versión desconocida) no llevan
  atributos de evento: su tramo tiene solo el error.

## 4. DLT (CA5)

El `DeadLetterPublishingRecoverer` publica con el `KafkaTemplate` del `processor`, que con
`observation-enabled` crea su tramo de publicación. En el `loggingRecoverer` de `KafkaConfig`
(donde ya se calcula el `reason` para la métrica) se añaden a la observation actual
`crowdpulse.outcome=dead_lettered` y `crowdpulse.dead_letter.reason`.

- **A comprobar en la tarea:** si cuando el gestor de errores llama al recoverer la observation
  del listener sigue abierta (entonces el envío al DLT cuelga de la traza del evento sin hacer
  nada). Si no lo está, el recoverer abre una observation propia, `crowdpulse.dead_letter`,
  con el contexto leído de la cabecera `traceparent` del mensaje. Se elige lo primero si basta.
- El mensaje del DLT conserva las cabeceras del original, incluida su `traceparent` (lo hace
  Spring Kafka): desde el DLT se puede volver a la traza original.

## 5. Reproceso del DLT (regla 7; sección 0, punto 3)

`DeadLetterReplayer.republish` copia hoy todas las cabeceras salvo las del DLT. Con la
observation de la plantilla, el mensaje llevaría **dos** `traceparent`: la vieja (copiada) y la
nueva. Cuál gana depende de si el lector usa la primera o la última cabecera, y eso no se deja
al azar: `republish` deja también fuera `traceparent`, `tracestate` y `baggage` (las cabeceras
de W3C Trace Context), y la traza del reproceso es la de su petición.

## 6. Logs con `traceId` (CA7)

Con Micrometer Tracing en el classpath, Spring Boot pone `traceId` y `spanId` en el MDC mientras
hay un tramo abierto, y el formato ECS los escribe. No hace falta configurar nada. Las líneas de
log que interesan ya se escriben dentro de un tramo: rechazos en `EventPublisher` y
`ApiExceptionHandler` (petición HTTP), duplicados y `EXIT` en 0 en `EventProcessor` (listener),
envío al DLT en `KafkaConfig` (sección 4). El aviso de caída de la BD (`DatabaseOutage`) sale del
gestor de errores: se comprueba si lleva `traceId`; la spec no lo exige.

## 7. Jaeger en `compose.yaml` (D1; CA9)

```yaml
  jaeger:
    # Spec 007 (D1): receives the traces of the api and the processor by OTLP; UI and search.
    image: 'jaegertracing/jaeger:2.X.Y'   # fixed in the task
    ports:
      # Only on this machine, like Prometheus and Grafana: no authentication.
      - '127.0.0.1:16686:16686'   # UI
      - '127.0.0.1:4318:4318'     # OTLP over HTTP, used by the apps running on the host
```

- **Jaeger 2** (no la 1.x, sin soporte): un contenedor con almacenamiento en memoria por
  defecto, sin fichero de configuración. Las trazas se pierden al pararlo; para una demo local
  es lo que se quiere (fuera de alcance: retención).
- **OTLP por HTTP (4318), no gRPC (4317):** es lo que usa Spring Boot por defecto y se depura
  con `curl`.
- Las apps corren en el equipo, así que aquí basta publicar en `127.0.0.1` (al revés que
  Prometheus, que entra desde Docker).
- **Versión:** la última 2.x estable en Docker Hub el día de la tarea, por número exacto.

## 8. Tests

Los componentes que exportan trazas no se crean en `@SpringBootTest` salvo con
`@AutoConfigureTracing` (documentación de Spring Boot 4.1, «Using Tracing»). Así que el resto de
ITs no cambia, y hay **un** IT nuevo por módulo con esa anotación, que tiene su propio contexto
(como `PrometheusEndpointIT`). Cada uno declara un `InMemorySpanExporter` como bean (Spring Boot
añade todos los `SpanExporter` que encuentra) y apunta el endpoint OTLP a un puerto cerrado: así
el CA6 se comprueba en los mismos tests, con el exportador real fallando.

| Test | Qué comprueba |
|---|---|
| `TracingIT` (`api`) | CA1, lado `api`: tras un `POST` aceptado, el mensaje de Kafka (leído con `TopicProbe`) lleva una `traceparent` con el mismo `traceId` que el tramo HTTP, y ese tramo tiene el `eventId` |
| `TracingIT` (`api`) | 422 por zona desconocida: tramo HTTP con código 422 y los atributos; 400: tramo sin ellos |
| `TracingIT` (`api`) | CA8: `GET /actuator/health` y `/actuator/prometheus` no exportan ningún tramo |
| `TracingIT` (`api`) | CA6: con el OTLP en un puerto cerrado, el `POST` responde 202 en el mismo tiempo que sin trazas (sin esperar al exportador) |
| `TracingIT` (`processor`) | CA1, lado `processor`: un mensaje publicado con una `traceparent` conocida produce un tramo de consumo con ese `traceId` y ese padre |
| `TracingIT` (`processor`) | CA2: `outcome` = `applied`, `duplicate` y `exit_at_zero` |
| `TracingIT` (`processor`) | CA3: mensaje sin `traceparent` → se aplica y deja un tramo con traza nueva y el `eventId` |
| `TracingIT` (`processor`) | CA4: falla el primer intento (espía en `AlertRepository`, como el CA3 de la 006) → dos tramos de consumo en la misma traza, el primero con error y sin `outcome`, el segundo `applied` |
| `TracingIT` (`processor`) | CA5: zona inexistente → tramo con `outcome=dead_lettered`, `reason=other` y publicación al DLT en la misma traza |
| `TracingIT` (`processor`) | Regla 7: un mensaje del DLT con `traceparent` vieja, tras `replay`, se procesa en la traza de la petición de reproceso, no en la vieja |
| `TracingIT` (`processor`) | CA6: con el OTLP en un puerto cerrado, los eventos se aplican y no hay nada en el DLT |
| `TracingIT` (`api` y `processor`) | CA7: con `OutputCaptureExtension`, la línea de log de un rechazo (api) y la de un duplicado (processor) llevan el `traceId` del tramo |

- **CA4 sin pausar PostgreSQL:** se fuerza el reintento con un fallo en el primer intento, que es
  lo mismo para el gestor de errores (reentrega del mensaje). La pausa real de la BD, con el
  backoff de la spec 004, se ve en la demo (CA10); repetirla aquí haría el test lento y no
  probaría nada distinto del mecanismo de trazas.
- **CA1 entero** (una sola traza `api` → `processor`) se prueba en dos mitades que se juntan en
  la cabecera: la `api` la escribe con su `traceId`; el `processor` continúa la que lea. No hay
  ningún test que arranque los dos servicios a la vez, y no hace falta uno para esto. La demo
  (CA9) lo comprueba de punta a punta en Jaeger.
- **Esperas:** el `BatchSpanProcessor` envía cada pocos segundos; en estos tests se baja su
  intervalo (propiedad de Spring Boot, a comprobar en la tarea) y se espera con `Eventually`.
- **Mutaciones (principio 2):**
  - `spring.kafka.template.observation-enabled: false` → no hay `traceparent` en el mensaje y
    falla el CA1 de la `api`.
  - `spring.kafka.listener.observation-enabled: false` → el tramo de consumo no aparece o no
    continúa la traza y falla el CA1 del `processor`.
  - Poner el `outcome` antes de `process` → el intento revertido lo lleva y falla el CA4.
  - Quitar el predicado de actuator → falla el CA8.
  - Copiar `traceparent` en `republish` → falla el test del reproceso (si no falla, es que el
    lector usa la última cabecera y la sección 5 se justificaría solo por claridad: se anota).
- **Unitarios:** `TraceAttributesTest` con un `ObservationRegistry` de prueba (`TestObservationRegistry`
  de `micrometer-observation-test`, que ya trae `spring-boot-starter-actuator-test`; se
  comprueba): pone los atributos como alta cardinalidad y no falla sin observation actual.

## 9. Demo y documentación (CA9, CA10)

- Demo a mano, documentada en `tasks.md` con capturas de Jaeger: simulador a 100 ev/s, 60 s,
  `--duplicates=5`; a los 15 s `docker compose pause postgres` durante 30 s.
  - Un duplicado: su `eventId` del log del `processor` → búsqueda en Jaeger por
    `crowdpulse.event.id` → traza con `outcome=duplicate`.
  - Un evento que esperó a la BD: tramos de consumo con error y el último `applied`; la espera
    se lee en la línea de tiempo.
  - Con `docker compose pause jaeger` un rato: el simulador sigue sin errores y la ocupación
    cuadra al final (CA6 de punta a punta; `pause` y no `stop`, porque una conexión colgada es
    peor que una rechazada).
- README, sección «Observabilidad»: Jaeger (`http://localhost:16686`), cómo buscar por
  `eventId`, qué tramos tiene una traza y la variable de muestreo.
- `CLAUDE.md`: Jaeger en la línea de `compose.yaml`.
- ADR 0007 «Trazas con OpenTelemetry y Jaeger»: D1 con sus alternativas, por qué la
  configuración de Spring Boot y no instrumentación propia (ni el agente Java de
  OpenTelemetry), y alta frente a baja cardinalidad.
- `./mvnw verify` por módulos desde un clon limpio.

## Orden propuesto para `tasks.md`
1. Dependencia, configuración común, predicado de actuator y Jaeger en compose; comprobar a mano
   que una petición llega a Jaeger (secciones 1, 2, 7).
2. `TracingIT` de la `api`: propagación, atributos, actuator, exportador caído (sección 3, `api`).
3. Atributos y `outcome` en el `processor`, con su `TracingIT` (sección 3, `processor`).
4. DLT y reproceso (secciones 4 y 5).
5. Logs con `traceId` (sección 6).
6. Demo, ADR 0007, documentación y verificación final (sección 9).
