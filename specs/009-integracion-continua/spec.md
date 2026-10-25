# Spec 009 — Integración continua

- **Estado:** Terminada (2026-10-25); D1–D4 con las opciones recomendadas; permiso para subir a `origin`
- **Fase:** 5
- **Fecha:** 2026-10-25
- **Depende de:** [spec 008](../008-dashboard/spec.md)

## Contexto y objetivo
Las garantías del proyecto existen porque hay un test que las demuestra (principio 2), y el
principio 7 dice que con clonar, tener Docker y ejecutar los comandos de `CLAUDE.md` basta para
pasarlos. Hoy eso solo se comprueba a mano, en el equipo del autor, cerrando cada spec con un
clon limpio. Nada impide subir un cambio que rompa un test, ni demuestra que el repositorio
funcione fuera de ese equipo (Windows, con su JDK y su Docker Desktop).

Objetivo: que cada cambio que llega a GitHub ejecute **todos** los tests en una máquina limpia
y que el resultado se vea en el propio cambio, sin pasos a mano.

## Alcance

### Incluido
- Un *workflow* de GitHub Actions en `.github/workflows/` que ejecuta:
  - `./mvnw verify` de `shared` y `api` (unitarios e integración, con Testcontainers);
  - `./mvnw verify` de `shared`, `processor` y `simulator`;
  - `npm ci`, `npm test` y `npm run build` en `dashboard/`.
- Se ejecuta en cada *push* a `main` y en cada *pull request* hacia `main` (D2).
- Si algo falla, los informes de los tests se pueden descargar desde la ejecución (D3).
- La insignia («badge») del estado en el README.

### Fuera de alcance
- Despliegue o publicación de imágenes (no hay despliegue en el proyecto).
- Proteger `main` para que no se pueda fusionar sin la CI en verde: es una opción de GitHub, no
  del repositorio; se recomienda en las notas.
- Análisis estático, cobertura mínima o escaneo de dependencias.
- Ejecutar la demo con el simulador o comprobar a mano el dashboard en el navegador: siguen
  siendo verificaciones de cierre de cada spec.
- Ejecutar los tests en Windows o macOS: el objetivo es una máquina limpia, no todas.

## Reglas
1. **La CI ejecuta lo mismo que el autor.** Los mismos comandos de `CLAUDE.md`, sin tests
   saltados ni perfiles especiales. Si un test solo pasa en el equipo del autor, es un fallo
   del test, no de la CI.
2. **Ningún test se desactiva para que la CI pase** (`CLAUDE.md`, «Nunca»). Un test inestable
   se arregla o se para y se pregunta.
3. **Sin secretos.** No hace falta ninguno: PostgreSQL y Kafka los levanta Testcontainers. Los
   permisos del *workflow*, solo de lectura del repositorio.
4. **Infraestructura real** (principio 8): los tests de integración usan el Docker del runner
   con Testcontainers, como en local; nada de servicios simulados para la CI.
5. **La CI también se demuestra** (principio 2): un cambio que rompe un test tiene que poner la
   CI en rojo, y se comprueba una vez.

## Criterios de aceptación
- **CA1** — Un *push* a `main` ejecuta el *workflow* y sus trabajos terminan en verde con los
  mismos números de tests que en local (50 en `api`; 161 en `processor` y `simulator`; 12 en
  el dashboard).
- **CA2** — Un *pull request* hacia `main` ejecuta el *workflow* y su resultado aparece en el
  propio *pull request*.
- **CA3** — En una rama de prueba, un test roto a propósito (uno de `api`, uno de `processor` y
  uno del dashboard, por separado o juntos) pone en rojo el trabajo que le toca; la rama se
  borra después (regla 5).
- **CA4** — Con un test de integración fallando, sus informes de Surefire/Failsafe se pueden
  descargar desde la ejecución.
- **CA5** — Desde que se sube un cambio hasta el resultado pasan menos de 15 minutos.
- **CA6** — El *workflow* no usa secretos y declara permisos de solo lectura.
- **CA7** — El README muestra la insignia con el estado de `main`.

## Decisiones
- **D1 — Resuelta: opción a)** (2026-10-25).
  **D1 — Cómo se reparte el trabajo.**
  - **a) (Recomendada)** Tres trabajos en paralelo (`api`, `processor` + `simulator`,
    `dashboard`), como ya se ejecuta en local por módulos. El resultado llega en lo que tarde
    el más lento (el `processor`, ~10 min), y se ve de un vistazo qué parte falla.
  - b) Un solo trabajo, todo seguido: más simple, pero más de 12 min y un fallo de la `api`
    oculta el estado del resto.
  - c) Una matriz por módulo: lo mismo que a) con una sintaxis más indirecta para tres casos.
- **D2 — Resuelta: opción a)** (2026-10-25).
  **D2 — Cuándo se ejecuta.**
  - **a) (Recomendada)** En cada *push* a `main` y en cada *pull request* hacia `main`, más un
    botón para lanzarlo a mano (`workflow_dispatch`).
  - b) En cada *push* a cualquier rama: ejecuciones duplicadas cuando la rama tiene *pull request*.
- **D3 — Resuelta: opción a)** (2026-10-25).
  **D3 — Informes de los tests.**
  - **a) (Recomendada)** Subirlos como artefacto de la ejecución **solo si falla**, y guardarlos
    unos días: es cuando hacen falta.
  - b) Siempre: ocupan espacio y nadie los mira si todo está en verde.
  - c) Nunca: con un fallo de un test de integración, el log de la consola no siempre basta.
- **D4 — Resuelta: opción a)** (2026-10-25).
  **D4 — Versiones de las acciones de GitHub** (`checkout`, `setup-java`, `setup-node`, …).
  - **a) (Recomendada)** Por versión mayor (`@v5`): se leen y se actualizan fácil; son acciones
    oficiales de GitHub.
  - b) Por *commit* exacto (SHA): más seguro frente a una etiqueta movida, pero cada línea es un
    código ilegible que habría que mantener a mano (o con Dependabot, fuera de alcance).

## Notas para el autor
- Para que la CI se ejecute hay que subir los cambios a `origin`. Los CA1–CA4 se comprueban
  allí; subir y crear la rama y el *pull request* de prueba lo pedirás tú (o lo autorizas al
  aprobar esta spec).
- Recomendado, en GitHub › Settings › Branches: exigir la CI en verde para fusionar en `main`.
- No añade dependencias del proyecto ni toca código, esquema, `compose.yaml` o contratos.
