# Plan: Sample-DB-Harness Phase 4 — Performance (TPC-H/-DS, LF 8.1/8.2)

> Dokumenttyp: Next-Plan (Folge-Slice von [`../in-progress/sample-db-integration-harness.md`](../in-progress/sample-db-integration-harness.md))
> Status: Entwurf, **überarbeitet nach Plan-Review (2026-06-21)**. Scope ausgearbeitet,
> **Bau folgt**. **Wichtigste Review-Korrekturen:** (a) LF-Zuordnung gegen den echten
> Lastenheft-Wortlaut präzisiert; (b) „LF 8.1 durch Phase 3 erbracht" zurückgenommen
> (Phase 3 misst **keine Zeit**); (c) ADR 0014-Pin-Vertrag für generierte TPC-Daten geklärt.
> Trigger: Slice-Grenze Phase 0–3 ist DoD-komplett; Performance/TPC ist ein
> ausgegliederter **1.0.0-QA-Folge-Slice** (ADR 0014/0013).
> Referenzen: ADR 0014 (Harness-Mechanik + Pin/SHA256-Vertrag), ADR 0004,
> [`../../operations/performance-benchmarks.md`](../../operations/performance-benchmarks.md)
> (vorhandene Perf-Infra + Lückenanalyse),
> [`../../../spec/lastenheft-d-migrate.md`](../../../spec/lastenheft-d-migrate.md)
> (Abnahmekriterien 8.1/8.2). Nicht-blockierend für 1.0.0-Funktionalität; QA-Abnahme-Ziel.

## Ziel

Realistische, großvolumige **Benchmark-Workloads** (TPC-H und/oder TPC-DS) gegen
das echte CLI fahren und die **formalen Lastenheft-Abnahmen** belegen — über die
heutige Smoke-Perf-Infra hinaus.

## LF-Abnahmekriterien — exakter Wortlaut (Review-korrigiert)

Das Lastenheft trennt zwei Ebenen; der ältere „LF 8.1/8.2"-Sammelbegriff verschleift sie:

- **LF 8.1 — Funktionale Tests** (Verlustfreiheit, **kein** Zeitbudget). Relevant:
  „Export und Re-Import von **mindestens 1 Million Datensätzen ohne Datenverlust**".
- **LF 8.2 — Performance-Tests** (Zeit/Skalierung). Relevant u. a.:
  „DDL-Generierung für 100 Tabellen in unter 5 s", „Export von 1 Mio in unter
  **100 s**", „Import von 1 Mio in unter **200 s**", „DDL-Generierung für 1.000
  Tabellen in unter **30 s**", „Export von 10 Mio ohne Out-of-Memory",
  „Checkpoint/Resume: erfolgreicher Wiederanlauf nach simuliertem Abbruch bei 50 %".

**Was Phase 3 (Employees-Scale) bereits belegt — und was nicht:**
- ✅ Verlustfreiheit (LF 8.1): ~4 Mio Zeilen export→import, Zeilen-Parität +
  `SUM(salary)`-Checksumme. Das **übertrifft datenmäßig** die 1-Mio-Schwelle.
- ✅ Checkpoint/Resume nach Abbruch (LF 8.2, eine Zeile): Mid-Stream-`docker kill` +
  `--resume` (allerdings nicht exakt „bei 50 %").
- ❌ **Keine gemessene Zeit** gegen Budget (Export <100 s / Import <200 s) — Phase 3
  asserrt nur Parität/Checksumme, kein Zeit-/Heap-Budget. `performance-benchmarks.md`
  ist hier nachzuziehen (die dortige Aussage „kein Datenvolumen-Test in dieser Größe"
  stammt von **vor** Phase 3 und gilt nur noch für die **gemessene** Abnahme).

→ **Korrektur gegenüber Entwurf v1:** „LF 8.1 ≈ erfüllt" wird zurückgenommen.
Verlustfreiheit ist *plausibilisiert*; die **gemessene** Performance-Abnahme (LF 8.2)
steht vollständig aus.

## Abgrenzung: TPC ≠ LF-Schwellen

Die LF-8.2-Schwellen sind **synthetisch/volumenbasiert** (N=1000-Tabellen, 1-Mio-
Zeilen), **nicht** TPC. TPC-H (8 Tabellen) / TPC-DS (24 Tabellen) ist eine
**realistische Join-/Aggregat-lastige Workload** zusätzlich zu den synthetischen
Schwellen — der eigentlich neue Teil dieses Slices. d-migrate ist **keine**
Query-Engine: gemessen wird **Transfer-Durchsatz + DDL-Zeit**, nicht TPC-Query-Latenz.

## Vorhandene Infrastruktur (nicht neu bauen)

- `make docker-perf` + `PERF_GATE=true` (Per-Hotpath-Baseline-Budget als hartes Gate;
  `PerfMeasure`/`PerfReport` im `hexagon/profiling`-Modul).
- **N=1000-DDL-Smoke existiert bereits** mit zwei Budgets: `renderSmokeMaxMs = 120_000`
  (Smoke) **und** `renderBaselineMs = 30_000` (Baseline-Gate unter `PERF_GATE=true`)
  in `test/perf-large-schema/src/test/kotlin/dev/dmigrate/test/perf/LargeSchemaScaleSpec.kt`.
  Das **30-s-Budget für
  LF 8.2 ist also bereits kodiert** — es wird auf geteilter CI nur nicht hart asserrt.
- `performance-benchmarks.md` (Methodik + Lückenanalyse). Phase-0–3-Harness-Muster.

**Review-Caveat (Mess-Last):** der N=1000-Smoke baut ein **gemischtes** Schema
(`tables=n, sequences=n, views=n, triggers=n` + **1** geteilte Trigger-Funktion =
**4×n + 1** Objekte; die ältere KDoc-Angabe „5×n" zählt fälschlich `n` Funktionen)
und misst den **Diff-Planner + PG-Diff-Renderer** (`SchemaComparator` → `DiffPlanner`
→ `PostgresDiffDdlGenerator`), nicht reine „1000-Tabellen-DDL-Generierung". Vor der
LF-8.2-Abnahme ist festzulegen, ob dieser 4×n-Diff-Pfad gilt (großzügig) oder ein
schmaler reiner Generate-Pfad für genau 1000 Tabellen gebraucht wird.

## Offene Grundentscheidungen (vor dem Bau)

1. **Sourcing + ADR 0014-Pin-Vertrag (Review-Blocker).** ADR 0014 verlangt eine auf
   Commit-SHA/Release gepinnte, **SHA256-verifizierte** Quelle, kein Dump im Repo.
   Ein **Generator** (DuckDB `tpch`/`tpcds`-Extension, `dbgen`/`dsdgen`) erzeugt
   **keinen** byte-identischen gepinnten Dump — der SHA256 hängt dann an Generator-
   + Extension-Version + Output-Format (CSV/Parquet, Spaltenreihenfolge, Float-
   Formatierung). Auflösungs-Optionen (in 4a zu entscheiden):
   - **(a) Generieren → einmal als Dump auf externen Mirror → wie Pagila/Sakila
     SHA256-pinnen** (de facto „gepinnter Datensatz"; ADR 0014-konform). **Default-
     Empfehlung** — robust gegen Generator-Drift.
   - **(b) Generator-Version + Extension-Version + Output-Format pinnen + den
     erzeugten Output-SHA256 als Baseline** (Generator-Determinismus CI-verifiziert).
     **Caveat:** DuckDB-`tpch`-Output ist über DuckDB-Patch-Versionen/Plattformen
     **nicht** garantiert byte-stabil (Float-Repräsentation, Sortier-/Encoding-
     Ordering, Parquet-Encoding) → ein gepinnter Output-SHA256 kann beim nächsten
     DuckDB-Bump brechen, obwohl die Daten „dieselben" sind. Fragiler als (a).
   Abweichung vom Standard-Pin-Muster → ggf. **ADR-Delegation** (permanente
   Ausnahme gehört in ein ADR, nicht in den Slice-Plan).
2. **Lizenz (Review-Caveat).** TPC stellt `dbgen`/`dsdgen` unter **TPC-EULA** (kein
   OSS); abgeleitete TPC-Daten unterliegen Branding-/Redistributions-Bedingungen
   (vgl. semgrep-Gate: LGPL/Commons-Clause durfte nicht ins MIT-Repo). Der ADR 0014-
   Pfad „nichts einchecken, On-Demand-Fetch" entschärft Redistribution — Argument für
   Fetch statt Vendoring. DuckDB-`tpch`-Extension ist MIT (generiert lokal), die
   *Daten-Schema-Definition* stammt aber aus TPC → in 4a prüfen.
3. **Workload:** TPC-H (8 Tabellen) zuerst; TPC-DS (24) optional als zweiter Sub-Slice.
4. **Scale-Factor:** Smoke (SF ~0.01) für CI-Funktionsnachweis vs. Abnahme (SF 1 ≈
   1 GB / ~6 Mio `lineitem`-Zeilen) für die Volumen-Schwellen.

## Scope-Skizze (Sub-Slices)

- **4a — Sourcing + Pin-Vertrag.** Generator/Quelle + ADR 0014-konforme Pin-Strategie
   (oben) entscheiden + im Kandidaten-Katalog dokumentieren; Lizenz prüfen.
- **4b — Schema-Round-Trip-Korrektheit.** TPC-H-Schema reverse/validate/generate/
   transfer (wie Phase 1/2) — Korrektheit vor Messung.
- **4c — LF 8.1 + 8.2 Volumen-Abnahme (gemessen).** 1-Mio-(bzw. SF-1-)Export/Import:
   Verlustfreiheit (LF 8.1) **und** getrennte Zeit-Budgets (LF 8.2: Export < 100 s,
   Import < 200 s); exakter Pfad festnageln (`data transfer --chunk-size` **vs.**
   `data export`→`import --resume`); **plus** Resume nach Abbruch **bei ~50 %**
   (LF-8.2-Wortlaut; Phase 3 bricht heute beim ersten Checkpoint ab, also < 50 %).
   Doku-Sync: `performance-benchmarks.md` auf „Verlustfreiheit durch Phase 3
   plausibilisiert, gemessene Abnahme offen" nachziehen (der Umbrella-Plan ist
   bereits angeglichen).
- **4d — LF 8.2 DDL-1000-Gate aktivieren/stabilisieren.** Das **bestehende** 30-s-
   Baseline-Gate verlässlich grün stellen (nicht neu einführen); 4×n-Diff-vs-reiner-
   DDL-Pfad entscheiden; dabei die irreführende „5×n"-KDoc in `LargeSchemaScaleSpec.kt`
   auf „4×n + 1" korrigieren. Synthetisch, **nicht** TPC — ggf. eigener Mini-Slice.
- **4e — (optional) TPC-DS** als zweite, komplexere Workload.

**Reihenfolge-Gate:** 4c/4d (harte Zeit-Budgets) dürfen **erst nach** Festlegung der
normierten Mess-Umgebung (siehe Vorbedingungen) greifen — sonst sind die Budgets
auf geteilter CI flaky oder müssen so locker sein, dass sie nichts abnehmen.

## Vorbedingungen

- Phase-0–3-Harness-Muster + `docker-perf`-Infra — **vorhanden**.
- **Normierte Mess-Umgebung (Blocker für harte Budgets, 4c/4d).** `performance-
  benchmarks.md` hält fest, dass eine definierte Hardware-/Container-Umgebung fehlt
  und geteilte CI-Runner nur diagnostisch geprüft werden. Harte LF-8.2-Budgets
  brauchen ein fixiertes Runner-/Container-Sizing + Warmup-/Iterations-Vertrag —
  **vor** dem Versprechen harter Budgets festzulegen.
- Sourcing-/Pin-/Lizenz-Entscheidung (4a) — **zu treffen**.

## Akzeptanzkriterien

- **4b:** TPC-H-Schema round-trippt grün (Parität + erwartete Notes gepinnt).
- **4c:** Verlustfreiheit (LF 8.1) **gemessen** belegt; Export-/Import-Zeit getrennt
  unter den LF-8.2-Budgets (< 100 s / < 200 s) in der normierten Umgebung;
  `performance-benchmarks.md` aktualisiert.
- **4d:** N=1000-DDL < 30 s als hartes Gate (LF 8.2) in der normierten Umgebung.
- **Gating:** opt-in/nightly (wie Phase 3), **nicht** im PR-Gate (Laufzeit/Volumen).
- **Übergreifend:** kein Dump im Repo; `make docs-check` grün.

## Nicht-Ziel (Scope-Grenze der LF-8.2-Abnahme)

- **Weitere LF-8.2-Skalierungskriterien sind NICHT Teil dieses Slices** und gehören
  in eigene Slices / ADR-Delegation: „Export von 10 Mio ohne Out-of-Memory",
  „Parallele Verarbeitung: ≥5× Speedup bei 8 Kernen", „inkrementelle Migration 1000
  Tabellen < 1 h", „Partitionierte Tabelle: 100-Partitionen-Export parallel". Dieser
  Slice trägt nur: Verlustfreiheit (LF 8.1) + Export/Import-Zeit-Budgets +
  DDL-1000-<30 s + Resume-bei-50 % (LF 8.2). So bleibt sichtbar, dass die **formale
  LF-8.2-Abnahme insgesamt** noch weitere Bausteine braucht.
- Spatial (Phase 5, eigener Slice).
- TPC-Query-Performance-Benchmarking (d-migrate transferiert Daten/Schema; gemessen
  wird Transfer/DDL, nicht TPC-Query-Latenz).
- Wettbewerbs-/Veröffentlichungs-taugliche TPC-Audit-Zahlen.
