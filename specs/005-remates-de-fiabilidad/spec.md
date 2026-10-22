# Spec 005 — Remates de fiabilidad

- **Estado:** Aprobada (2026-10-22): propuestas aceptadas por el autor
- **Fase:** 3
- **Fecha:** 2026-10-22
- **Depende de:** [spec 004](../004-fiabilidad-ante-caidas/spec.md)

## Contexto y objetivo
La spec 004 dejó tres problemas abiertos:

1. **El endpoint de reproceso del DLT no tiene autenticación** (D2 de la spec 004).
   Cualquiera que llegue al puerto del `processor` puede usarlo.
2. **Un mensaje que no tiene arreglo rebota en cada reproceso.** Un JSON roto vuelve al DLT
   cada vez que se llama al endpoint, y no hay forma de dejarlo aparte.
3. **El simulador no reintenta los 503.** D3 dice que el sensor reintenta, pero el simulador
   no lo hace: durante una caída de la base de datos su informe sale en ERROR, y no hay una
   demo de extremo a extremo de una caída con tráfico entrando.

Objetivo: cerrar la fase 3 sin esos tres huecos.

## Alcance

### Incluido
- El `processor` escucha solo en `127.0.0.1` por defecto; la dirección se puede cambiar con
  una variable de entorno.
- El reproceso se salta, por defecto, los mensajes cuyo fallo está en su contenido, y dice
  cuántos se ha saltado. Con `?all=true` reprocesa todo el DLT desde el principio.
- El simulador reintenta los 503 respetando `Retry-After`, como un sensor.

### Fuera de alcance
- Autenticación de verdad (usuarios, claves, TLS): cuando haya despliegue.
- Borrar mensajes del DLT o descartar uno concreto por offset.
- Reintentar errores de conexión en el simulador (ocultaría una API caída).

## Reglas de negocio
1. **Solo en local:** por defecto el `processor` no acepta conexiones que no vengan del
   propio equipo. No es autenticación: es no exponerse.
2. **Fallo de contenido:** JSON que no se puede leer, campos que faltan o no son válidos, o
   versión de mensaje desconocida. Ningún cambio del entorno lo arregla, así que el
   reproceso normal se los salta. Cualquier otro fallo (zona inexistente, error inesperado)
   se reprocesa.
3. **Nada se borra del DLT.** Saltarse un mensaje solo avanza la posición del grupo de
   reproceso.
4. **`?all=true` reprocesa todo el DLT desde el principio,** también lo ya reprocesado y lo
   saltado. Es inofensivo por la idempotencia por `eventId`; lo que siga sin arreglo vuelve
   al DLT.
5. **El simulador, como un sensor:** ante un 503 espera lo que diga `Retry-After` (1 s si
   no lo dice) y reintenta el mismo evento, hasta 60 s por evento. Después lo cuenta como
   fallido. Mientras reintenta, su zona no envía otros eventos: el orden por zona se mantiene.

## Contrato

`POST /admin/dead-letters/replay[?all=true]` → `200 {"replayed": n, "skipped": m}`.
`skipped` es siempre 0 con `all=true`.

Informe del simulador: una línea nueva, «Reintentos por 503: n (m eventos)».

## Criterios de aceptación
- **CA1** — Por defecto, el `processor` atiende en `127.0.0.1` y no en las demás direcciones
  del equipo. Con la variable de entorno puede escuchar en todas (`0.0.0.0`).
- **CA2** — Con un mensaje de JSON roto y otro de zona inexistente (ya creada) en el DLT, el
  reproceso devuelve `{"replayed": 1, "skipped": 1}`: se aplica el segundo y el primero
  sigue en el DLT sin haber rebotado.
- **CA3** — Con `?all=true` se reprocesan los dos, también el que antes se saltó. El de
  JSON roto vuelve al DLT y nada cuenta dos veces.
- **CA4** — El simulador reintenta un evento que recibe 503 y lo da por bueno cuando llega
  el 202; si el 503 dura más de 60 s, lo cuenta como fallido. El orden por zona se mantiene.
- **CA5** — Demo: simulador a 100 ev/s; a mitad, PostgreSQL en pausa 30 s → el informe
  termina en OK, con reintentos por 503 mayores que 0, y el DLT sin mensajes nuevos.
- **CA6** — `./mvnw verify` (por módulos) pasa en verde desde un clon limpio.
