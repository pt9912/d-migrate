# Slice: TPC Sub-Slice 4b — Schema-Round-Trip-Korrektheit (TPC-H)

> Dokumenttyp: abgeschlossener Slice (graduiert aus dem Umbrella
> [`../next/tpc-performance-slice.md`](../next/tpc-performance-slice.md), Sub-Slice 4b).
> Baut auf [4a](tpc-4a-sourcing-slice.md) (gepinnter DuckDB-`tpch`-Generator).
> ADR: [0017](../../adr/0017-tpc-benchmark-workload-sourcing.md) ·
> [0014](../../adr/0014-sample-db-harness-fetch-and-compose.md).
> Status: **abgeschlossen + live verifiziert (2026-06-23)** — `make sample-db-tpch-smoke`
> grün (PG→PG, 8 Tabellen, DECIMAL-Checksumme identisch). Folge: 4c/4d (gemessene Abnahme).

## Ziel (Slice-Grenze)

**Korrektheit vor der Messung:** die TPC-H-Workload (8 Tabellen) round-trippt verlustfrei
durch den vollen d-migrate-Pfad — `reverse → validate → generate → transfer` (wie Phase
1/2). Damit ist sichergestellt, dass 4c/4d (gemessene Volumen-/DDL-Abnahme) auf einem
korrekten Fundament misst, nicht auf einem kaputten Schema-Transfer.

## Scope-Entscheidung: FK-/PK-frei, nichts TPC-spezifisches eingecheckt

Die Quelle ist die **runtime-generierte** DuckDB-`schema.sql` (aus 4a) + die CSVs — **kein
TPC-Artefakt im Repo** (ADR 0017: „nichts eingecheckt"). Die DuckDB-`dbgen`-Ausgabe trägt
**keine** PK/FK; das TPC-H-Schlüsselgefüge nachzurüsten hieße, ein **eingechecktes
TPC-Schema-Artefakt** (Schlüsselspalten/Relationen) zu vendoren — bewusst **nicht** getan.
Constraint-/programmability-reiche Round-Trips sind bereits durch **Phase 1/2**
(Pagila 22 Tabellen mit FK/Views/Routinen/Trigger, Sakila Cross-Dialect) abgedeckt. 4b
trägt den **eigenständigen** Beleg: die TPC-H-**Typen** (`BIGINT`/`INTEGER`/`VARCHAR`/
`DECIMAL(15,2)`/`DATE`) + die 8-Tabellen-Form + verlustfreier **DECIMAL-Werttransfer**.

## Mechanik

- **Quell-Load:** die DuckDB-`schema.sql` ist **PG-kompatibel** (`CREATE TABLE t(... DECIMAL(15,2)
  NOT NULL ...);;` — das `;;`-Leerstatement toleriert psql) → direkt nach `tpch` geladen.
  CSV-Load via `\copy <t> FROM STDIN WITH (FORMAT csv, HEADER true)` (die DuckDB-`load.sql`
  ist DuckDB-COPY-Dialekt; die CSVs werden vom Host in den psql-stdin gepiped → **kein
  Mount nötig**). Tabellennamen werden aus den generierten `*.csv`-Dateinamen abgeleitet
  (nichts hartkodiert).
- **Aliase** (`.d-migrate.yaml`): `tpch_pg_src` / `tpch_pg_target` (postgres-Service).
- **Round-Trip** (`examples/sample-db/scripts/smoke-tpch.sh`): reverse → validate (0 Errors)
  → generate `--target postgresql --deterministic` → Ziel-DDL anwenden → `data transfer`.

## Live-Beleg (SF=0.01, PG→PG)

`make sample-db-tpch-smoke` grün:
- Load: 8 Tabellen, `lineitem` 60175 Zeilen.
- reverse: alle 8 Tabellennamen + `DECIMAL` + `DATE` erfasst.
- validate: **0 Errors** (`Validation passed`).
- generate: **8 `CREATE TABLE`, 0 Notes** (kein Typ-Downgrade, keine Degradierung).
- transfer + Parität: **8 Tabellen zeilen-identisch** Quelle == Ziel.
- **DECIMAL-Fidelity:** `sum(l_extendedprice)` = **2152189760.47** in Quelle **und** Ziel
  identisch (fängt stillen Präzisions-/Rundungsverlust).

## Definition of Done (Modul 5)

- [x] TPC-H lädt aus der runtime-generierten DuckDB-`schema.sql` + CSVs in eine PG-Quelle
      (nichts TPC-spezifisches im Repo).
- [x] Voller Round-Trip grün: reverse (8 Tabellen + Typen) → validate (0 Errors) →
      generate (8 `CREATE TABLE`, 0 Notes) → transfer.
- [x] Parität: 8 Tabellen zeilen-identisch + `DECIMAL`-Wert-Checksumme identisch.
- [x] `make sample-db-tpch-smoke` (opt-in), erwartete Form in `expected/tpch.md` gepinnt.
- [x] Opt-in/nightly, **nicht** im PR-Gate; `make docs-check` grün.

## Nicht in 4b (Folge-Slices)

- **4c/4d:** gemessene Volumen-/DDL-Abnahme (Export/Import-Zeit, DDL-1000 < 30 s) unter der
  normierten Mess-Umgebung ([ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md), accepted).
- **Cross-Dialect** TPC-H (PG→MySQL/SQLite) + **PK/FK-Anreicherung** sind möglich, aber
  außerhalb dieser Korrektheits-Slice-Grenze (PK/FK bräuchte ein eingechecktes
  TPC-Schema-Artefakt — siehe Scope-Entscheidung).
