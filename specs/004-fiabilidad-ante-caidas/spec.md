# Spec 004 — Fiabilidad ante caídas

- **Estado:** Borrador, con decisiones pendientes (D1–D4)
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
- Una forma de reprocesar los mensajes del DLT cuando se ha arreglado la causa (D2).

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
- **CA7** — Con PostgreSQL caído, `POST /api/v1/events` se comporta según D3 (propuesta:
  503 con `ProblemDetail`, nunca 202 sin el evento en Kafka ni un 500).
- **CA8** — `./mvnw verify` pasa en verde desde un clon limpio.

## Decisiones pendientes del autor
| Id | Pregunta | Propuesta |
|----|----------|-----------|
| D1 | ¿Cuánto espera el `processor` entre reintentos cuando la base de datos no responde, y hay un límite? | **[DECISIÓN PENDIENTE]** Espera creciente de 1 s a 30 s (1, 2, 4, 8, 16, 30, 30…) y **sin límite**: un evento correcto nunca va al DLT por una caída de la base de datos. Un `WARN` al empezar a fallar y otro cuando se recupera |
| D2 | ¿Cómo se reprocesa el DLT? | **[DECISIÓN PENDIENTE]** Opciones: (a) un endpoint del `processor`, `POST /admin/dead-letters/replay`, que vuelve a publicar en el topic principal lo que hay en el DLT; (b) comandos de Kafka documentados en el README (`kafka-console-consumer` + `kafka-console-producer`, cuidando clave y cabeceras); (c) un modo del `processor` que se lanza a mano y termina. Propuesta: **(a)**, porque se puede probar con un test de integración. Al ser solo para uso local, sin autenticación por ahora; la seguridad quedaría para cuando haya despliegue |
| D3 | ¿Qué hace la API con PostgreSQL caído? Hoy consulta el catálogo para dar el 422 (D2 de la spec 003), así que también falla | **[DECISIÓN PENDIENTE]** Propuesta: **dejarlo como está**: si la base de datos está caída, la API responde 503 (no puede saber si la zona existe). La regla 3 solo se cumple si el catálogo está disponible. La alternativa sería una caché del catálogo en la API; añade la pregunta de cuándo se invalida |
| D4 | La constitución pone «Idempotencia en el consumidor, DLT» en la fase 3, y la fase 2 ya los hizo | **[DECISIÓN PENDIENTE]** Enmendar la fila de la fase 3 a «Escenarios de caída, varias instancias, reproceso del DLT», anotándolo en el registro de enmiendas |
