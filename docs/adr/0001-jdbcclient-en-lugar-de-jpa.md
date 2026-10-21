# ADR 0001 — `JdbcClient` con SQL explícito en lugar de JPA

- **Estado:** Aceptada
- **Fecha:** 2026-10-19 (decidida en el plan 001), redactada 2026-10-21
- **Spec:** [001 — Ingesta MVP](../../specs/001-ingesta-mvp/spec.md)

## Contexto
La API tiene que garantizar dos cosas con PostgreSQL:

1. **Idempotencia:** un `eventId` repetido no cambia nada (regla 2).
2. **Sin actualizaciones perdidas:** peticiones concurrentes sobre la misma zona no se
   pisan (regla 4, CA6).

Spring Initializr generó el proyecto con `spring-boot-starter-data-jpa`. El modelo de
datos es pequeño (tres tablas, sin relaciones que navegar) y el autor tiene que poder
explicar cada sentencia que llega a la base de datos.

## Decisión
Usar `spring-boot-starter-jdbc` y `JdbcClient` con SQL escrito a mano. Los modelos son
records inmutables. Las dos operaciones críticas se ven tal cual en el código:

- `INSERT ... ON CONFLICT (event_id) DO NOTHING` en `SensorEventRepository`: la clave
  primaria decide si es duplicado, sin carrera entre «comprobar» e «insertar».
- `SELECT ... FOR UPDATE` en `OccupancyRepository` (ver [ADR 0002](0002-bloqueo-pesimista-por-zona.md)).

## Alternativas descartadas
- **JPA / Hibernate.** Es lo más habitual en ofertas de empleo, pero las dos sentencias
  críticas son específicas de PostgreSQL y acabarían como `@Query(nativeQuery = true)`.
  Tendríamos dos modelos a la vez (entidades gestionadas y SQL nativo) y conceptos que
  aquí no aportan nada: contexto de persistencia, *dirty checking*, `open-in-view`,
  carga perezosa.
- **Spring Data JDBC.** Más ligero que JPA, pero sigue generando el SQL de los casos
  simples y obligaría a mezclar repositorios derivados con consultas a mano.

## Consecuencias
- ✅ Cada sentencia SQL está en el código y se puede leer y defender línea a línea.
- ✅ Los records encajan con «un evento nunca se modifica después de aceptarlo».
- ✅ Una dependencia menos (Hibernate) y un arranque más rápido.
- ⚠️ El mapeo de filas es manual. Ejemplo: el driver de PostgreSQL devuelve los
  `TIMESTAMPTZ` como `OffsetDateTime`, que hay que convertir a `Instant`.
- ⚠️ Si aparece un modelo con relaciones ricas, habrá que reevaluar esta decisión con un
  ADR nuevo.
