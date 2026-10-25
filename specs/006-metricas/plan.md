# Plan 006 — Métricas

- **Estado:** Aprobado (2026-10-25), sección 0 incluida
- **Spec:** [`spec.md`](spec.md)
- **Fase:** 4
- **Fecha:** 2026-10-25

**Qué se añade:** la dependencia `io.micrometer:micrometer-registry-prometheus` en `api` y
`processor` (la versión la pone Spring Boot 4.1.1), y Prometheus y Grafana en `compose.yaml`.
Sin módulos nuevos ni migraciones. Todo esto lo cubre la aprobación de la spec.

---

## 0. Cambio a la spec (aprobado el 2026-10-25, ya aplicado)

**Quitar `crowdpulse_events_received_total` y usar la métrica HTTP que ya da Spring Boot.**
`http_server_requests_seconds_count{method="POST", uri="/api/v1/events", status="…"}`
cuenta exactamente lo mismo, por código de respuesta (202, 400, 422, 503), y sale gratis.
Escribir un contador propio sería reinventarla, y el principio 9 pide lo contrario. En el
panel, «aceptados» = `status="202"`, «no disponibles» = `status="503"`, «rechazados» = el
resto de `4xx`.

Si lo apruebas, el contrato de la spec pierde esa fila y el CA5 dice «la `api` cuenta
respuestas `503`». Si prefieres el contador propio, se escribe como un `HandlerInterceptor`.

## 1. Puerto de gestión del `processor` (D1; CA1, CA6)

```yaml
management:
  server:
    port: 8082
    # D1 of spec 006: only actuator lives here (health, prometheus), so it may be open: Prometheus
    # runs in Docker and cannot reach 127.0.0.1 on the host. The replay endpoint stays on 8081.
    address: ${CROWDPULSE_MANAGEMENT_ADDRESS:0.0.0.0}
  endpoints:
    web:
      exposure:
        include: health, prometheus
```

- **Cómo funciona:** con `management.server.port` distinto del principal, Spring Boot
  arranca un segundo servidor que solo sirve actuator, y `management.server.address` solo
  afecta a ese servidor (documentación de Spring Boot 4.1, «Customizing the Management Server
  Address»). El endpoint de reproceso sigue solo en `127.0.0.1:8081`.
- **Consecuencia:** `/actuator/health` del `processor` pasa al **8082**. Hay que cambiarlo en
  `CLAUDE.md`, `README.md` y `DatabaseOutageIT`.
- **ADR 0006** «Puerto de gestión separado en el `processor`»: matiza la regla 1 de la
  spec 005 (lo que se abre es de solo lectura) y explica por qué no las opciones b) y c).
- **La `api`** no cambia de puerto (D2): añade `prometheus` a la exposición y nada más.
- **Firewall de Windows:** al escuchar en `0.0.0.0` puede preguntar la primera vez. Se
  anota en el README.

### Tests
| Test | Qué comprueba |
|------|---------------|
| `ProcessorAddressIT` (ampliado) | Puerto principal: sigue sin responder en la IP del equipo (ya existía). Puerto de gestión: sí responde en esa IP. Con `management.server.port=0` y `@LocalManagementPort` |
| `ProcessorAddressIT` (ampliado) | `POST /admin/dead-letters/replay` en el puerto de gestión → 404: el reproceso no se ha colado en el puerto abierto |

- **Mutación:** quitar `management.server.port` → todo vuelve al 8081 en `127.0.0.1` y el test
  de la IP del equipo en el puerto de gestión falla.

## 2. Contadores del `processor` (reglas 2, 3; CA2, CA3)

### El resultado sale de `EventProcessor`
`process(...)` pasa de `void` a devolver un record:

```java
public record Processed(Outcome outcome, Instant processedAt, AlertLevel newLevel) {
    public enum Outcome { APPLIED, DUPLICATE, EXIT_AT_ZERO }
}
```
(`newLevel` es `null` si el nivel no cambió.)

`SensorEventListener` recibe ese resultado **después** de que `process` vuelva, y en ese
momento la transacción ya se ha confirmado (la cierra el proxy de `@Transactional` al
volver). Entonces llama a `EventMetrics.record(message, processed)`.

- **Por qué así (regla 3):** si la transacción se revierte, `process` lanza una excepción y no
  llega a devolver nada, así que no se cuenta nada. Contar dentro de `process` contaría
  también los intentos revertidos.
- *Descartado:* `TransactionSynchronization.afterCommit` dentro de `EventProcessor`. Hace lo
  mismo con más maquinaria. Devolver un valor se ve en la firma y se prueba sin Spring.

### `EventMetrics` (paquete `event`)
Recibe el `MeterRegistry` por constructor y registra:
- `crowdpulse.events.processed` (contador) con `zone` y `outcome` en minúsculas;
- `crowdpulse.event.processing.latency` (timer, `processedAt − receivedAt`) y
  `crowdpulse.event.sensor.delay` (timer, `receivedAt − occurredAt`), solo para
  `APPLIED`/`EXIT_AT_ZERO`. Los duplicados no se miden porque su `processedAt` no se guarda.
- `crowdpulse.alerts` (contador) con `zone` y `level`, cuando `newLevel` no es `null`.

Micrometer traduce los puntos a guiones bajos y añade `_total` o `_seconds`: así salen los
nombres del contrato.

- **Histogramas:** se activa `publishPercentileHistogram` en los dos timers, que es lo que
  permite calcular el p95 en Prometheus con `histogram_quantile`. Rango esperado de 1 ms a
  60 s (`minimumExpectedValue`/`maximumExpectedValue`) para no publicar cubetas inútiles.
- **Retraso del sensor negativo:** un sensor con el reloj adelantado (la API admite hasta
  5 min en el futuro) daría un retraso negativo, y un timer no admite valores negativos. Se
  registra como 0. Un sensor adelantado no tiene retraso; su desfase no es un problema del
  sistema.

### Envíos al DLT
En el `loggingRecoverer` de `KafkaConfig` (por donde ya pasa todo lo que va al DLT), un
contador `crowdpulse.events.dead.lettered` con `reason = content | other`. Para decidir el
`reason` se usa la misma lista que ya existe, `ContentFailure.TYPES`, sobre la causa más
interna. Así coincide con el reproceso de la spec 005.

### Tests
| Test | Qué comprueba |
|------|---------------|
| `EventMetricsTest` (unitario, `SimpleMeterRegistry`) | Etiquetas y valores por resultado; latencia; retraso negativo → 0; los duplicados no se miden; alerta solo si cambia el nivel |
| `ProcessingMetricsIT` | CA2: N eventos distintos + D duplicados por Kafka → aplicados + `exit_at_zero` = N y `duplicate` = D. Se comprueban diferencias antes/después, porque el contexto se comparte entre tests |
| `ProcessingMetricsIT` | CA3: `AlertRepository.insert` falla una vez (`@MockitoSpyBean`, lanza en el primer intento): la transacción se revierte, el gestor de errores reintenta y el evento cuenta **1** como aplicado |
| `DeadLetterIT` (ampliado) | JSON roto → `reason=content` +1; zona inexistente → `reason=other` +1 |

- **Mutación CA2:** contar `DUPLICATE` como `APPLIED` → falla.
- **Mutación CA3:** incrementar el contador dentro de `process` antes de `alerts.insert` →
  cuenta 2 y falla.
- El espía de `AlertRepository` se pone fuera de la transacción, en el repositorio, no en
  el bean `@Transactional` (está en MEMORY, «Errores a evitar»).

## 3. Gauges de zona leídos de la BD (regla 1; CA4)

`ZoneMetrics` (paquete `occupancy`):
- Cada 5 s (`@Scheduled`, `@EnableScheduling` en el `processor`) lee
  `SELECT zone_id, capacity, occupancy, alert_level` y actualiza tres `MultiGauge`:
  `crowdpulse.zone.occupancy`, `crowdpulse.zone.capacity` y `crowdpulse.zone.alert.level`
  (0/1/2), con la etiqueta `zone`. `MultiGauge.register(rows, true)` también da de alta
  las zonas nuevas.
- El primer refresco se hace al arrancar, así que tras un reinicio la métrica no empieza en 0.
- Si la BD no responde, se dejan los últimos valores y se registra en `DEBUG`: la caída ya la
  avisa `DatabaseOutage`, y en el panel se ve con `crowdpulse_database_outage`.
- El periodo se configura en `crowdpulse.metrics.zone-refresh: 5s`, en un record
  `@ConfigurationProperties`, como el resto de la configuración.

*Descartado:* un `Gauge` cuya función consulte la BD en cada lectura de Prometheus. Con la BD
caída, cada lectura se bloquearía hasta 3 s (el timeout de Hikari) por zona. *Descartado
también:* actualizar el gauge en memoria desde `EventProcessor`. Rompe el principio 6 y se
equivocaría tras un reinicio.

**Coste:** la ocupación del panel puede ir hasta 5 s por detrás de la BD. Para un panel que
Prometheus lee cada 5 s es aceptable; el CA4 se comprueba después de esperar a un refresco.

### Tests
| Test | Qué comprueba |
|------|---------------|
| `ZoneMetricsIT` | Tras procesar eventos y forzar un refresco (`refresh()` es un método normal, el `@Scheduled` solo lo llama), el gauge de cada zona es igual a la fila de `zone_occupancy` |
| `ZoneMetricsIT` | «Tras un reinicio»: se pone la ocupación en la BD con SQL (como si viniera de antes), se refresca y el gauge tiene ese valor sin que se haya procesado ningún evento |

- **Mutación:** que el gauge use un contador en memoria alimentado por `EventProcessor` → el
  segundo test ve 0 y falla.

## 4. `crowdpulse_database_outage` (CA5)

`DatabaseOutage` ya sabe si la BD está caída (`since != null`). Recibe el `MeterRegistry`
y registra un `Gauge` que vale 1 o 0 según eso. Es la misma información que ya escribe en
el log, ahora como métrica (principio 9).

- **Test:** `DatabaseOutageIT` (ampliado). Con PostgreSQL en pausa el gauge llega a 1; al
  reanudarlo, vuelve a 0.
- **API, CA5:** con PostgreSQL en pausa, `POST /events` responde 503. En `DatabaseDownIT`
  (ampliado), `http.server.requests` con `status=503` sube. Esto depende del cambio de la
  sección 0.

## 5. Métricas del cliente de Kafka (pregunta 4)

Spring Boot conecta las métricas del consumidor de Kafka a Micrometer sin configurar nada.
La que interesa es `kafka_consumer_fetch_manager_records_lag_max`: cuántos mensajes lleva
de retraso el consumidor. En la tarea se comprueba que aparece en `/actuator/prometheus` y
con qué nombre exacto. Si no aparece sola, se para y se pregunta antes de añadir nada.

## 6. `/actuator/prometheus` (CA1)

- `PrometheusEndpointIT` en `api` y `processor`, con `@AutoConfigureMetrics`. Hace falta
  porque, en los tests, Spring Boot solo crea el registro en memoria, no el de Prometheus
  (documentación de Spring Boot 4.1, «Using Metrics»). Comprueba que la respuesta contiene
  los nombres del contrato de su servicio.
- Solo ese test lleva la anotación: el resto sigue con el registro en memoria, y los
  contadores se leen del `MeterRegistry`.

## 7. Prometheus y Grafana en `compose.yaml` (CA6)

```yaml
  prometheus:
    image: 'prom/prometheus:vX.Y.Z'    # fijada en la tarea
    ports: ['9090:9090']
    volumes: ['./observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro']
    extra_hosts: ['host.docker.internal:host-gateway']
  grafana:
    image: 'grafana/grafana:X.Y.Z'     # fijada en la tarea
    ports: ['3000:3000']
    environment:
      - 'GF_AUTH_ANONYMOUS_ENABLED=true'        # read-only viewer without logging in
      - 'GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-admin}'   # development default
    volumes: ['./observability/grafana:/etc/grafana/provisioning:ro']
```

- **Puertos fijos** (como Kafka): son los que abre una persona en el navegador, y Prometheus
  no los descubre ninguna aplicación.
- **Destinos:** `host.docker.internal:8080` (api) y `host.docker.internal:8082` (processor),
  leídos cada 5 s. Las aplicaciones corren en el equipo, no en Docker; `extra_hosts` hace
  que ese nombre funcione también fuera de Docker Desktop.
- **Arranque de las apps:** la integración con Docker Compose de Spring Boot levantaría
  también estos dos servicios al ejecutar la app. Es inofensivo, pero se comprobará; si
  molesta, `spring.docker.compose.skip` o perfiles, decidido en la tarea.
- **Grafana con provisioning** (documentación de Grafana 13, «Provisioning»):
  - `observability/grafana/datasources/prometheus.yml`: datasource `http://prometheus:9090`;
  - `observability/grafana/dashboards/dashboards.yml` y `crowdpulse.json`: el panel.
- **El panel:** una fila por cada pregunta de la spec, seis en total y sin adornos. Se monta
  en la interfaz de Grafana, se exporta a JSON y se guarda en el repo. Cada consulta PromQL
  se explica en el README (`rate(...[1m])`, `histogram_quantile(0.95, ...)`).
- **Versiones:** la última estable de cada imagen en Docker Hub el día de la tarea, fijada
  por número exacto y nunca `latest`, igual que con `postgres:18` y `apache/kafka:4.2.2`.

## 8. Demo y documentación (CA7, CA8)

- Demo a mano, documentada en `tasks.md` con capturas del panel: simulador a 100 ev/s durante
  60 s, `docker compose pause postgres` 30 s. Se ve caer «aplicados», subir el retraso de
  consumo y `database_outage`, y luego la recuperación. Al final se compara la ocupación del
  panel con `GET /zones`.
- README: sección «Observabilidad» (URLs, panel, qué contesta cada fila y sus consultas).
- `CLAUDE.md`: salud del `processor` en el 8082.
- ADR 0006 (sección 1).
- `./mvnw verify` por módulos desde un clon limpio.

## Orden propuesto para `tasks.md`
1. Dependencia y puerto de gestión del `processor`, más el ADR 0006 (sección 1).
2. `Processed` y `EventMetrics`, con los tests de CA2 y CA3 (sección 2, sin el DLT).
3. Contador del DLT (sección 2).
4. `ZoneMetrics` (sección 3).
5. Gauge de `DatabaseOutage` y la métrica de la API (sección 4, según la sección 0).
6. `PrometheusEndpointIT` y comprobar las métricas de Kafka (secciones 5 y 6).
7. Prometheus y Grafana en compose, con el panel (sección 7).
8. Demo, documentación y verificación final (sección 8).
