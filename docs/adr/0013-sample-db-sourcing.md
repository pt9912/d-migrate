---
status: accepted
date: 2026-06-18
decision-makers: pt9912
consulted: docs/planning/next/sample-db-integration-harness.md (Phase 0), docs/planning/open/test-database-candidates.md
informed: CI (Integration Tests / nightly), Test-Infrastruktur, Repo-Footprint-Pflege
---

# Sample-DB-Sourcing: Pagila/Sakila vendoren, Employees on-demand — Quellen gepinnt

## Kontext und Problemstellung

Der geplante Sample-DB-Integrationstest-Harness
([`sample-db-integration-harness.md`](../planning/next/sample-db-integration-harness.md))
lädt externe Beispieldatenbanken (Pagila, Sakila, Employees) in Container und
fährt reverse → generate → transfer → compare. Die Dumps sind **externe
Artefakte**; wie sie in die CI kommen, hat echte Trade-offs:

| Option | Reproduzierbar/offline | Footprint | Lizenz | CI-Determinismus |
| ------ | ---------------------- | --------- | ------ | ---------------- |
| **A — vendoren** (Test-Ressource, Testcontainers `withInitScript`) | ✅ | ⚠️ Repo wächst | Dump + Lizenz mitcheckbar | ✅ |
| **B — zur Testzeit downloaden** | ❌ (Netz nötig) | ✅ keiner | nur Verweis | ❌ flaky / offline-Build bricht |
| **C — Hybrid** | teils | gemischt | gemischt | teils |

Zwei Randbedingungen prägen die Wahl:

- Das Repo verfolgt eine **CI-Determinismus-Linie** (z. B. `outputs.cacheIf { false }`
  für Kover, reproduzierbare Docker-Stages); ein Netz-Download im PR-Gate
  widerspricht dem (Flakiness, offline-Build bricht).
- Das Repo ist **footprint-sensibel**; Pagila/Sakila sind klein (~hunderte KB),
  **Employees** ist deutlich größer und steht unter **CC-BY-SA** (Attribution +
  Share-Alike).

## Entscheidung

**Hybrid (Option C), gestaffelt nach Größe/Lizenz — und alle Quellen gepinnt:**

1. **Pagila + Sakila → vendoren** als Test-Ressourcen (gepinnte Dumps, geladen via
   Testcontainers-Init). Klein, reproduzierbar, **offline-fähig** → tauglich fürs
   **PR-Gate**.
2. **Employees → on-demand/nightly** von gepinnter Quelle laden, **nicht** ins
   Repo eingecheckt (Footprint + CC-BY-SA-Share-Alike). Läuft nur im
   opt-in/nightly-Pfad, **nie** im PR-Gate.
3. **Pinning ist Pflicht** für jede Quelle: Commit-SHA oder Release-Tag, **nicht**
   `main` (wie aktuell im Kandidaten-Katalog) — sonst ist auch der Download nicht
   reproduzierbar.
4. **Lizenz-Vermerke** liegen neben den vendored Dumps (Herkunft, Lizenz,
   gepinnte Revision); CC-BY-SA-Attribution für Employees im nightly-Pfad
   dokumentiert.

## Konsequenzen

- **PR-Gate bleibt offline-deterministisch:** Pagila/Sakila-Smoke/Compatibility
  laufen ohne Netz (vendored).
- **Employees-Scale braucht Netz** → bewusst nur nightly/opt-in; ein PR-Build
  ohne Netz bricht dadurch nicht.
- **Repo wächst moderat** um zwei kleine Dumps + Lizenz-Vermerke; der große
  Employees-Dump bleibt draußen.
- **Lizenz-Sorgfalt:** vendored Dumps tragen ihre Lizenz mit; CC-BY-SA-Share-Alike
  wird durch Nicht-Vendoren von Employees vermieden.
- **Folgepflicht:** wandert eine externe Quelle/Revision, muss der Pin
  nachgezogen werden (bewusste, sichtbare Änderung statt stillem Drift).

## Abgrenzung

- Diese ADR regelt **nur das Sourcing**, nicht den Harness-Aufbau selbst (Modul,
  Phasen, Expected-Result-Baseline) — das steht im Slice-Plan.
- Performance/TPC (LF 8.1/8.2) ist ein eigener 1.0.0-QA-Slice und hier nicht
  adressiert.
