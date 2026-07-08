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

## Volumen-Abnahme (4c Mess-Kern, `smoke-tpch-perf.sh`)

Datei-basierter `data export` → `data import` (≥ 1 Mio, SF=0.2 default → ~1,73 Mio Zeilen)
unter Container-Caps **2 CPU/4 GB** (`dmigrate-capped`).

| Kriterium | Erwartung | Modus |
|---|---|---|
| Verlustfreiheit (LF 8.1/8.5) | **kanonischer Inhalts-SHA-256** je Tabelle Quelle == Re-Import (alle 8) | **HART** (host-unabhängig) |
| Export-Durchsatz (LF 8.2/LN-002) | ≥ 10 000 Sätze/s (= 1 Mio < 100 s) | diagnostisch (hart nur `PERF_GATE=true` + Runner) |
| Import-Durchsatz (LF 8.2/LN-003) | ≥ 5 000 Sätze/s (= 1 Mio < 200 s) | diagnostisch |
| Resume (LF 8.2) | Mid-Stream-Abbruch + `--resume` → vollständiger, verlustfreier Export | **HART** |

### Nicht offensichtliche Notizen (4c, gepinnt)

- **Kanonischer Hash statt roher Datei-Byte-Vergleich.** Der LF-8.5-„Byte-für-Byte"-
  Vergleich auf der **rohen Exportdatei** ist untauglich — der Round-Trip ist nicht
  byte-stabil (`schema reverse` alphabetisiert die Spalten, Ordinalreihenfolge geht
  verloren; siehe [`reverse-column-ordinal-order.md`](../../../docs/planning/open/reverse-column-ordinal-order.md)).
  Der kanonische Hash (spalten-namens-geordnet + zeilen-sortiert) ist order-invariant
  + zellgenau und realisiert die LF-8.5-Absicht faithful.
- **Resume-Abbruchpunkt host-abhängig.** Der Smoke zielt auf ~50 %, der erste
  resumebare Checkpoint erscheint aber (Checkpoint-Flush-Latenz) auf schnellen Hosts
  erst später (~70 %). Der tatsächliche %-Wert wird berichtet; ein Band [25 %, 90 %]
  belegt „echt mid-stream". Der Beleg ist Resume-Vollständigkeit, nicht der exakte Punkt.
- **Kalibrier-Guard (Teil 2, gebaut).** Vor den Durchsatz-Gates misst der Smoke die
  Host-Geschwindigkeit über den **diff-planner-Hotpath via CLI** (`schema generate` auf
  `calib-schema.yaml`, 5× Median unter Caps; ADR 0018-Ergänzung). Ohne gepinnten
  `CALIB_REFERENCE_MS` → **Bootstrap** (meldet den Median, bleibt diagnostisch). Mit
  Referenz: Drift ≤ 25 % → in band → Hart-Gate möglich; Drift > 25 % → **Off-Spec →
  Rückfall auf diagnostisch** (kein False-Fail). Das harte Durchsatz-Gate greift nur bei
  `PERF_GATE=true` **UND** host-in-band.
- **Operativer Rest (kein Code):** einen designierten Nightly-Runner festlegen +
  `CALIB_REFERENCE_MS` darauf pinnen (aus einem Bootstrap-Lauf) — dann ist
  `perf-acceptance.yml` hart. Verlustfreiheit + Resume sind ohnehin host-unabhängig hart.
