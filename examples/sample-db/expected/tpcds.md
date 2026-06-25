# TPC-DS-Harness — gepinnte Erwartungen (Phase 4: 4e)

> Gepinntes Erwartungs-/Verhaltensdokument für `scripts/smoke-tpcds.sh` und
> `scripts/tpcds-generate.sh` (optionaler Sub-Slice 4e). Wie der TPC-H-Smoke prüft 4e
> **wertbasiert** (Zeilen-Parität + DECIMAL-Checksumme), nicht per Diff gegen einen
> DDL-Dump — die Quelle ist generiert (kein fixer Dump im Repo, ADR 0017).
> Slice: [`../../../docs/planning/done/tpc-4e-tpcds-slice.md`](../../../docs/planning/done/tpc-4e-tpcds-slice.md).

## Sourcing (4e)

- Generator: gepinntes **DuckDB-CLI v1.4.5** + **`tpcds`-Core-Extension** v1.4.5/linux_amd64,
  beide SHA256-gepinnt (`fetch-dumps.sh`, `FETCH_TPCDS=1`). Die Extension ist **nicht** im CLI
  gebündelt → mitgepinnt + offline aus Datei `LOAD`-ed (Loader `network_mode: none`).
- `CALL dsdgen(sf=SF)` + `EXPORT DATABASE` → `schema.sql` + `load.sql` + **24 CSVs**
  (call_center, catalog_page, catalog_returns, catalog_sales, customer, customer_address,
  customer_demographics, date_dim, household_demographics, income_band, inventory, item,
  promotion, reason, ship_mode, store, store_returns, store_sales, time_dim, warehouse,
  web_page, web_returns, web_sales, web_site).
- **SF=0.01 (Default):** `store_sales` = **28810 Zeilen** (deterministisch — gepinnter
  Form-Check in `tpcds-generate.sh`).

## Round-Trip-Korrektheit (4e, PG→PG)

| Schritt | Erwartung |
|---|---|
| Quell-Load | 24 Tabellen; DuckDB-`schema.sql` PG-kompatibel (`;;` toleriert), CSV via `\copy FROM STDIN`; NULL = leeres CSV-Feld → PG-NULL |
| reverse | alle 24 Tabellen + `DECIMAL` + `DATE` erfasst |
| validate | **0 Errors** (`Validation passed`) |
| generate (`--target postgresql`) | **24 `CREATE TABLE`, 0 Notes** |
| transfer + Parität | 24 Tabellen **zeilen-identisch** Quelle == Ziel |
| DECIMAL-Fidelity | `sum(store_sales.ss_net_paid)` identisch (SF=0.01: **47475151.75**) |

## Nicht offensichtliche Notizen (gepinnt)

- **Kein TPC-Artefakt im Repo.** Schema **und** Daten kommen aus dem gepinnten Generator
  zur Laufzeit; `.cache/tpcds*` ist gitignored (ADR 0017: nichts eingecheckt/publiziert).
- **Bewusst FK-/PK-frei.** `dsdgen`-EXPORT trägt keine Constraints (0 PK/FK/NOT NULL); das
  TPC-DS-Schlüsselgefüge nachzurüsten wäre ein eingechecktes TPC-Artefakt. Constraint-reiche
  Round-Trips deckt Phase 1/2 (Pagila/Sakila) ab; 4e trägt die 24-Tabellen-Form + die
  TPC-DS-Typen (BIGINT/VARCHAR/DATE/DECIMAL(p,s)) + DECIMAL-Werttransfer.
- **DuckDB-`load.sql` ist NICHT PG-tauglich** (DuckDB-COPY-Dialekt); der Smoke nutzt
  stattdessen postgres `\copy FROM STDIN` und leitet die CSVs vom Host in den psql-stdin.
- **Geltungsbereich:** 4e ist Korrektheit der komplexeren Workload. Die gemessene Volumen-/
  Zeit-Abnahme (LF 8.2) ist 4c (TPC-H) unter der normierten Mess-Umgebung (ADR 0018).
