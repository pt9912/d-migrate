---
status: accepted
date: 2026-06-18
supersedes: ADR-0013
decision-makers: pt9912
consulted: docs/planning/next/sample-db-integration-harness.md (Phase 0), examples/bi-demo/ (Präzedenz), docs/planning/done-archive/pilot-validation-0.9.9.md (Pilot-Mechanik)
informed: CI (Integration Tests / nightly), examples/, .dockerignore, Repo-Footprint-Pflege
---

# Sample-DB-Harness: On-Demand-Fetch + docker-compose/Scripts (supersedet ADR 0013)

## Kontext und Problemstellung

[ADR 0013](0013-sample-db-sourcing.md) entschied, Pagila/Sakila-Dumps zu
**vendoren** (für ein offline-deterministisches PR-Gate). Bei der Slice-
Vorbereitung kamen zwei bessere Optionen auf, die die Kerngründe von 0013 drehen:

1. **Sourcing:** Ein **On-Demand-Fetch-Script** holt die (gepinnten) Dumps in
   einen `.gitignore`-ten `.cache/`-Ordner — statt sie einzuchecken. → Footprint
   null, keine Redistribution (leichtere Lizenz-Position, v. a. Employees CC-BY-SA).
2. **Harness-Mechanik:** Statt eines Testcontainers-Gradle-Moduls ein
   **docker-compose + bash-Script-Harness mit dem echten CLI-Image** — exakt das
   Muster von [bi-demo](../../examples/bi-demo/README.md) (compose +
   `examples/bi-demo/scripts/smoke.sh` + CI-Workflow). Das **läuft lokal *und* in CI**, testet das
   echte CLI end-to-end (näher am Pilot), und hat **nicht** das
   Testcontainers-im-`docker build`-Problem (kein verschachteltes Docker).

Der „Offline-PR-Gate"-Einwand von 0013 wiegt kaum, weil der Sample-DB-Harness
ohnehin **nicht** im Offline-Default-Build läuft (gegated), sondern in einem
eigenen Workflow mit Netz — analog `bi-demo-smoke.yml`.

## Entscheidung

1. **Sourcing — On-Demand-Fetch statt Vendoren.** Ein Script lädt die Dumps bei
   Bedarf in `examples/sample-db/.cache/` (gitignored **und** in `.dockerignore`). <!-- d-check:ignore (geplanter Harness-Pfad, existiert noch nicht; ADR 0011) -->
   Quelle **gepinnt** (Commit-SHA/Release-Tag, nicht `main`) und per **SHA256
   verifiziert**. Kein Dump im Repo — uniform für Pagila/Sakila/Employees.
2. **Mechanik — docker-compose + Scripts, kein Testcontainers.** Harness analog
   `examples/bi-demo/`: `docker-compose.yml` (postgres + mysql), Fetch-/Smoke-/
   Compatibility-Scripts, gegen das lokal gebaute `d-migrate:dev`-CLI-Image.
3. **Platzierung — `examples/sample-db/`** (Geschwister von `bi-demo`), **kein** <!-- d-check:ignore (geplanter Harness-Pfad, existiert noch nicht; ADR 0011) -->
   **neuer Root**. `test/` bleibt Gradle-Modulen vorbehalten.
4. **Ausführung — lokal *und* CI.** `make sample-db-smoke`(+`-compatibility`)
   lokal; CI-Workflow analog `bi-demo-smoke.yml`. Scale (Employees) opt-in/nightly.

## Konsequenzen

- **Footprint null** (kein Dump in der git-Historie), **leichtere Lizenz**
  (keine Redistribution).
- **Netzabhängigkeit** beim Fetch (Cache-Miss) — akzeptabel, da nicht im
  Offline-Default-Build; GitHub-Actions-Cache gekeyt auf den Pin reduziert
  Downloads auf seltene Cache-Misses.
- **Lokal lauffähig** → die Expected-Result-Baseline kann **lokal** gepinnt
  werden; der mehrrundige Push→CI→Baseline-Zyklus entfällt.
- **Echtes CLI-E2E** statt Library-In-Process (näher an Pilot/Nutzer); die
  feinkörnige Modell-Ebene bleibt durch die bestehenden `integration-*`-Gradle-
  Module (synthetische Schemata) abgedeckt.
- `examples/` trägt damit (wie schon bei bi-demo) sowohl Demo- als auch
  Smoke-/Validierungs-Harnesse.

## Abgrenzung

- Regelt Sourcing **und** Harness-Mechanik/Platzierung; der konkrete Phasen-/
  Baseline-Aufbau steht im Slice-Plan.
- Performance/TPC (LF 8.1/8.2) bleibt ein eigener 1.0.0-QA-Slice.
