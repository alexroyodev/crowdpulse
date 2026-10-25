# Tareas 009 — Integración continua

- **Estado:** Aprobado (2026-10-25, por delegación del autor); en curso
- **Spec:** [`spec.md`](spec.md) · **Plan:** [`plan.md`](plan.md)

Una tarea cada vez. Está terminada cuando su verificación pasa y `MEMORY.md` está actualizado.
Al terminar se marca `[x]`.

---

- [ ] **T01 — Workflow y primer push** (plan §1, §2, §4; CA1, CA5, CA6, CA7)
  - `git update-index --chmod=+x mvnw`.
  - `.github/workflows/ci.yml`; insignia en el README; `CLAUDE.md`.
  - *Push* de `main`.
  - **Verificación:** la ejecución de `main` termina en verde, con los números de tests de
    local, en menos de 15 min; el fichero no usa secretos y declara `contents: read`.

- [ ] **T02 — La CI se pone en rojo** (plan §3; CA2, CA3, CA4)
  - Rama `ci-check` con un test roto en `api` (un `*IT`), otro en `processor` y otro en el
    dashboard; *pull request* hacia `main` (lo abre el autor: sin `gh`).
  - **Verificación:** los tres trabajos en rojo por el test roto, el resultado en el *pull
    request* y los informes descargables; después, cerrar el *pull request* y borrar la rama.

## Verificación de los criterios de aceptación

| CA | Cómo se verifica | Resultado |
|----|------------------|-----------|
| CA1 | Ejecución de `main` | |
| CA2 | *Pull request* de `ci-check` | |
| CA3 | Ejecución de `ci-check` | |
| CA4 | Artefactos de la ejecución de `ci-check` | |
| CA5 | Duración de la ejecución de `main` | |
| CA6 | Revisión de `ci.yml` | |
| CA7 | README | |
