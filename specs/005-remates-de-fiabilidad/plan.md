# Plan 005 — Remates de fiabilidad

- **Estado:** Aprobado (2026-10-22)
- **Spec:** [`spec.md`](spec.md)
- **Fase:** 3
- **Fecha:** 2026-10-22

Sin módulos nuevos, dependencias nuevas, migraciones ni cambios en `compose.yaml`.

---

## 1. El `processor`, solo en local (regla 1; CA1)

```yaml
server:
  # Spec 005: only reachable from this machine unless told otherwise (CROWDPULSE_PROCESSOR_ADDRESS=0.0.0.0).
  address: ${CROWDPULSE_PROCESSOR_ADDRESS:127.0.0.1}
```

- **Por qué basta:** el `processor` no tiene API pública, solo `/actuator/health` y el
  endpoint de reproceso. Si no es accesible desde fuera, el endpoint tampoco.
- **Ojo con `localhost`:** en Windows, `localhost` puede resolverse primero a `::1` (IPv6),
  donde el `processor` ya no escucha. Los tests usan `127.0.0.1` explícitamente; `curl
  localhost` funciona porque prueba las dos direcciones. Se documenta en el README.
- **Test `ProcessorAddressIT`:**
  - Con la configuración por defecto, `127.0.0.1` responde y una dirección IPv4 del
    equipo que no sea de loopback (`NetworkInterface`) rechaza la conexión.
  - Con la variable a `0.0.0.0`, esa misma dirección responde.
  - Si el equipo no tiene ninguna dirección que no sea de loopback, el test se salta con
    `Assumptions` y un mensaje que lo explica. No es un `@Disabled`: depende del equipo.
- **Mutación:** quitar `server.address` → la dirección externa responde y el test falla.

*Descartado* (aprobado por el autor): Spring Security con usuario y contraseña, o una
clave en una cabecera. Añaden una dependencia o un secreto que gestionar para un uso que
hoy es solo local.

## 2. Reproceso que se salta los fallos de contenido (reglas 2–4; CA2, CA3)

### Cómo sabe qué falló
Spring Kafka guarda en cada mensaje del DLT la cabecera `kafka_dlt-exception-cause-fqcn`,
con la clase de la causa (p. ej. `tools.jackson.core.exc.UnexpectedEndOfInputException`).
Una clase pura nueva, `ContentFailure.isContentFailure(String className)`, carga esa clase
por nombre y responde `true` si es (o hereda de) `JacksonException`,
`ConstraintViolationException` o `UnsupportedMessageVersionException`: las mismas que la
spec 003 manda al DLT al primer intento, salvo `UnknownZoneException`, que sí puede
arreglarse (se crea la zona). Sin cabecera, o con una clase que no se puede cargar, el
mensaje se reprocesa: ante la duda, no se aparta nada.

*Descartado:* comparar el nombre de la clase con una lista de textos. Las excepciones de
Jackson son muchas subclases (`UnexpectedEndOfInputException`, `MismatchedInputException`…);
con `isAssignableFrom` basta nombrar la clase padre.

### Cambios en `DeadLetterReplayer`
- `replay(boolean all)`:
  - **normal:** desde la posición del grupo; los fallos de contenido se saltan y se cuentan,
    pero su offset avanza igual (regla 3);
  - **`all`:** `seekToBeginning` en todas las particiones del DLT; se reprocesa todo.
- Al terminar se confirma la posición final en los dos casos, como hasta ahora.
- Respuesta `ReplayResult(int replayed, int skipped)`; `?all=true` en el controlador.

### Tests
| Test | Qué comprueba |
|------|---------------|
| `ContentFailureTest` (unitario) | Subclases de Jackson, `ConstraintViolationException` y `UnsupportedMessageVersionException` → `true`; `UnknownZoneException`, `IllegalStateException`, `null` y una clase inexistente → `false` |
| `DeadLetterReplayIT` (ampliado) | CA2: JSON roto + zona inexistente → zona creada → `{"replayed":1,"skipped":1}`; se aplica el segundo; el DLT no recibe nada nuevo (el roto no rebotó). CA3: `?all=true` → `{"replayed":2,"skipped":0}`; el roto vuelve al DLT y la zona no cuenta dos veces |

- **Mutación:** `isContentFailure` siempre `false` → el JSON roto se reprocesa y rebota,
  y CA2 falla.

## 3. El simulador reintenta los 503 (regla 5; CA4, CA5)

- `ApiClient.send` devuelve `Response(int status, Duration retryAfter)`, leyendo
  `Retry-After` en segundos (1 s si falta o no se puede leer).
- `ZoneRunner`: si la respuesta es 503, espera `retryAfter` y reenvía **el mismo evento**,
  hasta que la respuesta no sea 503 o pasen 60 s desde el primer intento. El estado que
  cuenta es el último. El reloj y la pausa se inyectan, como en `SettlingWait`.
- `ZoneResult` añade `retries` (reenvíos) y `retriedEvents` (eventos que necesitaron
  alguno); `SimulationReport` añade la línea «Reintentos por 503: n (m eventos)».
- Orden por zona: el hilo de la zona no avanza mientras reintenta, así que los eventos
  siguientes salen después. El ritmo se recupera solo: los eventos atrasados salen enseguida
  (ya es así).
- **Tests en `ZoneRunnerTest`** (sin red, con el envío, el reloj y la pausa falsos):
  - 503, 503, 202 → un evento, dos reintentos, estado final 202.
  - 503 durante más de 60 s → fallido con 503.
  - Un 503 seguido de otros eventos → se envían en orden.
- **Mutación:** sin reintentos → el primer test falla.
- *Descartado:* reintentar también los errores de conexión (fuera de alcance en la spec).

## 4. Demo (CA5) y documentación
- Demo con compose: simulador a 100 ev/s durante 60 s; a los ~15 s, `docker compose pause
  postgres` durante 30 s → informe en OK, con reintentos > 0, y DLT sin mensajes nuevos.
- README: dirección del `processor`, `?all=true`, `skipped`, y reintentos del simulador.
- ADR 0005: una nota fechada en «Consecuencias» que diga qué riesgos cierra la spec 005.
  No hace falta un ADR nuevo: no hay ninguna decisión de arquitectura nueva.

## 5. Riesgos
1. **El cortafuegos de Windows** podría bloquear la conexión a la propia IP también con
   `0.0.0.0`, y entonces el caso «se puede abrir» de CA1 fallaría por el entorno. Si pasa,
   lo veremos en T01 y lo anotaré antes de cambiar nada.
2. **`?all=true` vuelve a meter en el DLT lo que sigue roto,** y el DLT crece. Es inofensivo,
   y es lo que pide la regla 4.
3. **Con una caída larga, el simulador pierde ritmo.** Una zona que reintenta 30 s envía
   después sus eventos atrasados de golpe. Es lo que haría un sensor real con un búfer.
