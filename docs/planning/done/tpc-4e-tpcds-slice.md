# Slice: TPC Sub-Slice 4e — TPC-DS-Workload (Round-Trip-Korrektheit)

> Dokumenttyp: Done-Plan (optionaler Sub-Slice des Umbrellas
> [`../done/tpc-performance-slice.md`](../done/tpc-performance-slice.md), Phase 4).
> ADR: [0017](../../adr/0017-tpc-benchmark-workload-sourcing.md) (Sourcing/Lizenz —
> Mechanik auf die `tpcds`-Extension angewandt), [0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)
> (Harness). Aufbauend auf [4a](tpc-4a-sourcing-slice.md)/[4b](tpc-4b-roundtrip-slice.md).
> **Status: GELIEFERT + live-verifiziert (2026-06-25).** `make sample-db-tpcds-smoke` grün.

## Ziel

Die **zweite, komplexere** Benchmark-Workload (TPC-DS, **24 Tabellen**) gegen das echte
CLI fahren — Schema-Round-Trip-**Korrektheit** analog 4b (TPC-H), aber mit deutlich
breiterem Tabellen-/Typ-Spektrum. Das exerziert den breitesten realistischen Schema-Umfang
des Harness (Sterne-Schema: 7 Fakten- + 17 Dimensionstabellen).

## Was geliefert wurde

**Sourcing (analog 4a).** Gepinntes **DuckDB-CLI v1.4.5** + **`tpcds`-Core-Extension**
(MIT, separat SHA256-gepinnt — `cda2f50…`, `fetch-dumps.sh FETCH_TPCDS=1`). Wie bei tpch ist
die Extension **nicht** im CLI gebündelt → mitgepinnt + offline aus Datei `LOAD`-ed (Loader
`network_mode: none`, hermetisch). `CALL dsdgen(sf=SF)` + `EXPORT DATABASE` →
`schema.sql` + `load.sql` + **24 CSVs** nach `.cache/tpcds/` (gitignored, kein Dump im Repo).
`make sample-db-tpcds-gen`; SF konfigurierbar (Default 0.01). Form-Check-Pin (SF=0.01,
deterministisch): `store_sales` = **28810 Zeilen**.

**Round-Trip-Korrektheit PG→PG (analog 4b).** `make sample-db-tpcds-smoke`
(`examples/sample-db/scripts/smoke-tpcds.sh`): Generierung → 24 CSVs in frische `tpcds`-DB laden →
`schema reverse` → `validate` → `generate --target postgresql` → Zielschema `tpcds_target`
→ `data transfer` → Parität.

| Schritt | Ergebnis (live, 2026-06-25) |
|---|---|
| Quell-Load | 24 Tabellen; DuckDB-`schema.sql` PG-kompatibel (`;;` toleriert), CSV via `\copy FROM STDIN`; NULL = leeres CSV-Feld → PG-NULL |
| reverse | alle 24 Tabellen + `DECIMAL` + `DATE` erfasst |
| validate | **0 Errors** (`Validation passed`) |
| generate (`--target postgresql`) | **24 `CREATE TABLE`, 0 Notes** |
| transfer + Parität | **24 Tabellen zeilen-identisch** Quelle == Ziel |
| DECIMAL-Fidelity | `sum(store_sales.ss_net_paid)` identisch (SF=0.01: **47475151.75**) |

## Nicht offensichtliche Notizen (gepinnt)

- **Kein TPC-Artefakt im Repo.** Schema **und** Daten kommen aus dem gepinnten Generator zur
  Laufzeit; `.cache/tpcds*` gitignored. Hält ADR 0017 ein (nichts eingecheckt/publiziert).
- **ADR 0017-Mechanik auf `tpcds` angewandt.** ADR 0017 entschied Tool A (DuckDB) für TPC-H;
  TPC-DS nutzt dieselbe Tool-Familie/Lizenz/Pin-Mechanik (eigene MIT-Core-Extension) — als
  Nachtrag zu ADR 0017 vermerkt, keine neue Grundsatzentscheidung.
- **Bewusst FK-/PK-frei** (wie 4b). `dsdgen`-EXPORT trägt keine Constraints (0 PK/FK/NOT NULL
  geprüft); das TPC-DS-Schlüsselgefüge nachzurüsten wäre ein eingechecktes TPC-Artefakt.
  Constraint-/programmability-reiche Round-Trips deckt Phase 1/2 (Pagila/Sakila) ab; 4e trägt
  die 24-Tabellen-Form + TPC-DS-Typen (BIGINT/VARCHAR/DATE/DECIMAL(p,s)) + Werttransfer.
- **Generator wiederverwendet die tpch-CLI-Linie** (identisches v1.4.5-Binary, SHA-gepinnt);
  nur die Extension unterscheidet sich. Eigenes `tpcds-tool/`-Verzeichnis (self-contained).

## Abgrenzung / Nicht-Ziel

- **Volumen-/Zeit-Abnahme** (LF 8.2-Budgets, Caps, Kalibrier-Guard) ist **4c** (TPC-H);
  4e ist **Korrektheit** der komplexeren Workload, nicht eine zweite Mess-Abnahme.
- TPC-Query-Performance (d-migrate transferiert Schema/Daten, keine Query-Engine).
- Wettbewerbs-/Audit-taugliche TPC-Zahlen.

## Bezug

- Umbrella: [`../done/tpc-performance-slice.md`](../done/tpc-performance-slice.md) (Phase 4).
- Sourcing/Lizenz: [ADR 0017](../../adr/0017-tpc-benchmark-workload-sourcing.md).
- Erwartungs-Pin: [`../../../examples/sample-db/expected/tpcds.md`](../../../examples/sample-db/expected/tpcds.md).
