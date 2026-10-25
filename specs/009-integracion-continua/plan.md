# Plan 009 — Integración continua

- **Estado:** Aprobado (2026-10-25, por delegación del autor); implementado (detalles en `tasks.md`)
- **Spec:** [`spec.md`](spec.md)
- **Fase:** 5
- **Fecha:** 2026-10-25

**Qué se añade:** `.github/workflows/ci.yml`, el bit de ejecución de `mvnw` en Git y la
insignia en el README. Sin dependencias nuevas ni cambios de código.

## 1. Un arreglo previo: `mvnw` sin permiso de ejecución
En Git, `mvnw` está como `100644` (el repositorio se ha trabajado en Windows, que no tiene ese
bit): en Linux, `./mvnw` daría «Permission denied». Se corrige con
`git update-index --chmod=+x mvnw`. *Descartado:* llamar a `sh mvnw` en la CI: los comandos
tienen que ser los de `CLAUDE.md` (regla 1), y cualquiera que clone en Linux tendría el problema.

## 2. El *workflow* (D1–D4)

```yaml
name: CI
on:
  push: { branches: [main] }
  pull_request: { branches: [main] }
  workflow_dispatch:
permissions:
  contents: read
concurrency:            # a newer push to the same branch cancels the older run
  group: ci-${{ github.ref }}
  cancel-in-progress: true
jobs:
  api:        checkout, setup-java (temurin 21, cache maven), ./mvnw -B verify -pl shared,api
  processor:  lo mismo con -pl shared,processor,simulator
  dashboard:  checkout, setup-node (24, cache npm), npm ci, npm test, npm run build (en dashboard/)
```

- **Runner `ubuntu-latest`:** trae Docker, que es lo que Testcontainers necesita; no hace falta
  ningún `services:` (regla 4).
- **JDK 21 Temurin** con la caché de Maven de `setup-java` (clave: los `pom.xml`). **Node 24**,
  el del autor (`engines` pide ≥ 22.12), con la caché de npm (clave: `package-lock.json`).
- **`-B`** (modo no interactivo de Maven): log sin barras de progreso. No cambia qué se ejecuta.
- **`concurrency`:** con dos *push* seguidos, la ejecución vieja se cancela; no se gastan 10 min
  comprobando un estado que ya no existe.
- **`timeout-minutes: 30`** por trabajo: un test colgado no ocupa el runner 6 h (el máximo).
- **Informes (D3):** `actions/upload-artifact` con `if: failure()` de
  `**/target/surefire-reports` y `**/target/failsafe-reports`, 7 días.
- **Versiones (D4):** `actions/checkout@v7`, `actions/setup-java@v6`, `actions/setup-node@v7`,
  `actions/upload-artifact@v7` (las mayores actuales, 2026-10-25).

## 3. Comprobación (CA1–CA6)
- **CA1/CA5:** *push* de `main` y lectura del resultado con la API pública de GitHub
  (`/repos/alexroyodev/crowdpulse/actions/runs`): el repositorio es público y no hace falta `gh`.
- **CA2/CA3/CA4:** rama `ci-check` con tres tests rotos (uno por trabajo; uno de ellos un `*IT`
  para tener informes de Failsafe). Sin `gh`, el *pull request* lo abre el autor desde el enlace
  de GitHub; después se cierra y la rama se borra (`git push origin --delete ci-check`).
- **CA6:** revisión del fichero.

## 4. Documentación
- README: insignia de la CI y una línea en «Cómo se trabaja».
- `CLAUDE.md`: la CI en la estructura (`.github/workflows/ci.yml`).

## Orden para `tasks.md`
1. `mvnw` ejecutable, *workflow*, insignia; *push* de `main` y CA1/CA5/CA6.
2. Rama de prueba con tests rotos; CA2–CA4; borrar la rama; cierre.
