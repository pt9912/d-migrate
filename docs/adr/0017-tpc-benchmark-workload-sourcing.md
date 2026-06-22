---
status: proposed
date: 2026-06-22
decision-makers: pt9912
consulted: docs/planning/next/tpc-performance-slice.md (Phase 4), docs/adr/0014-sample-db-harness-fetch-and-compose.md (Pin-Vertrag), spec/lastenheft-d-migrate.md (LF 8.1/8.2/8.5), "Postgres test data generation 101" (Kaarel Moppel, pgDay Nordic 2025 — Landschaft Testdaten-Generierung), TPC-Council/HammerDB (Kandidat-Tool)
informed: examples/sample-db, docs/operations/performance-benchmarks.md
---

# Phase-4-Benchmark-Workload: Generator-Tool pinnen statt statischem Dump

> **Status: proposed (Entwurf/Vorlage).** Offene Felder unten sind vor `accepted`
> zu entscheiden. Begleitet den Slice
> [`../planning/next/tpc-performance-slice.md`](../planning/next/tpc-performance-slice.md)
> (Sub-Slice 4a) und löst dessen Sourcing-/Pin-/Lizenz-Blocker. **Blocker 3
> (normierte Mess-Umgebung) ist NICHT Teil dieser ADR** — er betrifft die Mess-
> *Methodik*, nicht das Sourcing.

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

## Tool-Optionen (ZU ENTSCHEIDEN — Sub-Slice 4a)

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

## Offene Felder (vor `accepted`)

1. **Tool-Wahl A/B/C** (Empfehlung A).
2. **Pin-Mechanik je Tool:** (A) DuckDB-Version + Extension-Version + SF; (B) HammerDB
   Image-Digest/Release-Tag (v5.0) + Config.
3. **Lizenz-Freigabe** der gewählten Option (A unkritisch; B GPL-als-Container-Stance
   bestätigen).
4. **Blocker 3 — normierte Mess-Umgebung** (separat; gatet die harten 4c/4d-Budgets).

## Konsequenzen

- Sub-Slice 4a wird baubar, sobald 1–3 entschieden sind; 4b (Schema-Round-Trip) und
  das synthetische 4d-DDL-Gate sind davon unabhängig vorziehbar.
- Reproduzierbarkeit über gepinntes Tool + Config statt Daten-SHA256 — bewusste,
  hier dokumentierte ADR-0014-Abweichung.
- Kein Benchmark-Dump im Repo; Verlustfreiheit per-Lauf (LF 8.5) verifiziert.
