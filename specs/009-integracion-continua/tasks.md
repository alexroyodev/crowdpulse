# Tareas 009 — Integración continua

- **Estado:** Terminado (2026-10-25)
- **Spec:** [`spec.md`](spec.md) · **Plan:** [`plan.md`](plan.md)

Una tarea cada vez. Está terminada cuando su verificación pasa y `MEMORY.md` está actualizado.
Al terminar se marca `[x]`.

---

- [x] **T01 — Workflow y primer push** (plan §1, §2, §4; CA1, CA5, CA6, CA7)
  - `git update-index --chmod=+x mvnw`.
  - `.github/workflows/ci.yml`; insignia en el README; `CLAUDE.md`.
  - *Push* de `main`.
  - **Verificación:** la ejecución de `main` termina en verde, con los números de tests de
    local, en menos de 15 min; el fichero no usa secretos y declara `contents: read`.
  - Hecho así (2026-10-25): `mvnw` estaba como `100644` en Git (se trabaja en Windows); ahora
    `100755`. Primera ejecución de `main` (`9ae2242`,
    [run 40205429555](https://github.com/alexroyodev/crowdpulse/actions/runs/40205429555)) en
    verde: `api` 2 min 9 s, `processor` 5 min 11 s, `dashboard` 21 s; 5 min 15 s en total (en local,
    los dos `verify` seguidos pasan de 12 min). Los logs de GitHub piden sesión incluso en un
    repositorio público, así que los números de tests no se pudieron leer por la API: los ve el
    autor en la ejecución.

- [x] **T02 — La CI se pone en rojo** (plan §3; CA2, CA3, CA4)
  - Rama `ci-check` con un test roto en `api` (un `*IT`), otro en `processor` y otro en el
    dashboard; *pull request* hacia `main` (lo abre el autor: sin `gh`).
  - **Verificación:** los tres trabajos en rojo por el test roto, el resultado en el *pull
    request* y los informes descargables; después, cerrar el *pull request* y borrar la rama.
  - Hecho así (2026-10-25): [*pull request* #1](https://github.com/alexroyodev/crowdpulse/pull/1),
    abierto por el autor. **Primer intento inválido, error mío:** al romper los tests con `sed`, el
    `;` de las dos líneas Java quedó dentro del comentario y los trabajos de Maven fallaron en 18 s
    por compilación, no por el test (el del dashboard sí era bueno). Corregido en la rama y
    comprobado con `test-compile` antes de subir. Segunda ejecución
    ([run 40206180806](https://github.com/alexroyodev/crowdpulse/actions/runs/40206180806)), los
    tres en rojo por el motivo correcto: `dashboard` en `npm test` (anotación: «expected '20.000'
    to be '20 000'», `format.test.ts:14`); `processor` en 21 s (falla `OccupancyTest`, unitario,
    antes de los `*IT`); `api` en 2 min (llega a Failsafe: `EventPublishingIT`). Artefactos
    `api-test-reports` (162 KB) y `processor-test-reports` (41 KB). Rama borrada; el *pull
    request* quedó cerrado sin fusionar. Aviso de GitHub: `ubuntu-latest` pasa a Ubuntu 26 desde
    el 19-10-2026; no cambia nada aquí (Docker y Java los pone el runner y `setup-java`).

## Verificación de los criterios de aceptación

| CA | Cómo se verifica | Resultado |
|----|------------------|-----------|
| CA1 | [Run 40205429555](https://github.com/alexroyodev/crowdpulse/actions/runs/40205429555) de `main`, en verde. Los números de tests están en los logs, que piden sesión: los confirma el autor | ✅ (números pendientes de mirar)
| CA2 | [*Pull request* #1](https://github.com/alexroyodev/crowdpulse/pull/1): el resultado aparece en él | ✅
| CA3 | Run 40206180806: los tres trabajos en rojo por su test roto | ✅
| CA4 | Artefactos `api-test-reports` y `processor-test-reports` en la ejecución fallida | ✅ (descarga: pide sesión)
| CA5 | 5 min 15 s de *push* a resultado | ✅
| CA6 | `ci.yml`: sin secretos, `permissions: contents: read` | ✅
| CA7 | Insignia al principio del README | ✅
