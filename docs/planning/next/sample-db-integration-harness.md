# Plan: Automatisierter Sample-DB-Integrationstest-Harness

> Dokumenttyp: Umsetzungsplan (Slice)
> Status: Entwurf (2026-06-18, Review-Update 2026-06-18)
> Roadmap-Slot: Phase 1–2 (Smoke/Compatibility) = Test-Infrastruktur; Phase 3
> (Scale) = 1.0.0-QA. **Phase 4 (Performance/TPC) ist ein eigener Folge-Slice**
> (deckt **LF 8.1** 1 Mio. Datensätze / **LF 8.2** 1000 Tabellen < 30 s ab — heute
> 🔮 in der Roadmap), kein Teil dieses Slice.
> Referenzen: [`../open/test-database-candidates.md`](../open/test-database-candidates.md)
> (Kandidaten-Katalog), [`../../operations/pilot-validation-playbook.md`](../../operations/pilot-validation-playbook.md)
> (Szenarien-Vorlage), ADR 0004 (Planning-Struktur), ADR 0012 (Index-Präfix-Scope,
> Beispiel einer bewussten Round-Trip-Lücke).

## Ziel

Die im Pilot bewährten Sample-DBs (Pagila, Sakila; Employees für Scale) als
**reproduzierbare CI-Integrationstests** operationalisieren: Container starten →
Dump laden → `reverse` → `validate` → `generate --split` → Zielschema anlegen →
`data transfer` → `schema compare`. Schließt die Lücke „Smoke/Compatibility liefen
bisher nur ad-hoc im Pilot, nicht automatisiert".

## Slice-Grenze (DoD-Boundary)

**Dieser Slice umfasst Phase 0–3.** Phase 4 (TPC-H/-DS-Performance, LF 8.1/8.2)
ist ein **separater 1.0.0-QA-Slice** und hier nur als Forward-Pointer geführt.
Der Slice ist *fertig*, wenn Phase 1+2 als PR-Gate-Integrationstests grün laufen
und Phase 3 (Scale) als opt-in/nightly verfügbar ist.

## Pivotale Design-Entscheidung — Dump-Sourcing (ADR-würdig, Phase 0)

Die Sample-Dumps sind externe Artefakte. Optionen mit echten Trade-offs:

| Option | Reproduzierbar/offline | Footprint | Lizenz-Sorgfalt | CI-Determinismus |
| ------ | ---------------------- | --------- | --------------- | ---------------- |
| **A — vendoren** (Test-Ressource, Testcontainers `withInitScript`) | ✅ | ⚠️ Repo wächst (Pagila/Sakila klein; Employees groß) | Dump + Lizenz mitcheckbar | ✅ |
| **B — zur Testzeit von URL laden** | ❌ (Netz nötig) | ✅ keiner | nur Verweis | ❌ Flaky / offline-Build bricht |
| **C — Hybrid** | teils | gemischt | gemischt | teils |

**Empfehlung:** **A für Pagila/Sakila**, **C für Employees**. Zwingend in **jeder**
Variante: **gepinnte Quelle** (Commit-SHA/Release-Tag, nicht `main` wie im
Katalog) — sonst ist auch der Download nicht reproduzierbar.

**Lizenz-Hinweis (Grund für den Hybrid-Schnitt):** Employees (`datacharmer/test_db`)
steht unter **CC-BY-SA** (Attribution + Share-Alike) — Vendoren erfordert
Attribution und vergrößert das Repo deutlich; daher on-demand/nightly statt
eingecheckt. Pagila/Sakila-Lizenzen (PostgreSQL- bzw. Sakila/BSD-nah) im
Sourcing-ADR konkret festhalten.

## Objekt-Scope der Assertion (in-scope vs. erwarteter Skip)

Pagila/Sakila enthalten **mehr als Tabellen** (Views, Funktionen, Trigger). Ein
Cross-Dialect-Round-Trip erzeugt reihenweise **legitime** `E053`/`W`-Notes
(View-Bodies nicht transpiliert — I-09-Klasse, Sequence-Emulation, Präfixlängen).
„`schema compare` clean" ist daher die **falsche** Erwartung.

- **In-scope (muss round-trippen):** Tabellen, Spalten/Typen, PK/FK/UNIQUE/CHECK,
  Indizes, Daten (Zeilenzahl + Stichprobe), Sequenzen.
- **Erwarteter Skip (mit dokumentierter Note):** Views/Funktionen/Trigger bei
  Cross-Dialect (`E053`), dialekt-spezifische Index-/Constraint-Grenzen
  (`W103`/`E056`/`W123`/…), Präfixlängen auf PK/Constraint (ADR 0012-Lücke).

→ **Kernarbeitspaket: ein gepinnter Expected-Result-Baseline (Golden) je
Sample-DB** — die erwarteten Notes/Skips/Diffs, nicht „0 Diffs". Das ist der
eigentliche Aufwand und die laufende Wartungslast (Golden-Churn bei
Generator-Änderungen), nicht das Laden.

## Scope-Skizze (Phasen)

- **Phase 0 — Sourcing-ADR.** Vendoren-vs-Download-Policy, gepinnte Quellen,
  Footprint-Budget, Lizenz-Vermerke (Pagila, Sakila, Employees CC-BY-SA).
  Voraussetzung für jeden Code.
- **Phase 1 — Smoke (Pagila/PG).** Neues, noch zu erstellendes Test-Modul
  `test/sample-db-matrix` <!-- d-check:ignore (geplantes Test-Modul, existiert noch nicht; ADR 0011) -->
  **analog `test/integration-postgresql`** (Testcontainers + Dump-Load via
  `withInitScript`) — **nicht** als Erweiterung von `test/cross-dialect-matrix`
  (das läuft synthetisch im File-Mode ohne Live-Container, falsches Paradigma).
  Pagila laden → reverse → validate (0 Errors) → generate split pre/post → neues
  PG-Schema → transfer → `schema compare` gegen Expected-Baseline.
- **Phase 2 — Compatibility (Cross-Dialect je DB).** **Jede** Sample-DB wird
  cross-dialect transferiert und **gegen ihre eigene Quelle** geprüft (Pagila
  PG→MySQL, Sakila MySQL→PG) — *kein* direkter Pagila↔Sakila-Vergleich (andere
  Schemata). Fokus: TINYINT(1)↔BOOLEAN, Enum-Case, FK-Graphen, erwartete E053/W-
  Notes je Expected-Baseline.
- **Phase 3 — Scale (Employees/MySQL).** Streaming/Chunking/Resume gegen größeres
  Volumen. **Achtung — kein Nightly-CI-Mechanismus vorhanden** (es gibt keinen
  `schedule:`/`cron:`-Workflow; „nightly" existiert heute nur als Konvention um
  `make docker-perf`). Phase 3 muss daher entweder (a) einen **scheduled
  CI-Workflow neu anlegen** (eigenes Arbeitspaket) **oder** (b) ehrlich als reines
  **opt-in `make`-Target** geführt werden, nicht CI-gegated.
- **Phase 4 — Performance (TPC-H/-DS).** **Eigener 1.0.0-QA-Folge-Slice** für
  LF 8.1/8.2 — hier nur Forward-Pointer, nicht Teil dieses Slice.

## CI-Laufzeit-Budget

Die `Integration Tests`-Workflow läuft heute ~12 min. Voller Daten-Transfer +
Zeilenzahl-Check auf Pagila/Sakila kostet zusätzlich. Schnitt:

- **PR-Gate (Phase 1/2):** Schema-Smoke + Stichproben-Zeilenzahlen (begrenztes
  Volumen), Ziel < ~3 min Zusatzlast.
- **Voller Daten-Transfer / große Row-Counts / Scale (Phase 3):** opt-in/nightly,
  nicht im PR-Gate.

## Vorbedingungen

- Sourcing-ADR (Phase 0) entschieden; Quellen gepinnt.
- Vorhanden: Testcontainers-Infra (`integration-postgresql`/`-mysql` als Vorlage),
  `-PintegrationTests`-Gating, `make docker-perf`-Muster (opt-in), Szenarien-
  Vorlage im Pilot-Validierungs-Playbook.
- **Nicht vorhanden (Phase 3):** ein scheduled/nightly CI-Workflow — ggf. erst zu
  bauen (siehe Phase 3).

## Akzeptanzkriterien (je Phase)

**Phase 0:** ADR `accepted`; Sourcing-Strategie + gepinnte Quellen + Footprint-
Budget + Lizenz-Vermerke festgeschrieben.

**Phase 1 (Pagila Smoke):** Container startet + Pagila deterministisch geladen
(offline-fähig bei vendored); reverse/validate ohne offene Errors; generate
split; `schema compare` == Expected-Baseline (keine *unerklärten* Diffs);
Stichproben-Zeilenzahlen Quelle = Ziel; läuft im `Integration Tests`-PR-Gate
grün; Zusatzlaufzeit im Budget.

**Phase 2 (Compatibility):** Pagila PG→MySQL und Sakila MySQL→PG je gegen eigene
Quelle abgenommen; erwartete E053/W-Notes gegen Expected-Baseline gepinnt;
TINYINT(1)↔BOOLEAN + Enum-Case datenbelegt.

**Phase 3 (Scale):** Employees-Transfer mit Resume nach simulierter
Unterbrechung; Chunking-Pfad belegt; Gating-Mechanismus (scheduled Workflow
oder opt-in-Target) dokumentiert und grün; **nicht** im PR-Gate.

**Übergreifend:** koverVerify-neutral (reiner Testcode, kein `main`-Modul);
`make docs-check` grün.

## Nicht-Ziel

- Ersatz der menschlichen ≥5-Tester-Pilotabnahme (LF 9.2) — bleibt separat.
- TPC-Benchmarks (Phase 4 = eigener Slice).
- Direkter Pagila↔Sakila-Schemavergleich (unterschiedliche Schemata).
