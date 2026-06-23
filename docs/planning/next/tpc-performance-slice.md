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

## Stand & Wiedereinstieg (2026-06-23)

**Beide Decision-Blocker aufgelöst — Bau noch nicht begonnen.**

- **Sourcing/Lizenz (Blocker 1+2):** entschieden →
  [ADR 0017](../../adr/0017-tpc-benchmark-workload-sourcing.md) **accepted**: Tool
  **A — DuckDB-`tpch`** (LTS 1.4.5, Core-Extension MIT, Generator-Pin statt Dump).
- **Normierte Mess-Umgebung (Blocker 3):** **entschieden + ratifiziert** →
  [ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md) **accepted**
  (Container-Caps **2 CPU/4 GB** + Acceptance-Tier + Kalibrierungs-Guard; ratifizierte
  Parameter: `diff-planner` als Kalibrier-Op ±25 %, Acceptance-Mess-Vertrag K=1/M=3 auf
  Median, designierter `perf-acceptance.yml`-Nightly-Runner). **Damit sind die harten
  4c-/4d-Budgets entsperrt.**

**Hier weitermachen — Decision-Strang + 4a-Sourcing abgeschlossen, es bleibt der Round-Trip + die Messung:**

- **4a — ERLEDIGT** ([done/tpc-4a-sourcing-slice.md](../done/tpc-4a-sourcing-slice.md),
  Commit `2dd3f56e`): gepinnter DuckDB-`tpch`-Generator erzeugt die TPC-H-Workload
  offline + reproduzierbar (`make sample-db-tpch-gen`, kein Dump im Repo). Nächster Bau:
  **4b** (TPC-H in eine Quell-DB laden + reverse/validate/generate/transfer —
  Korrektheit vor Messung).
- **4d** (synthetisches DDL-1000-Gate) + **4c** (Volumen-Abnahme): Spec/Infrastruktur
  baubar; die **harten Acceptance-Gates** assertieren nun gemäß ADR 0018 (Referenz-Caps
  + Kalibrierungs-Guard) auf dem designierten Nightly-Runner.

## Ziel

Realistische, großvolumige **Benchmark-Workloads** (TPC-H und/oder TPC-DS) gegen
das echte CLI fahren und die **formalen Lastenheft-Abnahmen** belegen — über die
heutige Smoke-Perf-Infra hinaus.

## LF-Abnahmekriterien — exakter Wortlaut (Review-korrigiert)

Das Lastenheft trennt zwei Ebenen; der ältere „LF 8.1/8.2"-Sammelbegriff verschleift sie:

- **LF 8.1 — Funktionale Tests** (Verlustfreiheit, **kein** Zeitbudget). Relevant:
  „Export und Re-Import von **mindestens 1 Million Datensätzen ohne Datenverlust**".
  **Verifikationsmethode (LF 8.5 Datenintegritätstests, gebunden an LN-009/010/011):**
  das Lastenheft schreibt einen **Byte-für-Byte-Vergleich (SHA-256-Hash)** der 1-Mio-
  Export/Import-Daten vor — strenger als Phase 3 (nur Zeilen-Parität + `SUM(salary)`-
  Checksumme). 4c muss diese Methode übernehmen (oder ein Surrogat ausdrücklich
  begründen). LF 8.5 nennt zusätzlich NULL-/Unicode-/BLOB-Integrität — eigener Scope.
- **LF 8.2 — Performance-Tests** (Zeit/Skalierung). Relevant u. a.:
  „DDL-Generierung für 100 Tabellen in unter 5 s" (= **LN-001**), „Export von 1 Mio in
  unter **100 s**", „Import von 1 Mio in unter **200 s**" (entsprechen den Durchsatz-
  Anforderungen **LN-002** ≥ 10 000 Sätze/s → 1 Mio/100 s bzw. **LN-003** ≥ 5 000
  Sätze/s → 1 Mio/200 s), „DDL-Generierung für 1.000 Tabellen in unter **30 s**"
  (= nummerierte Anforderung **LN-004**, „Schemas mit >1.000 Tabellen"; auch in den
  Abnahmekriterien), „Export von 10 Mio ohne Out-of-Memory", „Checkpoint/Resume:
  erfolgreicher Wiederanlauf nach simuliertem Abbruch bei 50 %".

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
**4×n + 1** Objekte; die KDoc in `LargeSchemaScaleSpec.kt` ist seit `6040d763`
entsprechend korrigiert — die frühere „5×n"-Angabe zählte fälschlich `n` Funktionen)
und misst den **Diff-Planner + PG-Diff-Renderer** (`SchemaComparator` → `DiffPlanner`
→ `PostgresDiffDdlGenerator`), nicht reine „1000-Tabellen-DDL-Generierung". Vor der
LF-8.2-Abnahme ist festzulegen, ob dieser 4×n-Diff-Pfad gilt (großzügig) oder ein
schmaler reiner Generate-Pfad für genau 1000 Tabellen gebraucht wird.

## Grundentscheidungen

**Entschieden (ADR 0017, accepted — Sourcing + Lizenz).** Der Pin-Vertrag aus ADR 0014
(Commit-SHA/Release + SHA256, kein Dump im Repo) lässt sich auf **generierte** TPC-Daten
nicht 1:1 anwenden — ein Generator-Output ist über Versionen/Plattformen nicht byte-stabil.
[ADR 0017](../../adr/0017-tpc-benchmark-workload-sourcing.md) löst das per **Generator-Tool
+ Config pinnen statt statischem Dump** (analog gepinntem `gdal`-Loader), Tool **A —
DuckDB-`tpch`** (LTS 1.4.5, Core-Extension MIT, SF-konfigurierbar); Verlustfreiheit wird
**per-Lauf** verifiziert (LF 8.5), nicht gegen einen Baseline-Dump. Lizenz unkritisch:
MIT-Extension, lokal generiert, nichts eingecheckt/publiziert → keine TPC-EULA-/Branding-
Bindung. B (HammerDB GPL-als-Container) / C (schlanke SQL-Generierung) bleiben dokumentierte
Fallbacks. Vollständige Begründung + verworfene Optionen: [ADR 0017](../../adr/0017-tpc-benchmark-workload-sourcing.md).

**Noch offen (in 4a/4e festzunageln):**

1. **Workload:** TPC-H (8 Tabellen) zuerst; TPC-DS (24) optional als zweiter Sub-Slice (4e).
2. **Scale-Factor:** Smoke (SF ~0.01) für CI-Funktionsnachweis vs. Abnahme (SF 1 ≈
   1 GB / ~6 Mio `lineitem`-Zeilen) für die Volumen-Schwellen.

## Scope-Skizze (Sub-Slices)

- **4a — Sourcing + Pin-Vertrag — ERLEDIGT**
   ([done/tpc-4a-sourcing-slice.md](../done/tpc-4a-sourcing-slice.md)). Tool-Wahl +
   Pin-Mechanik + Lizenz **ratifiziert in
   [ADR 0017](../../adr/0017-tpc-benchmark-workload-sourcing.md)** (accepted; A
   DuckDB-`tpch`, LTS 1.4.5, MIT). Umgesetzt: gepinntes DuckDB-CLI v1.4.5 **+
   mitgepinnte `tpch`-Extension** (4a-Befund: nicht im CLI gebündelt → ADR 0017 Punkt 2
   korrigiert) in digest-gepinntem `debian-slim`-Loader, SF-Config + `dbgen`,
   `network_mode: none` (hermetisch), im Kandidaten-Katalog dokumentiert. Generator-Tool
   + Config gepinnt, kein Dump im Repo.
- **4b — Schema-Round-Trip-Korrektheit.** TPC-H-Schema reverse/validate/generate/
   transfer (wie Phase 1/2) — Korrektheit vor Messung.
- **4c — LF 8.1 + 8.2 Volumen-Abnahme (gemessen).** 1-Mio-(bzw. SF-1-)Export/Import:
   Verlustfreiheit (LF 8.1) **per LF-8.5-Methode** — **Byte-für-Byte-/SHA-256-Vergleich**
   der Export/Import-Daten (NICHT nur Zeilen-Parität + Checksumme wie Phase 3).
   **Wovon der SHA-256 gebildet wird, hängt am Dialekt-Paar und ist hier festzunageln:**
   bei **Gleich-Dialekt-Round-Trip** (z. B. PG→PG) trägt der literale Byte-für-Byte-Hash
   der exportierten Daten (Export → Import → Re-Export, Hash-Gleichheit). Bei
   **Cross-Dialect** ist byte-identisch Quelle↔Ziel *per Design unmöglich* (die Typ-/
   Encoding-Normierung ist genau das Produkt) → dort ein **definiertes kanonisches
   Surrogat** (normalisierte Wert-Serialisierung vor dem Hash) ausdrücklich begründen.
   (Abgleich: ADR 0017 nennt „SHA-256 Quelle↔Ziel" verkürzt für „per-Lauf statt gegen
   Dump" — die *präzise* Methode pinnt dieses 4c, nicht die ADR.) **Plus** getrennte
   Zeit-Budgets (LF 8.2: Export < 100 s, Import < 200 s); exakter Pfad festnageln
   (`data transfer --chunk-size` **vs.** `data export`→`import --resume`); **plus**
   Resume nach Abbruch **bei ~50 %** (LF-8.2-Wortlaut; Phase 3 bricht heute beim ersten
   Checkpoint ab, also < 50 %). Doku-Sync: `performance-benchmarks.md` auf
   „Verlustfreiheit durch Phase 3 plausibilisiert, gemessene Abnahme offen" nachziehen
   (der Umbrella-Plan ist bereits angeglichen).
- **4d — LF 8.2 / LN-004 DDL-1000-Gate aktivieren/stabilisieren.** Das **bestehende**
   30-s-Baseline-Gate (= **LN-004** „>1.000 Tabellen … unter 30 s") verlässlich grün
   stellen (nicht neu einführen); 4×n-Diff-vs-reiner-DDL-Pfad entscheiden. (Die früher
   hier gelistete „5×n"-KDoc-Korrektur ist **erledigt**, Commit `6040d763`.) Doku-Sync:
   die Scale-Tabelle in `performance-benchmarks.md` führt bislang nur die Smoke-Budgets —
   beim Aktivieren des harten Gates die Baseline-Spalte (`renderBaselineMs`) nachtragen
   und den Ausblick (Abnahme-Lücke) von „gemessene Abnahme steht noch aus" auf
   „30-s-Baseline kodiert, hart nur im Acceptance-Tier" schärfen.
   **Das LF-8.2-Kriterium „100 Tabellen < 5 s" (= LN-001) ist bereits gedeckt** — die
   existierende N=100-Baseline (`renderBaselineMs=2_000` = 2 s) liegt darunter; nur als
   Gate bestätigen, kein neuer Bau. Synthetisch, **nicht** TPC — ggf. eigener Mini-Slice.
- **4e — (optional) TPC-DS** als zweite, komplexere Workload.

Jeder Sub-Slice (4a–4e) graduiert bei Aktivierung als **eigener** `in-progress/`-Slice
mit eigener DoD (Modul 5) — dieser `next/`-Entwurf ist der Umbrella, nicht eine
einzelne lieferbare Einheit.

**Reihenfolge-Gate:** 4c/4d (harte Zeit-Budgets) dürfen **erst nach** Festlegung der
normierten Mess-Umgebung (siehe Vorbedingungen) greifen — sonst sind die Budgets
auf geteilter CI flaky oder müssen so locker sein, dass sie nichts abnehmen. (Diese
Vorbedingung ist mit ADR 0018 `accepted` nun erfüllt.)

## Vorbedingungen

- Phase-0–3-Harness-Muster + `docker-perf`-Infra — **vorhanden**.
- **Normierte Mess-Umgebung (Blocker für harte Budgets, 4c/4d).** `performance-
  benchmarks.md` hält fest, dass eine definierte Hardware-/Container-Umgebung fehlt
  und geteilte CI-Runner nur diagnostisch geprüft werden. Harte LF-8.2-Budgets
  brauchen ein fixiertes Runner-/Container-Sizing + Warmup-/Iterations-Vertrag —
  **vor** dem Versprechen harter Budgets festzulegen. **Ratifiziert in
  [ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md)** (accepted):
  Container-Caps-Referenz **2 CPU/4 GB** + Acceptance-Tier + Kalibrierungs-Guard
  (`diff-planner` ±25 %) + Mess-Vertrag K=1/M=3 + designierter `perf-acceptance.yml`-
  Nightly-Runner — **erfüllt**.
- Sourcing-/Pin-/Lizenz-Entscheidung (4a) — **entschieden** ([ADR 0017](../../adr/0017-tpc-benchmark-workload-sourcing.md)
  accepted: A DuckDB-`tpch`, LTS 1.4.5, MIT). Bleibt: Umsetzung in 4a.

## Akzeptanzkriterien

- **4b:** TPC-H-Schema round-trippt grün (Parität + erwartete Notes gepinnt).
- **4c:** Verlustfreiheit (LF 8.1) **gemessen** belegt **per LF-8.5-Methode**
  (Byte-für-Byte-/SHA-256-Vergleich der Export/Import-Daten, nicht nur Zeilen-Parität;
  Methode je Dialekt-Paar — Gleich-Dialekt literal, Cross-Dialect kanonisches Surrogat);
  Export-/Import-Zeit getrennt unter den LF-8.2-Budgets (< 100 s / < 200 s) in der
  normierten Umgebung; Resume nach Abbruch bei ~50 %; `performance-benchmarks.md` aktualisiert.
- **4d:** N=1000-DDL < 30 s als hartes Gate (**LF 8.2 / LN-004**) in der normierten
  Umgebung; N=100-DDL < 5 s (LF 8.2) als Gate bestätigt (bereits durch die N=100-Baseline gedeckt).
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
