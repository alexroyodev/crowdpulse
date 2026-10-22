# Constitución de CrowdPulse

Principios que no cambian de una spec a otra. Si una spec o un plan los contradice,
gana la constitución, salvo que se enmiende de forma explícita (ver "Enmiendas").

## Propósito
CrowdPulse estima en tiempo real la ocupación por zonas de un estadio a partir de
eventos de sensores y avisa cuando una zona se acerca a su capacidad. Es un proyecto
de aprendizaje y portfolio: su valor está en que su autor pueda explicar y demostrar
cómo se comporta ante duplicados, desorden, picos de carga y caídas.

## Principios

### 1. Comprensible antes que ingenioso
Cada línea debe poder explicarla el autor en una entrevista. Si una solución necesita
un párrafo para justificarse, se busca una más simple o se documenta en un ADR.
Ninguna abstracción "por si acaso": se introduce cuando hay un segundo caso real.

### 2. Toda garantía se demuestra con un test
"No se pierden eventos", "los duplicados no cuentan" o "el orden se respeta por zona"
son afirmaciones que solo existen si un test automatizado las comprueba. Usar Kafka,
transacciones o reintentos no es una garantía por sí mismo.
Un test de garantía se valida una vez quitando el mecanismo que la da (el bloqueo, la
transacción, la restricción...) y comprobando que falla; si sigue en verde, no prueba nada.

### 3. Avanzar por fases, siempre con algo que funciona
Cada fase termina con un sistema ejecutable y una demo reproducible. No se introduce
tecnología de una fase posterior antes de tiempo. Un proyecto pequeño terminado vale
más que uno grande a medias.

| Fase | Objetivo | Se añade |
|------|----------|----------|
| 1 | MVP síncrono: simulador → API → PostgreSQL, con alertas por umbrales | Spring Boot, Flyway, Docker Compose |
| 2 | Procesamiento asíncrono | Kafka, servicio `processor`, DLT básico |
| 3 | Fiabilidad | Escenarios de caída, varias instancias, reproceso del DLT |
| 4 | Observabilidad y presentación | Métricas, trazas, dashboard, documentación |

### 4. El tiempo del evento manda
Se distingue siempre cuándo ocurrió algo (`occurredAt`) de cuándo lo vio el sistema
(`receivedAt`). La lógica de negocio usa el primero; la latencia se mide con la
diferencia. Todos los instantes se guardan en UTC.

### 5. Contratos explícitos y estables
El formato de los eventos y de la API está escrito en la spec antes de implementarse.
Los cambios incompatibles se versionan; los productores existentes no se rompen.

### 6. La base de datos es la fuente de verdad del estado
El estado consultable (ocupación por zona) vive en PostgreSQL. Ningún dato de negocio
se guarda solo en memoria. El esquema cambia únicamente mediante migraciones Flyway.

### 7. Entorno reproducible
Clonar el repositorio, tener Docker y ejecutar los comandos de `CLAUDE.md` debe bastar
para levantar el sistema y pasar todos los tests. Nada depende de configuración local
no documentada.

### 8. Tests contra infraestructura real
Las pruebas de integración usan PostgreSQL (y Kafka) reales vía Testcontainers. La
lógica de dominio se prueba sin Spring, con `Clock` inyectado.

### 9. Diseñado para observarse
Desde la fase 1, cada servicio expone salud y registra (en logs estructurados) los
eventos rechazados y las anomalías con su `eventId`. En la fase 4 esto se convierte
en métricas y trazas, no se reinventa.

## Proceso (SDD)
1. **Spec** (`specs/NNN-nombre/spec.md`): qué y por qué, alcance, criterios de
   aceptación verificables. Sin decisiones técnicas salvo contratos.
2. **Plan** (`plan.md`): cómo, decisiones técnicas y sus alternativas descartadas.
3. **Tareas** (`tasks.md`): pasos pequeños, cada uno con su verificación.
4. **Implementación**: una tarea cada vez, tests en verde al terminar cada una.

El autor aprueba cada documento antes de pasar al siguiente. Las decisiones pendientes
se marcan como `[DECISIÓN PENDIENTE]` y bloquean la implementación de lo que afectan.

## Enmiendas
Esta constitución se cambia solo por decisión explícita del autor, anotando la fecha y
el motivo en el registro de abajo. Las decisiones técnicas concretas van en ADRs
(`docs/adr/`), no aquí.

### Registro
- 2026-10-19 — Versión inicial.
- 2026-10-21 — Principio 2: los tests de garantía se validan quitando el mecanismo y
  viéndolos fallar. Motivo: en la spec 001 así se comprobó que `ConcurrentIngestionIT`
  y `TransactionAtomicityIT` detectan de verdad el fallo que vigilan.
- 2026-10-21 — Fase 1 incluye las alertas por umbrales (spec 002). Motivo: no añaden
  tecnología nueva; la tabla de fases no las situaba en ninguna.
- 2026-10-21 — Fase 2 incluye un DLT básico (spec 003). Motivo: `CLAUDE.md` exige que
  ningún mensaje se descarte; los escenarios de caída siguen en la fase 3.
- 2026-10-22 — Fase 3 pasa a «Escenarios de caída, varias instancias, reproceso del DLT»
  (spec 004, D4). Motivo: la fase 2 ya hizo la idempotencia en el consumidor y el DLT.
