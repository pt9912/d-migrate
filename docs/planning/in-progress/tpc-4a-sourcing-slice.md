# Slice: TPC Sub-Slice 4a — Sourcing + Pin-Vertrag (DuckDB-`tpch`-Generator)

> Dokumenttyp: in-progress-Slice (graduiert aus dem Umbrella
> [`../next/tpc-performance-slice.md`](../next/tpc-performance-slice.md), Sub-Slice 4a).
> ADR: [0017](../../adr/0017-tpc-benchmark-workload-sourcing.md) (accepted, Tool A
> DuckDB-`tpch`) · [0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)
> (Fetch/Compose-Mechanik, Pin-Disziplin).
> Status: **in Arbeit** — Pins verifiziert + Generierung live belegt; Verdrahtung folgt.

## Ziel (Slice-Grenze)

Die **realistische TPC-H-Workload reproduzierbar + on-demand** verfügbar machen — der
Sourcing-Teil. **Generator-Tool + Config gepinnt, kein Dump im Repo** (ADR 0017): ein
gepinntes DuckDB-CLI erzeugt das echte TPC-H-Schema (8 Tabellen) + Daten bei
konfigurierbarem Scale-Factor **offline**. Das Laden in eine Quell-DB + der
Reverse/Generate/Transfer-Round-Trip sind **4b** (nicht dieser Slice).

## Träger-Entscheidung (in 4a festzunageln — entschieden)

ADR 0017 ließ das konkrete Trägerformat offen („Digest-gepinntes Image *oder* CLI-Release
`v1.4.5` + SHA256"). **Gewählt: gepinntes CLI-Binary + Extension, SHA256-verifiziert**,
ausgeführt in einem digest-gepinnten Minimal-Image — exakt analog zur bestehenden
`fetch-dumps.sh`-Disziplin (Artefakt per SHA256 gepinnt, nichts im Repo). Vorteil
gegenüber einem Drittanbieter-DuckDB-Image: maximale Konsistenz mit der vorhandenen
Pin-Mechanik, keine Abhängigkeit von einem fremden Image-Tag.

**Verifizierte Pins (2026-06-23, live geprüft):**

| Artefakt | Quelle | SHA256 |
|----------|--------|--------|
| DuckDB CLI v1.4.5 (linux-amd64) | `github.com/duckdb/duckdb/releases/download/v1.4.5/duckdb_cli-linux-amd64.zip` | `ff4ef9ec59fe3e1a1f3dd1004c6218d1fd59c0533c185c968c4403fd0240d02b` |
| `tpch`-Extension v1.4.5/linux_amd64 (.gz) | `extensions.duckdb.org/v1.4.5/linux_amd64/tpch.duckdb_extension.gz` | `56256ba742be9b2800c89ffedb4409946aaa2514d95e07288bb5cf6b88e45014` |
| Runner-Image `debian:bookworm-slim` | Docker Hub (digest-gepinnt) | `sha256:96e378d7e6531ac9a15ad505478fcc2e69f371b10f5cdf87857c4b8188404716` |

## Befund: ADR-0017-Korrektur — `tpch` ist NICHT im CLI-Binary gebündelt

ADR 0017 (Ratifizierte Entscheidung, Punkt 2) nahm an: „Extension-Version: keine
separate. `tpch` ist eine Core-Extension (mit DuckDB ausgeliefert/autoloaded) — die
gepinnte DuckDB-Version fixiert die Extension implizit." **Das stimmt nicht für die
CLI-Distribution:** das v1.4.5-CLI-Binary enthält `tpch` nicht, sondern lädt es beim
ersten `CALL dbgen(...)` von `extensions.duckdb.org` nach (scheitert unter
`--network none`). **Konsequenz:** der Pin muss die Extension **mitumfassen**. Mit
gepinnter `tpch.duckdb_extension` + `LOAD '<datei>'` läuft die Generierung **voll
offline** (`--network none`-verifiziert). ADR 0017 Punkt 2 ist entsprechend korrigiert.

## Mechanik

1. **Fetch (`examples/sample-db/scripts/fetch-dumps.sh`, opt-in `FETCH_TPCH=1`):**
   CLI-Zip + Extension-`.gz` per URL+SHA256 in `.cache/tpch-tool/` holen, CLI entpacken,
   Extension gunzippen — exakt das `fetch_one`-Muster (idempotenter Cache-Hit bei SHA256).
2. **Compose-Loader (`duckdb`-Service, `profiles: ["tools"]`):** digest-gepinntes
   `debian:bookworm-slim`, mountet `.` nach `/work` — analog `gdal`-Loader (Phase 5).
3. **Generierung (`examples/sample-db/scripts/tpch-generate.sh`):** ruft den gepinnten CLI im Loader
   offline auf — `SET autoinstall/autoload_known_extensions=false; LOAD '<ext>';
   CALL dbgen(sf=${SF}); EXPORT DATABASE '/work/.cache/tpch' (FORMAT CSV)`. Erzeugt
   `schema.sql` + `load.sql` + 8 CSVs (customer/lineitem/nation/orders/part/partsupp/
   region/supplier).
4. **Make-Target (`sample-db-tpch-gen`, opt-in):** Fetch + Generierung bei `SF`
   (Default 0.01) + Form-Assertion (8 Tabellen, `lineitem`-Zeilenzahl).

## Live-Beleg (SF=0.01, offline)

`CALL dbgen(sf=0.01)` → **8 Tabellen**, `lineitem` = **60175 Zeilen** (deterministisch),
`EXPORT DATABASE` → 8 CSVs + schema.sql + load.sql. DDL-Typen sauber
PG/MySQL-abbildbar (`BIGINT`/`INTEGER`/`VARCHAR`/`DECIMAL(15,2)`/`DATE`) — Vorarbeit für 4b.

## Definition of Done (Modul 5)

- [ ] `FETCH_TPCH=1 make sample-db-tpch-gen` holt CLI+Extension SHA256-verifiziert
      (Cache-Hit idempotent), nichts davon im Repo (`.cache/` gitignored).
- [ ] Generierung läuft **offline** (`--network none` im Loader) und erzeugt das
      TPC-H-Schema (8 Tabellen) + Daten bei konfigurierbarem `SF`.
- [ ] Form-Assertion grün: 8 Tabellen, `lineitem`-Zeilen == erwarteter SF-Wert
      (SF=0.01 → 60175).
- [ ] Im Kandidaten-Katalog (`docs/planning/open/test-database-candidates.md`)
      dokumentiert (Pins, SF, offline-Mechanik).
- [ ] ADR 0017 Punkt 2 (Extension-Pin) korrigiert.
- [ ] Opt-in/nightly, **nicht** im PR-Gate; `make docs-check` grün.

## Nicht in 4a (Folge-Slices)

- **4b:** TPC-H in eine Quell-DB (PostgreSQL) laden + reverse/validate/generate/transfer
  (Korrektheit). Die DuckDB-`schema.sql` ist DuckDB-Dialekt → 4b entscheidet
  Lade-/DDL-Pfad (kanonisches TPC-H-PG-Schema vs. abgeleitet).
- **4c/4d:** gemessene Volumen-/DDL-Abnahme unter der normierten Mess-Umgebung
  ([ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md), accepted).
