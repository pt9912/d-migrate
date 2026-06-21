# Plan: Sample-DB-Harness Phase 4 — Performance (TPC-H/-DS, LF 8.1/8.2)

> Dokumenttyp: Next-Plan (Folge-Slice von [`../in-progress/sample-db-integration-harness.md`](../in-progress/sample-db-integration-harness.md))
> Status: Entwurf (2026-06-21). Scope ausgearbeitet, **Bau folgt**;
> Sourcing-/Workload-/Methodik-Entscheidungen vorab zu treffen.
> Trigger: Slice-Grenze Phase 0–3 ist DoD-komplett; Performance/TPC ist ein
> ausgegliederter **1.0.0-QA-Folge-Slice** (ADR 0014/0013).
> Referenzen: ADR 0014 (Harness-Mechanik), ADR 0004 (Planning-Struktur),
> [`../../operations/performance-benchmarks.md`](../../operations/performance-benchmarks.md)
> (vorhandene Perf-Infra + LF-8.1/8.2-Lückenanalyse),
> [`../../../spec/lastenheft-d-migrate.md`](../../../spec/lastenheft-d-migrate.md) §8.2,
> [`../open/test-database-candidates.md`](../open/test-database-candidates.md).
> Nicht-blockierend für 1.0.0-Funktionalität; QA-Abnahme-Ziel.

## Ziel

Realistische, großvolumige **Benchmark-Workloads** (TPC-H und/oder TPC-DS) gegen
das echte CLI fahren und die **formalen Lastenheft-Abnahmen LF 8.1/8.2** belegen —
über die heutige Smoke-Perf-Infra hinaus.

## Abgrenzung (wichtig: TPC ≠ LF 8.1/8.2)

Der Umbrella-Plan koppelt „Phase 4 (Performance/TPC, LF 8.1/8.2)" — die drei sind
aber teils orthogonal und müssen sauber getrennt werden:

- **LF 8.1** („1 Mio. Datensätze Export/Import ohne Datenverlust") ist **durch
  Phase 3 datenmäßig faktisch erbracht**: der Employees-Scale-Smoke transferiert
  ~4 Mio Zeilen export→import mit exakter Zeilen-Parität + `SUM(salary)`-Checksumme.
  Offen bleibt nur die **formelle Benchmark-Abnahme** (gemessen, gegen Budget).
  → `performance-benchmarks.md` §8 ist hier nachzuziehen (LF 8.1 ≈ erfüllt).
- **LF 8.2** („DDL-Generierung 1 000 Tabellen < 30 s") ist **synthetisch, nicht
  TPC** (TPC-H hat 8, TPC-DS 24 Tabellen). Heute läuft ein N=1000-Smoke mit
  lockerem ≤ 120 s-Budget; LF 8.2 ist dessen **Verschärfung auf < 30 s** als harte
  Abnahme — unabhängig von TPC.
- **TPC-H/-DS** ist die **realistische Workload-Erweiterung** (Join-/Aggregat-
  lastige Schemata, Standard-Skalierung) der Perf-Validierung — der eigentlich
  neue Teil dieses Slices.

## Vorhandene Infrastruktur (nicht neu bauen)

- `make docker-perf` — `perf`-getaggte Kotest-Specs; `PERF_GATE=true` macht das
  Per-Hotpath-Baseline-Budget zum harten Gate (`PerfMeasure`/`PerfReport` im
  `hexagon/profiling`-Modul).
- `docs/operations/performance-benchmarks.md` — Methodik + LF-8.1/8.2-Status.
- Phase-0–3-Harness-Muster (compose + Scripts + gepinnte Baseline, opt-in/nightly).

## Offene Grundentscheidungen (vor dem Bau)

1. **Sourcing der TPC-Daten:**
   - **DuckDB `tpch`/`tpcds`-Extension** — generiert deterministisch je Scale-Factor
     (kein C-Toolchain-Build); Export nach CSV/Parquet, dann pinnen oder on-demand
     generieren. *Empfohlen* (reproduzierbar, leichtgewichtig).
   - **`dbgen`/`dsdgen`** (offizielle C-Tools) — kanonisch, aber Build-Schritt +
     Lizenz-/Redistribution-Beachtung.
   - **Gepinnte vorgenerierte Datensätze** (Mirror) — einfachstes Fetch, aber großer
     Footprint + Mirror-Vertrauen.
2. **Workload:** TPC-H (8 Tabellen, einfacher Einstieg) zuerst; TPC-DS (24 Tabellen)
   optional als zweiter Sub-Slice.
3. **Scale-Factor:** Smoke (SF ~0.01) für CI-Funktionsnachweis vs. Abnahme (SF 1 ≈
   1 GB / ~6 Mio lineitem-Zeilen) für LF 8.1.
4. **Mess-Methodik:** was genau gemessen wird (Transfer-Durchsatz Zeilen/s,
   Wandzeit, konstanter Speicher) + Budget-Quelle + Mess-Umgebungs-Stabilität.

## Scope-Skizze (Sub-Slices)

- **4a — Sourcing + Pinning.** TPC-H-Generierung/-Pin entscheiden + im
  Kandidaten-Katalog dokumentieren; Footprint/Reproduzierbarkeit wie ADR 0014.
- **4b — Schema-Round-Trip-Korrektheit.** TPC-H-Schema reverse/validate/generate/
  transfer (wie Phase 1/2) — Korrektheit zuerst, vor Messung.
- **4c — LF 8.1 formelle Abnahme.** 1-Mio-(bzw. SF-1-)Export/Import gemessen gegen
  Budget; `performance-benchmarks.md` §8 nachziehen.
- **4d — LF 8.2 Verschärfung.** N=1000-DDL-Smoke von ≤ 120 s auf die < 30 s-
  Abnahmeschwelle heben (synthetisch, **nicht** TPC) — ggf. eigener Mini-Slice.
- **4e — (optional) TPC-DS** als zweite, komplexere Workload.

## Vorbedingungen

- Phase-0–3-Harness-Muster + `docker-perf`-Infra — **vorhanden**.
- Stabile Perf-Mess-Umgebung (Budgets sind hostabhängig) — **zu klären** (4c/4d).
- Sourcing-Entscheidung (oben) — **zu treffen** (4a).

## Akzeptanzkriterien

- **4b:** TPC-H-Schema round-trippt grün (Parität + erwartete Notes gepinnt).
- **4c:** LF 8.1 als gemessene Abnahme dokumentiert (export/import ohne Verlust,
  Durchsatz gegen Budget); `performance-benchmarks.md` aktualisiert.
- **4d:** N=1000-DDL < 30 s als harte Abnahme (LF 8.2).
- **Gating:** opt-in/nightly (wie Phase 3), **nicht** im PR-Gate (Laufzeit/Volumen).
- **Übergreifend:** kein Dump im Repo; `make docs-check` grün.

## Nicht-Ziel

- Spatial (Phase 5, eigener Slice).
- TPC-Query-Performance-Benchmarking (d-migrate transferiert Daten/Schema; es ist
  **keine** Query-Engine — gemessen wird Transfer/DDL, nicht TPC-Query-Latenz).
- Wettbewerbs-/Veröffentlichungs-taugliche TPC-Zahlen (offizielle TPC-Audits sind
  ausdrücklich kein Ziel).
