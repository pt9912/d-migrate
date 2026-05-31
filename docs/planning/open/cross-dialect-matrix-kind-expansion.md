# Cross-Dialect-Matrix Kind-Erweiterung (Folge-Slice zu Phase B)

- **Status**: Draft (Trigger registriert, kein Scope-Schnitt)
- **Trigger**: Post-Closure-Review des
  [`quality-coverage-expansion-plan`](../done/quality-coverage-expansion-plan.md)
  am 2026-05-31 hat festgestellt, dass die Matrix-`Kind`-Aufzaehlung
  nur zwei der fuenf vom Plan-Text genannten Test-Arten implementiert.
- **Aktivierungsbedingung**: Wenn die Matrix als „voll" gegen Plan-
  Wortlaut auditiert werden soll, oder wenn ein Regression-Sweep
  ueber Rollback-/Report-Pfade benoetigt wird (z. B. fuer
  Compliance-Reviews oder das Hochstufen zum 1.0.0-Gate).

## Befund-Snapshot (2026-05-31)

- Plan-Text in
  [`docs/planning/done/quality-coverage-expansion-plan.md:115`](../done/quality-coverage-expansion-plan.md)
  und §5.2 listet fuenf Test-Arten als Matrix-Achse: **Positiv,
  Blocker, Report, Rollback, File-Mode**.
- `test/cross-dialect-matrix/src/test/kotlin/dev/dmigrate/test/matrix/MatrixCell.kt:40`
  definiert `enum class Kind` mit nur zwei Werten: `POSITIVE`
  („positive") und `BLOCKER` („blocker"). Report/Rollback/File-Mode
  fehlen.
- `MatrixSweepRunner.kt:123` laeuft per `planOnly = true` — der
  Sweep prueft also Plan-Output und Exit-Code, keine Rollback-
  Artefakte und keine Report-Inhalte.

## Skizzierte Arbeit

- Entscheidung pro fehlender Kind:
  - **REPORT**: Sweep prueft eine Report-Property
    (`primaryBlockedReason`, `executionMeta.statementCount`, …)
    pro Workstream × Dialekt. Kandidat: Per-Cell-Pinning auf einen
    Report-Wert ergaenzend zum Exit-Code.
  - **ROLLBACK**: Sweep aktiviert `generateRollback=true` und pinnt
    das Rollback-Artefakt — entweder Hash oder Statement-Count.
  - **FILE_MODE**: Heutiger File-mode-Standardlauf ist im Sweep
    implizit; ein expliziter Kind macht den Carve-out gegenueber
    einem zukuenftigen DB-Execute-Sweep klar (Erinnerung: §5.0 hat
    File-Mode als Default-Smoke und DB-Execute als Folge-Slice).
- `MatrixCell.Kind` um die drei Werte ergaenzen, `CarveOutRegistry`
  und `fixtures/carve-outs.yaml` pro neuer Kind pinnen oder
  permanent carven.
- `MatrixSweepRunner` differenziert `planOnly` nach Kind: REPORT/
  ROLLBACK liefern echte Artefakte, POSITIVE/BLOCKER bleiben
  plan-only.

## Nicht-Ziel

- Kein DB-Execute-Sweep (eigener Folge-Slice).
- Keine Aenderung an Workstream-Pinning-Liste — die heutigen
  7 gepinnten + 17 permanenten Workstreams bleiben Quelle der
  Wahrheit.
