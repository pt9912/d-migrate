---
status: accepted
date: 2026-06-22
decision-makers: pt9912
consulted: docs/planning/next/tpc-performance-slice.md (Phase 4), docs/adr/0014-sample-db-harness-fetch-and-compose.md (Pin-Vertrag), spec/lastenheft-d-migrate.md (LF 8.1/8.2/8.5), "Postgres test data generation 101" (Kaarel Moppel, pgDay Nordic 2025 — Landschaft Testdaten-Generierung), TPC-Council/HammerDB (Kandidat-Tool)
informed: examples/sample-db, docs/operations/performance-benchmarks.md
---

# Phase-4-Benchmark-Workload: Generator-Tool pinnen statt statischem Dump

> **Status: accepted (ratifiziert 2026-06-22).** Tool-Wahl **A (DuckDB-`tpch`)**;
> konkrete Pin-Werte unter „Ratifizierte Entscheidung". Begleitet den Slice
> [`../planning/done/tpc-performance-slice.md`](../planning/done/tpc-performance-slice.md)
> (Sub-Slice 4a) und löst dessen Sourcing-/Pin-/Lizenz-Blocker. **Blocker 3
> (normierte Mess-Umgebung) ist NICHT Teil dieser ADR** — er betrifft die Mess-
> *Methodik*, nicht das Sourcing (siehe [ADR 0018](0018-normalized-perf-measurement-environment.md)).

## Kontext und Problemstellung

Phase 4 braucht eine **realistische, großvolumige Workload**, um die LF-8.1/8.2-
Abnahmen zu belegen (Export/Import 1 Mio+ verlustfrei < 100 s / < 200 s; DDL-1000
< 30 s). d-migrate ist **keine Query-Engine** — gemessen wird **Transfer-Durchsatz +
DDL-Zeit**, nicht Query-Latenz. Gebraucht wird also primär ein **realistisches Schema
(Joins/FKs)** + **skalierbares Datenvolumen**.

**Konflikt mit dem Pin-Vertrag (ADR 0014).** Die bisherigen Samples (Pagila/Sakila/…)
sind **fixe Upstream-Artefakte** (Datei + Commit-SHA + SHA256, on-demand, nichts im
Repo). TPC-artige Daten sind dagegen **generiert** — es gibt keine „eine wahre Datei",
und ein Generator-Output ist nicht byte-stabil über Versionen/Plattformen.

**Lizenz.** TPC-`dbgen`/`dsdgen` stehen unter TPC-EULA (kein OSS), abgeleitete Daten
unter Branding-/Audit-Bedingungen. Das Repo ist MIT (vgl. semgrep-Gate: LGPL+Commons
Clause durfte nicht vendored werden).

## Entscheidung (Kern, dialekt-/tool-unabhängig)

**Wir pinnen den GENERATOR (Tool + Konfiguration), nicht die Daten** — und generieren
on-demand in die Quell-DB, analog zum gepinnten `gdal`-Loader-Container für das
PostGIS-nyc-Sample (Phase 5). Konkret:

- **Kein TPC-/Workload-Dump im Repo** (ADR-0014-Geist gewahrt: reproduzierbar +
  nichts eingecheckt). Der „Pin" ist das **gepinnte Tool-Image/Version + die
  Generierungs-Config** (Scale-Factor/Warehouses), nicht ein Daten-SHA256.
- **Verlustfreiheit wird per-Lauf verifiziert** (LF 8.5: Byte-/SHA-256-Vergleich
  Quelle↔Ziel **innerhalb** des Laufs), **nicht** gegen einen fixen Baseline-Dump.
  Daher ist Byte-Determinismus der *generierten* Daten **nicht** erforderlich —
  Reproduzierbarkeit kommt aus dem gepinnten Tool + der Config.
- **Abweichung von ADR 0014** (Daten-Artefakt-Pin → Generator-Tool-Pin) ist die
  bewusst delegierte permanente Ausnahme, die diese ADR trägt (Modul 7).

## Tool-Optionen (entschieden: A — DuckDB-`tpch`)

| Option | Was | Lizenz | Schema-Realismus | Gewicht | Caveat |
|---|---|---|---|---|---|
| **(A) DuckDB-`tpch`-Extension** | echtes TPC-H-Schema (8 Tab.) + skalierbare Daten, embedded | **MIT** | hoch (echtes TPC-H) | **leicht** | Output nicht byte-stabil (egal, da Tool gepinnt wird, kein Daten-SHA) |
| **(B) HammerDB (TPROC-H)** | voller Benchmark-Driver als gepinnter Container (wie `gdal`) | **GPL-3.0** | hoch (TPROC-H, TPC-abgeleitet) | **schwer** | Query-Driver-Teil ungenutzt; GPL nur als *separates* Tool ok (nicht vendored/gelinkt); „TPROC" meidet TPC-Branding |
| **(C) Schlanke SQL-Generierung** | `generate_series`/pgbench-Skripte | MIT/PG | niedrig (synthetisch) | **leichtest** | Schema trivial → schwächerer Round-Trip-Korrektheitstest (4b) |

**Empfehlung: (A) DuckDB-`tpch`.** Begründung: (i) **MIT** — kein GPL-/EULA-Thema;
(ii) liefert das **echte TPC-H-Schema** (gut für 4b-Korrektheit) **und** Volumen;
(iii) **leichtgewichtig** (embedded Extension, kein voller Benchmark-Stack) — deckt
sich mit der Moppel-Leitlinie „schlanker Ansatz schlägt das große Framework, solange
Verteilung/Kardinalität/Volumen sauber sind". Der Query-Driver-Mehrwert von HammerDB
(B) ist für d-migrate **irrelevant** (wir messen Transfer/DDL, nicht Query-Latenz).
(B) bleibt Option, falls später ein vollerer, realistischerer Daten-/Lastmix gewünscht
ist (GPL dann als *gepinnter Loader-Container*, Daten on-demand — sauber gegenüber MIT).

## Lizenz-Analyse (zu bestätigen)

- **(A) DuckDB-`tpch`:** MIT-Extension, lokal generiert → unkritisch. Die TPC-H-
  *Schema-Definition* stammt aus TPC; da nichts eingecheckt + nichts publiziert wird
  (kein Audit-Anspruch, siehe Nicht-Ziele im Slice), greift die EULA-Branding-Auflage
  nicht.
- **(B) HammerDB:** GPL-3.0 — als **separater, gepinnter Container** ausgeführt (kein
  Vendoring, kein Linken, keine Weiterverteilung), generierte Daten sind **kein**
  Derivat des GPL-Codes → keine Copyleft-Infektion des MIT-Repos (Präzedenz:
  externer Tool-Container wie `gdal`). HammerDB nutzt **TPROC-C/TPROC-H** (TPC-
  abgeleitet, bewusst umbenannt, ohne TPC-Audit/Branding-Bindung).

## Ratifizierte Entscheidung (A — ratifiziert 2026-06-22)

Konkrete Werte der gewählten **Option A**:

1. **Tool-Wahl: A (DuckDB-`tpch`).** Begründung siehe Empfehlung oben (MIT, echtes
   TPC-H-Schema, leichtgewichtig). B/C bleiben dokumentierte Fallbacks.
2. **Pin-Mechanik (A):**
   - **DuckDB-Version: LTS-Linie „Andium" 1.4.x, gepinnt auf konkreten Release-Tag
     1.4.5** (Stand 2026-06-17). LTS gewählt — bugfix-only, langfristig vergleichbares
     Benchmark-Ergebnis (gegenüber der schnelleren Stable-Linie 1.5.x).
   - **Extension-Version: separat zu pinnen** (Korrektur 4a, 2026-06-23). Anders als
     hier ursprünglich angenommen ist `tpch` **nicht** im CLI-Binary gebündelt — das
     v1.4.5-CLI lädt die Extension beim ersten `CALL dbgen` von `extensions.duckdb.org`
     nach (scheitert unter `--network none`). Daher wird die **`tpch.duckdb_extension`
     (v1.4.5/linux_amd64) per SHA256 mitgepinnt** und offline aus Datei `LOAD`-ed; erst
     damit ist die Generierung hermetisch. Konkrete Pins + Live-Beleg:
     [4a-Slice](../planning/done/tpc-4a-sourcing-slice.md).
   - **Pin-Träger:** exakter Versions-Pin in einem Digest-gepinnten Basis-Image (analog
     zum `gdal`-Loader-Container, Phase 5) — z. B. `duckdb==1.4.5` (PyPI, exakt) oder
     das CLI-Release `v1.4.5` + SHA256. (Konkretes Trägerformat in 4a.)
   - **Workload-Config:** Scale-Factor als Harness-Parameter. **SF=1 ≈ 8,6 Mio Zeilen**
     (lineitem ~6 Mio) ⇒ erfüllt LF-8.1 „1 Mio+"; größere Abnahme-SF konfigurierbar.
     Speicher-schonende Stufengenerierung via `dbgen(sf=N, children=C, step=S)`.
3. **Lizenz-Freigabe (A):** Core-Extension unter **MIT** (DuckDB-Lizenz), lokal
   generiert, nichts eingecheckt/publiziert → unkritisch; keine TPC-EULA-/Branding-
   Bindung (kein Audit-Anspruch, siehe Nicht-Ziele im Slice).
4. **Blocker 3 — normierte Mess-Umgebung:** in
   [ADR 0018](0018-normalized-perf-measurement-environment.md) entschieden (separat;
   gatet die harten 4c/4d-Budgets). **Nicht Teil dieser ADR.**

## Konsequenzen

- Sub-Slice 4a wird baubar, sobald 1–3 entschieden sind; 4b (Schema-Round-Trip) und
  das synthetische 4d-DDL-Gate sind davon unabhängig vorziehbar.
- Reproduzierbarkeit über gepinntes Tool + Config statt Daten-SHA256 — bewusste,
  hier dokumentierte ADR-0014-Abweichung.
- Kein Benchmark-Dump im Repo; Verlustfreiheit per-Lauf (LF 8.5) verifiziert.

## Nachtrag (2026-06-25): TPC-DS (`tpcds`-Extension)

Der optionale Sub-Slice 4e (TPC-DS, 24 Tabellen) wendet **dieselbe** hier getroffene
Entscheidung auf die **`tpcds`-Core-Extension** an: dieselbe Tool-Familie (DuckDB), dieselbe
**MIT**-Lizenz, derselbe Pin-Mechanismus (CLI v1.4.5 + separat SHA256-gepinnte
`tpcds.duckdb_extension`, nicht im CLI gebündelt → offline aus Datei `LOAD`-ed). `CALL
dsdgen(sf=N)` statt `dbgen`. Das ist **keine neue Grundsatzentscheidung**, nur die Anwendung
von Punkt 1–3 auf die zweite Workload — daher als Nachtrag statt eigener ADR. Geliefert +
live-verifiziert: [`../planning/done/tpc-4e-tpcds-slice.md`](../planning/done/tpc-4e-tpcds-slice.md).
