# Oracle-Leg im Sample-DB-Harness (Slice 3b)

> **Status:** Draft mit Scope (2026-09-06).
> **Trigger:** Oracle Slice 3 (`data export`/`import`/`transfer`, ADR 0052)
> hat den Datenpfad geliefert, aber die im Slice-Schnitt vorgesehene
> **3b** (sample-db-Oracle-Leg im Harness) bewusst nicht mitgezogen —
> Eigner-Entscheidung, als eigener Folge-Schnitt statt in Slice 3
> eingebettet (anders als beim MSSQL-Vorbild, das 3+3b gemeinsam
> geliefert hat, `docs/planning/done/mssql-dialect-scoping.md` Zeile 218).

## Ist-Zustand

`examples/sample-db/` ist der Cross-Dialekt-Round-Trip-Harness (ADR 0013
Sourcing, ADR 0014 Fetch-and-Compose). Jeder registrierte Dialekt außer
Oracle hat darin ein "Leg": ein `docker-compose.yml`-Service, `.env`-Variablen
(Port, Credentials), ein Paar `smoke-cross-<dialekt>2pg.sh`/
`smoke-cross-pg2<dialekt>.sh`-Skripte und eine gepinnte Notes-/Zeilenzahl-
Baseline unter `expected/`. Das MSSQL-Leg
(`examples/sample-db/scripts/smoke-cross-pg2ms.sh`,
`examples/sample-db/scripts/smoke-cross-ms2pg.sh`) ist das jüngste, direkt vergleichbare
Vorbild: Pagila PG laden → `schema reverse` → `schema generate --target mssql
--split pre-post` → Pre-Data per `sqlcmd` anwenden → `data transfer --verify`
→ Zeilen-Paritäts- und Typ-Stichproben gegen eine gepinnte Baseline.

Oracle hat weder einen `docker-compose.yml`-Service noch `.env`-Variablen
noch Smoke-Skripte in diesem Harness. Der reale Oracle-Testcontainer-Einsatz
in diesem Projekt beschränkt sich bisher auf `test/integration-oracle`
(isolierte, kleine JDBC-Fixtures) und die neuen `test/e2e-cli`-Tests
(`OracleSchemaGenerateE2ETest`, `OracleTransferE2ETest`) — beide bauen ihre
eigenen, kleinen Testcontainer direkt im Testcode auf, nicht über das
Sample-DB-Harness mit dem echten Pagila-Datensatz.

## Ziel

Ein Oracle-Leg im Sample-DB-Harness, analog zum MSSQL-Leg: Pagila-Cross-
Dialect-Smoke PostgreSQL → Oracle (und optional zurück, falls Slice 5
`schema reverse` gegen das erzeugte Oracle-Schema dafür reif genug ist —
siehe Nicht-Scope) mit gepinnter Notes-/Zeilenzahl-Baseline, als CI-fähiger
Smoke-Test (oder zumindest manuell wiederholbar analog Phase 2 der
bestehenden Harness-Phasen).

## Scope-Skizze

1. **P0 — `docker-compose.yml`-Service.** `oracle`-Service analog zum
   `mssql`-Block (Zeile ~110-134): Digest-gepinntes `gvenzl/oracle-free`-Image
   (wie in `test/integration-oracle`/`test/e2e-cli` bereits verwendet,
   `23-slim-faststart`), Port-Mapping über eine neue `SAMPLE_DB_ORACLE_PORT`-
   `.env`-Variable, Healthcheck (z. B. `sqlplus`- oder JDBC-basierter Connect-
   Test — prüfen, ob das Image ein CLI-Tool für einen einfachen Healthcheck
   mitbringt, sonst auf einen simplen TCP-Check ausweichen).
2. **P1 — `.env.example`-Ergänzung.** `SAMPLE_DB_ORACLE_PORT` + ggf.
   Credential-Variablen (Oracle-Testcontainer-Defaults `system`/generiertes
   Passwort — prüfen, ob `.env`-Overrides überhaupt nötig sind oder das
   Test-Image feste Test-Credentials mitbringt wie bei PG/MySQL).
3. **P2 — Smoke-Skripte.** `smoke-cross-pg2ora.sh` (Pagila PG → Oracle:
   reverse → `generate --target oracle` → Anwenden — **kein `sqlcmd`-Äquivalent
   nötig**, Slice-3-Erkenntnis aus `OracleTransferE2ETest` nutzen (JDBC-
   `Statement.execute()` pro Anweisung nach Entfernen der `/`-Batch-Trenner)
   → `data transfer --verify` → Zeilen-Parität + Typ-Stichproben). Optional
   `smoke-cross-ora2pg.sh` (umgekehrte Richtung) — nur wenn Oracle
   `schema reverse` (bereits aus Slice 1 vorhanden) für das komplexere
   Pagila-Schema ausreicht; sonst als eigener Nicht-Scope-Punkt vermerken.
4. **P3 — Gepinnte Baseline.** `pagila-cross-oracle.notes.txt` (unter
   `expected/`, analog `pagila-cross-ms.notes.txt`) + eine `EXPECTED_VERIFY_EXCLUSIONS`-
   Konstante fürs `--verify`-Skript, aus einem echten Erstlauf abgeleitet,
   nicht geraten.
5. **P4 — README/Doku.** `examples/sample-db/README.md`-Phasentabelle um die
   Oracle-Zeile ergänzen (Phase 2, analog MSSQL); `docs/planning/in-progress/
   oracle-dialect-scoping.md` Slice-3b-Zeile auf ✅ setzen, wenn geliefert.
6. **P5 — CI-Einbindung.** Prüfen, ob/wie der bestehende Sample-DB-Smoke-
   Workflow (welcher Workflow das MSSQL-Leg fährt) den Oracle-Container-
   Ressourcenbedarf (RAM/Startzeit, laut `OracleContainerConnectIntegrationTest`
   ~2-3 Min Kaltstart) verkraftet, oder ob ein separates, opt-in/nightly
   Gate wie bei Phase 3 (Employees-Scale) sinnvoller ist.

## Akzeptanzkriterien

- `make sample-db-smoke-cross-oracle` (oder analog benanntes Target) baut
  Pagila in PostgreSQL, transferiert per `data transfer --verify` nach
  Oracle, und die Zeilen-/Typ-Stichproben stimmen gegen eine gepinnte
  Baseline überein.
- Notes-Baseline ist aus einem echten Lauf abgeleitet (keine geratenen
  E-/W-Codes), analog `pagila-cross-ms.notes.txt`.
- `docker-compose.yml`/`.env.example` bleiben für alle bestehenden Legs
  unverändert nutzbar (rein additiv).

## Nicht-Scope

- Oracle→PostgreSQL-Rückrichtung (`smoke-cross-ora2pg.sh`), falls die
  Komplexität des reversed Oracle-Schemas (Partitionierung E055,
  Volltext/Spatial nicht gescoped) die Baseline unverhältnismäßig
  aufwendig macht — dann als eigener Trigger-Eintrag in `open/` vermerken.
- Employees-Scale- oder Spatial-Phasen für Oracle (Phase 3/Spatial-Analogon)
  — Oracle Spatial ist laut ADR 0052 gar nicht gescoped.
- CI-Standard-Gate-Aufnahme, falls der Ressourcenbedarf das PR-Gate zu sehr
  verlangsamt — dann opt-in/nightly wie Phase 3.
