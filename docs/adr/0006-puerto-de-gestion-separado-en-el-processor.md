# ADR 0006 — Puerto de gestión separado en el `processor`

- **Estado:** Aceptada
- **Fecha:** 2026-10-25
- **Spec:** [006 — Métricas](../../specs/006-metricas/spec.md) (D1)

## Contexto
La spec 005 hizo que el `processor` escuchara solo en `127.0.0.1`, para que nadie de fuera
del equipo pudiera usar el endpoint de reproceso del DLT, que no tiene autenticación.

La spec 006 añade Prometheus, que corre en Docker y lee `/actuator/prometheus` de cada
servicio. En Docker Desktop, una conexión desde un contenedor al equipo llega por la red
virtual de Docker, no por `127.0.0.1`, así que Prometheus no vería el `processor`.

## Decisión
Actuator tiene su propio servidor en el `processor`:

```yaml
management:
  server:
    port: 8082
    address: ${CROWDPULSE_MANAGEMENT_ADDRESS:0.0.0.0}
```

- Con `management.server.port` distinto de `server.port`, Spring Boot arranca un segundo
  servidor que **solo** sirve los endpoints de actuator expuestos (`health` y `prometheus`).
- El endpoint de reproceso sigue en `127.0.0.1:8081`: no existe en el 8082 (404).
- Lo que queda abierto es de solo lectura: recuentos y estado, sin datos personales ni
  secretos. Matiza la regla 1 de la spec 005 («no exponerse»): no se expone nada que cambie
  el sistema.

## Alternativas descartadas
- **Prometheus fuera de Docker:** cada persona tendría que instalarlo; rompe el principio 7
  (clonar, `docker compose up` y listo).
- **Red `host` en Docker Desktop:** experimental y depende de la versión instalada.
- **Abrir todo el `processor` (`CROWDPULSE_PROCESSOR_ADDRESS=0.0.0.0`):** expondría también
  el reproceso, que es justo lo que la spec 005 quería evitar.

## Consecuencias
- El health del `processor` pasa al 8082 (`curl 127.0.0.1:8082/actuator/health`).
- Al escuchar en todas las direcciones, el firewall de Windows puede preguntar la primera vez.
- `ProcessorAddressIT` lo comprueba: el 8082 responde en la IP del equipo, el puerto principal
  no, y el reproceso da 404 en el 8082. Validado con una mutación (`address: 127.0.0.1` en el
  servidor de gestión → el test falla).
- Cuando haya despliegue y autenticación real, se revisará qué queda abierto.
