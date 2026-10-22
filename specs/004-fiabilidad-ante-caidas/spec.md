# Spec 004 — Fiabilidad ante caídas

- **Estado:** Aprobada (2026-10-22); decisiones D1–D4 resueltas el mismo día
- **Fase:** 3
- **Fecha:** 2026-10-22
- **Depende de:** [spec 003](../003-procesamiento-asincrono/spec.md)

## Contexto y objetivo
La fase 2 dejó la cadena sensor → API → Kafka → `processor` → PostgreSQL funcionando, con
entrega *at-least-once*, idempotencia por `eventId` y un DLT. Todo eso está probado con
el sistema sano y con caídas del propio `processor`. Quedan sin probar, o mal resueltos,
los fallos de lo que hay alrededor:

- **PostgreSQL caído.** Hoy el `processor` reintenta 3 veces en 2 s y manda el evento al
  DLT. Si la base de datos tarda más en volver, eventos correctos acaban en el DLT, y la
  ocupación queda mal hasta que alguien los reprocese. Ahora mismo no hay forma de
  reprocesarlos (problema abierto de la spec 003).
- **Kafka caído mientras el `processor` consume.** Sin probar.
- **Varias instancias del `processor`.** El diseño lo permite (bloqueo por zona,
  idempotencia), pero no se ha probado un reparto de particiones a mitad de trabajo.

Objetivo: que un fallo pasajero de la infraestructura **retrase** el procesamiento, pero
no lo **estropee**. Que el DLT solo reciba mensajes que de verdad no se pueden procesar.
Y que todo eso esté demostrado con tests.

## Alcance

### Incluido
- Distinguir dos tipos de fallo en el `processor`:
  - **fallo de infraestructura** (la base de datos no responde): se espera y se reintenta
    sin límite;
  - **mensaje malo**: va al DLT como en la spec 003.
- PostgreSQL caído durante el consumo: no se pierde nada, no se cuenta nada dos veces, no
  se desordena nada y no va nada al DLT.
- Kafka caído durante el consumo: al volver, el `processor` sigue sin pérdidas ni dobles.
- Dos instancias del `processor` a la vez, y una cae a mitad: la otra se queda con sus
  particiones y el resultado es exacto.
- Reprocesar los mensajes del DLT cuando se ha arreglado la causa, con un endpoint del
  `processor` (D2).
- La API responde 503, no 500, cuando no puede consultar el catálogo de zonas (D3).

### Fuera de alcance
- *Exactly-once*, transacciones de Kafka.
- Varias réplicas de Kafka o de PostgreSQL (alta disponibilidad de la infraestructura).
- Métricas, alertas sobre el DLT, trazas y dashboard (fase 4).
- Kubernetes.

## Reglas de negocio
1. **Un fallo de infraestructura no es culpa del mensaje.** Mientras la base de datos no
   responda, el `processor` reintenta el mismo mensaje, con una espera que crece hasta un
   máximo (D1). No pasa al siguiente mensaje de esa partición, ni lo manda al DLT.
2. **Orden por zona también durante una caída:** ningún evento de una zona se aplica
   antes que otro anterior de la misma zona que esté esperando a la base de datos.
3. **Mientras el `processor` espera, Kafka guarda los eventos pendientes:** no se pierden
   aunque la espera sea larga. Lo que responde la API con la base de datos caída lo decide D3.
4. **Reparto de particiones:** cada partición la consume una sola instancia a la vez. Si
   durante un reparto dos instancias llegan a procesar el mismo mensaje, el bloqueo por
   zona y el `eventId` impiden que cuente dos veces.
5. **El DLT conserva el mensaje original** (clave, valor y cabeceras). Reprocesarlo es
   idempotente: si ya se había aplicado, no cambia nada.
6. **Un mensaje malo sigue yendo al DLT al primer intento,** como en la spec 003.

## Criterios de aceptación
- **CA1** — Hay 1 000 eventos en Kafka y PostgreSQL se para unos 30 s mientras el
  `processor` los consume:
  - cuando vuelve la base de datos, la ocupación es la correcta;
  - no ha ido nada al DLT;
  - no hay anomalías por desorden.
- **CA2** — Mientras la base de datos no responde, el `processor` registra el problema
  (`WARN`, sin un log por cada reintento) y `/actuator/health` del `processor` dice `DOWN`.
- **CA3** — Si se reinicia Kafka mientras el `processor` consume, al volver se procesan
  todos los eventos aceptados, cada uno una vez.
- **CA4** — Con dos instancias del `processor`, si una se para a mitad de 1 000 eventos,
  la otra termina el trabajo: ocupación exacta y sin anomalías.
- **CA5** — Un mensaje del DLT se puede reprocesar cuando se ha arreglado la causa. Si ya
  se había aplicado, no cuenta dos veces.
- **CA6** — Regresión de la spec 003: JSON roto, campo que falta, versión desconocida y
  zona inexistente siguen yendo al DLT al primer intento.
- **CA7** — Con PostgreSQL caído, `POST /api/v1/events` responde 503 con `ProblemDetail`
  (D3): nunca 202 y nunca un 500. Hoy responde 500, porque el error de la base de datos
  llega al manejador genérico.
- **CA8** — `./mvnw verify` pasa en verde desde un clon limpio.

## Decisiones del autor (2026-10-22)
| Id | Decisión | Elegida (propuesta aceptada) |
|----|----------|------------------------------|
| D1 | Reintentos con la base de datos caída | Espera creciente de 1 s a 30 s (1, 2, 4, 8, 16, 30, 30…), **sin límite**: un evento correcto nunca va al DLT por una caída de la base de datos. Un `WARN` al empezar a fallar y otro al recuperarse |
| D2 | Reproceso del DLT | Endpoint del `processor`, `POST /admin/dead-letters/replay`, que vuelve a publicar en el topic principal lo que hay en el DLT. Sin autenticación por ahora (uso local); la seguridad, cuando haya despliegue |
| D3 | La API con PostgreSQL caído | Como está: sin catálogo no sabe si la zona existe, así que responde **503** con `ProblemDetail` y el sensor reintenta. Sin caché del catálogo |
| D4 | Fila de la fase 3 en la constitución | Enmendada a «Escenarios de caída, varias instancias, reproceso del DLT» (registro de enmiendas, 2026-10-22) |
