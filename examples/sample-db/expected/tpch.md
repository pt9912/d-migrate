# TPC-H-Harness — gepinnte Erwartungen (Phase 4: 4a/4b)

> Gepinntes Erwartungs-/Verhaltensdokument für `scripts/smoke-tpch.sh` (4b) und
> `scripts/tpch-generate.sh` (4a). Anders als die Baseline-Compare-Phasen (Pagila/
> Sakila) prüft der TPC-H-Smoke **wertbasiert** (Zeilen-Parität + DECIMAL-Checksumme),
> nicht per Diff gegen einen DDL-Dump — die Quelle ist generiert (kein fixer Dump im
> Repo, ADR 0017).

## Sourcing (4a)

- Generator: gepinntes **DuckDB-CLI v1.4.5** + **`tpch`-Extension** v1.4.5/linux_amd64,
  beide SHA256-gepinnt (`fetch-dumps.sh`, `FETCH_TPCH=1`). Die Extension ist **nicht**
  im CLI gebündelt → mitgepinnt + offline aus Datei `LOAD`-ed (Loader `network_mode: none`).
- `CALL dbgen(sf=SF)` + `EXPORT DATABASE` → `schema.sql` + `load.sql` + **8 CSVs**
  (customer, lineitem, nation, orders, part, partsupp, region, supplier).
- **SF=0.01 (Default):** `lineitem` = **60175 Zeilen** (deterministisch — gepinnter
  Form-Check in `tpch-generate.sh`).

## Round-Trip-Korrektheit (4b, PG→PG)

| Schritt | Erwartung |
|---|---|
| Quell-Load | 8 Tabellen; DuckDB-`schema.sql` ist PG-kompatibel (`;;` toleriert), CSV via `\copy FROM STDIN` |
| reverse | alle 8 Tabellen + `DECIMAL` + `DATE` erfasst |
| validate | **0 Errors** (`Validation passed`) |
| generate (`--target postgresql`) | **8 `CREATE TABLE`, 0 Notes** |
| transfer + Parität | 8 Tabellen **zeilen-identisch** Quelle == Ziel |
| DECIMAL-Fidelity | `sum(l_extendedprice)` identisch (SF=0.01: **2152189760.47**) |

## Nicht offensichtliche Notizen (gepinnt)

- **Kein TPC-Artefakt im Repo.** Schema **und** Daten kommen aus dem gepinnten
  Generator zur Laufzeit; `.cache/tpch*` ist gitignored. Das hält ADR 0017
  („nichts eingecheckt/publiziert") ein.
- **Bewusst FK-/PK-frei.** `dbgen`-EXPORT trägt keine Constraints; das TPC-H-
  Schlüsselgefüge nachzurüsten wäre ein eingechecktes TPC-Schema-Artefakt. Constraint-/
  programmability-reiche Round-Trips deckt **Phase 1/2** (Pagila/Sakila) ab; 4b trägt
  TPC-H-**Typen** + 8-Tabellen-Form + **DECIMAL-Werttransfer**.
- **DuckDB-`load.sql` ist NICHT PG-tauglich** (DuckDB-COPY-Syntax mit `force_not_null`/
  `quote`); der Smoke nutzt stattdessen postgres `\copy FROM STDIN` und leitet die CSVs
  vom Host in den psql-stdin (kein Volume-Mount).
- **Geltungsbereich:** 4b ist Korrektheit. Die gemessene Volumen-/DDL-Abnahme (Zeit-
  Budgets, DDL-1000 < 30 s) ist 4c/4d unter der normierten Mess-Umgebung (ADR 0018).
