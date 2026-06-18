# Plan: Automatisierter Sample-DB-Integrationstest-Harness

> Dokumenttyp: Umsetzungsplan (Slice)
> Status: Entwurf (2026-06-18)
> Roadmap-Slot: Phase 1–2 (Smoke/Compatibility) = Test-Infrastruktur; Phase 3–4
> (Scale/Performance) = 1.0.0-QA (deckt **LF 8.1** 1 Mio. Datensätze / **LF 8.2**
> 1000 Tabellen < 30 s ab — heute 🔮 in der Roadmap)
> Referenzen: [`../open/test-database-candidates.md`](../open/test-database-candidates.md)
> (Kandidaten-Katalog), [`../../operations/pilot-validation-playbook.md`](../../operations/pilot-validation-playbook.md)
> (Szenarien-Vorlage), ADR 0004 (Planning-Struktur)

## Ziel

Die im Pilot bewährten Sample-DBs (Pagila, Sakila, später Employees) als
**reproduzierbare CI-Integrationstests** operationalisieren: Container starten →
Dump laden → `reverse` → `validate` → `generate --split` → Zielschema anlegen →
`data transfer` → `schema compare` (clean / erklärte Diffs). Schließt die Lücke
„Smoke/Compatibility liefen bisher nur ad-hoc im Pilot, nicht automatisiert" und
legt die Basis für Scale-/Performance-Gates.

## Pivotale Design-Entscheidung — Dump-Sourcing (ADR-würdig, Phase 0)

Die Sample-Dumps sind externe Artefakte. Drei Optionen mit echten Trade-offs:

| Option | Reproduzierbar/offline | Footprint | Lizenz-Sorgfalt | CI-Determinismus |
| ------ | ---------------------- | --------- | --------------- | ---------------- |
| **A — vendoren** (Test-Ressource, Testcontainers `withInitScript`) | ✅ | ⚠️ Repo wächst (Pagila/Sakila klein; Employees groß) | Dump + Lizenz mitcheckbar | ✅ |
| **B — zur Testzeit von URL laden** | ❌ (Netz nötig) | ✅ keiner | nur Verweis | ❌ Flaky / offline-Build bricht |
| **C — Hybrid** | teils | gemischt | gemischt | teils |

**Empfehlung:** **A für Pagila/Sakila** (klein, reproduzierbar — passt zur
CI-Determinismus-Linie des Repos), **C für Employees** (groß → on-demand/nightly
geladen, nicht vendored, wegen Footprint). Entscheidung gehört in einen
**Sourcing-ADR** (vendoren-Policy + Footprint-Budget + Drittsoftware-Lizenzen).

## Scope-Skizze (Phasen)

- **Phase 0 — Sourcing-ADR.** Vendoren-vs-Download-Policy, Footprint-Budget,
  Lizenz-Vermerke (Pagila, Sakila, Employees). Voraussetzung für jeden Code.
- **Phase 1 — Smoke (Pagila/PG).** Neues, noch zu erstellendes Test-Modul
  `test/sample-db-matrix` <!-- d-check:ignore (geplantes Test-Modul, existiert noch nicht; ADR 0011) -->
  (gegated `-PintegrationTests`) oder Erweiterung des bestehenden `test/cross-dialect-matrix`.
  Pagila laden → reverse → validate (0 Errors) → generate split pre/post → neues
  PG-Schema → transfer → `schema compare` clean.
- **Phase 2 — Compatibility (Sakila/MySQL + Cross-DB).** Sakila-Lauf analog;
  Pagila↔Sakila-Dialektvergleich (TINYINT(1)↔BOOLEAN, Enum-Case, FK-Graphen).
- **Phase 3 — Scale (Employees/MySQL).** Streaming/Chunking/Resume gegen größeres
  Volumen; **opt-in/nightly** (analog `perf-large-schema` / D-N10k), nicht im
  PR-Gate.
- **Phase 4 — Performance (TPC-H/-DS).** Benchmark-Gates für LF 8.1/8.2;
  eigener 1.0.0-QA-Slice.

## Vorbedingungen

- Sourcing-ADR (Phase 0) entschieden.
- Vorhanden: Testcontainers-Infra, `-PintegrationTests`-Gating, das
  `perf-large-schema`/Nightly-Muster für Scale-Gating, Szenarien-Vorlage im
  Pilot-Validierungs-Playbook.

## Akzeptanzkriterien (je Phase)

- Container startet, Dump deterministisch geladen (vendored = offline-fähig).
- `reverse`→`generate`→`apply`→`schema compare` clean **oder** Differenzen mit
  erwarteter Dialekt-Grenze (W103/E053/E056) erklärt — kein stiller Diff.
- Zeilenzahlen Quelle = Ziel je transferierter Tabelle (Stichprobe).
- Läuft im CI-`Integration Tests`-Workflow grün; Scale/Perf nur nightly/opt-in.
- Keine Netzabhängigkeit im PR-Gate (vendored), Footprint im ADR-Budget.

## Nicht-Ziel

- Ersatz der menschlichen ≥5-Tester-Pilotabnahme (LF 9.2) — das bleibt separat.
- Vollständige TPC-Benchmark-Suite in Phase 1–3 (erst Phase 4).
