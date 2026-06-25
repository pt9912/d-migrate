# Test Database Candidates

> Dieses Dokument sammelt externe Beispieldatenbanken, die sich fuer
> `d-migrate` und angrenzende Integrationsprojekte wie `d-browser` als
> Testgrundlage eignen.
>
> Fokus: realistische Schema-/Datenfaelle fuer Reverse Engineering, Import,
> Export, Streaming, Resume und Integrationsverifikation.

---

## 1. Ziel

Die Kandidaten sollen unterschiedliche Testziele abdecken:

- kleine bis mittlere Demo-Schemata fuer schnelle Smoke- und Regressionstests
- realistische FK-Graphen und typische Domainenmodelle
- groessere Datenmengen fuer Streaming-, Resume- und Lasttests
- spaetere Performance- und Analysefaelle

---

## 2. Priorisierte Startauswahl

### 2.1 Pagila

- URL: `https://raw.githubusercontent.com/neondatabase-labs/postgres-sample-dbs/main/pagila.sql`
- Ursprung: `https://github.com/neondatabase/postgres-sample-dbs`
- Datenbanktyp: PostgreSQL

Warum frueh einsetzen:

- realistische PostgreSQL-Referenz mit FK-Beziehungen und typischer
  Demo-Schema-Komplexitaet
- gut geeignet fuer Reverse Engineering, Schema-Read und erste
  End-to-End-Laeufe
- einfach als einzelne SQL-Datei in Testumgebungen zu laden

Empfohlene Nutzung:

- Smoke-Tests fuer Schema-Import und Export
- erste Integrationsprobe fuer `source-d-migrate`
- Resume-/Streaming-Basis im kleinen bis mittleren Umfang

### 2.2 Sakila

- URL: `https://github.com/jOOQ/sakila`
- Datenbanktyp: MySQL-orientierte Referenz, auch fuer Cross-DB-Vergleiche

Warum frueh einsetzen:

- bekannter Standard fuer relationale Beispieltests
- aehnliche Domaine wie Pagila, dadurch gut fuer Vergleichslaeufe
- hilfreich, um Verhaltensunterschiede zwischen PostgreSQL- und
  MySQL-nahen Setups sichtbar zu machen

Empfohlene Nutzung:

- Kompatibilitaetstests zwischen unterschiedlichen SQL-Dialekten
- Schema-/Daten-Vergleichslaeufe mit Pagila
- Validierung von FK-Graphen, Join-lastigen Strukturen und
  Generator-/Reader-Verhalten

### 2.3 Employees

- URL: `https://github.com/datacharmer/test_db`
- Alternativreferenz: `https://dev.mysql.com/doc/employee/en/employees-installation.html`
- Datenbanktyp: MySQL

Warum frueh, aber nach Pagila und Sakila:

- groesseres, tabellenlastiges Beispiel mit mehr Volumen
- besser geeignet fuer laengere Export-/Import-Strecken als kleine
  Demoschemata
- nuetzlich fuer Streaming-, Chunking- und Resume-Tests

Empfohlene Nutzung:

- Scale-Tests fuer Import/Export
- Resume- und Unterbrechungsfaelle
- Lastnaehere Regressionstests mit relevanterem Datenvolumen

---

### 2.4 Spatial-Samples (Phase 5)

Spatial deckt **Korrektheit** ab (Geometrie-/SRID-/Index-Fidelitaet), nicht
Volumen — daher ein Hybrid aus einem echten PostGIS-Sample (PG-Realismus) und
einem kuratierten WKT-Sample (portabel ueber alle Dialekte).

**(a) PostGIS nyc (echtes Workshop-Sample) — 5a, PostgreSQL/PostGIS.**

- URL: `https://s3.amazonaws.com/s3.cleverelephant.ca/postgis-workshop-2020.zip`
  (offizielles „Introduction to PostGIS"-Workshop-Bundle, postgis.net)
- SHA256: `373cab8cf4004d92bb77fbbe496fe7b683969a3f9b5be19225935287d8497a85`
- Datenbanktyp: PostgreSQL/PostGIS; Format: **Shapefiles**
- Inhalt: `nyc_neighborhoods` (129 MultiPolygons, **EPSG:26918** NAD83/UTM18N),
  `nyc_streets`, `nyc_subway_stations`, `nyc_census_blocks`.
- Lade-Mechanik: `postgis/postgis` hat **kein** `shp2pgsql`/`ogr2ogr` → der
  gepinnte `gdal`-Compose-Service (`ghcr.io/osgeo/gdal:ubuntu-small-3.13.1`)
  laedt die Shapefile per `ogr2ogr` in die PostGIS-Quelle (legt auto. einen
  GIST-Index an).
- Pin/Fetch: `fetch-dumps.sh` (`FETCH_NYC=1`, ~22 MB, opt-in, kein PR-Gate).
- Nutzung: 5a PostGIS-Round-Trip (`smoke-spatial.sh` `[pg-nyc]`): reverse →
  validate → generate `--spatial-profile postgis` → transfer; Paritaet (Zeilen +
  Flaechen-Checksumme), SRID-Erhalt, GIST-Index.

**(b) Kuratiertes WKT-Sample — 5b/5c/5d, alle Dialekte.**

- Quelle: **inline in `examples/sample-db/scripts/smoke-spatial.sh`** (versioniert
  im Repo, kein externer Pin noetig) — Point + Polygon, geografisch (EPSG:4326)
  und projiziert (EPSG:25832/3857/31466, inkl. gedrehter Gauss-Krueger-Achsen).
- Warum kuratiert: ein einzelnes echtes Sample laedt nicht portabel ueber PG +
  MySQL + SpatiaLite; exakt-in-double darstellbare WKT-Koordinaten machen
  Achsen-/SRID-Fehler datenbelegt sichtbar (semantischer Vergleich statt
  `ST_AsText`).
- Nutzung: 5b (MySQL native GEOMETRY+SRID), 5c (Cross-Dialect PG↔MySQL,
  axis-order), 5d (SpatiaLite migrate-Round-Trip).

### 2.5 TPC-H via DuckDB-Generator (Phase 4, 4a)

TPC-H deckt **realistische Join-/Aggregat-Last + Volumen** ab. Statt eines Dumps
wird das **Generator-Tool gepinnt** (ADR 0017) und on-demand offline generiert —
analog zum gepinnten `gdal`-Loader, aber als gepinntes Binary statt fremdem Image.

- Tool: **DuckDB-CLI v1.4.5** (linux-amd64) + **`tpch`-Extension** v1.4.5/linux_amd64.
  Die Extension ist **nicht** im CLI gebuendelt (sonst Laufzeit-Download von
  `extensions.duckdb.org`) → sie wird **mitgepinnt** und aus Datei `LOAD`-ed; erst
  damit ist die Generierung hermetisch (Loader laeuft `network_mode: none`).
- Pins (SHA256): CLI `ff4ef9ec59fe3e1a1f3dd1004c6218d1fd59c0533c185c968c4403fd0240d02b`,
  Extension-`.gz` `56256ba742be9b2800c89ffedb4409946aaa2514d95e07288bb5cf6b88e45014`;
  Runner-Image `debian:bookworm-slim@sha256:96e378d7e6531ac9a15ad505478fcc2e69f371b10f5cdf87857c4b8188404716`.
- Lade-Mechanik: `duckdb`-Compose-Service (digest-gepinntes `debian:bookworm-slim`)
  fuehrt das gepinnte CLI aus: `LOAD <ext>; CALL dbgen(sf=SF); EXPORT DATABASE`
  → `schema.sql` + `load.sql` + 8 CSVs (customer/lineitem/nation/orders/part/
  partsupp/region/supplier) nach `.cache/tpch/` (gitignored, **kein Dump im Repo**).
- Scale-Factor konfigurierbar: SF=0.01 (Default, CI-Funktionsnachweis: `lineitem`
  = 60175 Zeilen) bis SF=1 (~6 Mio `lineitem`, Volumen-Abnahme in 4c).
- Pin/Fetch: `fetch-dumps.sh` (`FETCH_TPCH=1`, ~50 MB, opt-in, kein PR-Gate).
- Nutzung: 4a Sourcing-Beleg (`make sample-db-tpch-gen`); **4b Round-Trip-Korrektheit
  ERLEDIGT** (`make sample-db-tpch-smoke`: PG→PG reverse/validate/generate/transfer,
  8 Tabellen Parität + DECIMAL-Checksumme; FK-/PK-frei). Gemessene Abnahme = 4c/4d.
- Lizenz: DuckDB-`tpch`-Extension **MIT**, lokal generiert, nichts eingecheckt/
  publiziert → keine TPC-EULA-/Branding-Bindung (ADR 0017).

---

## 3. Weitere sinnvolle Kandidaten

### 3.1 PostgreSQL-Sample-Databases Uebersicht

- URL: `https://wiki.postgresql.org/wiki/Sample_Databases`

Nutzen:

- guter Einstiegspunkt fuer weitere PostgreSQL-Beispiele
- hilfreich, wenn spaeter gezielt andere Komplexitaetsstufen oder
  Domainenmodelle gebraucht werden

### 3.2 Bytebase Employee Sample

- URL: `https://github.com/bytebase/employee-sample-database`

Nutzen:

- moegliche Ergaenzung oder Gegenprobe zum klassischen `employees`-Datensatz
- sinnvoll, wenn eine zweite Employee-Variante fuer Tooling-Vergleiche
  benoetigt wird

---

## 4. Spaetere Schwergewichte

### 4.1 TPC-H

- URL: `https://www.tpc.org/tpch/`
- **Aktiv ab Phase 4 (4a):** via gepinntem DuckDB-Generator gesourct — siehe
  [2.5](#25-tpc-h-via-duckdb-generator-phase-4-4a). Dieser Abschnitt bleibt als
  Einordnung des Original-Benchmarks.

Einordnung:

- gut fuer spaetere analytische Performance- und Benchmark-Szenarien
- fuer fruehen Adapter- und Integrationsbau meist zu schwergewichtig

### 4.2 TPC-DS

- URL: `https://www.tpc.org/tpcds/`

Einordnung:

- noch staerker auf komplexe Analyse- und Warehouse-Szenarien ausgelegt
- eher fuer spaetere Performance-, Skalierungs- und Robustheitstests

---

## 5. Empfohlene Teststaffelung

### 5.1 Phase 1 - Smoke

- `Pagila`
- Ziel: schnelle Schema-/Import-/Export-Pruefung in CI-nahen Laeufen

### 5.2 Phase 2 - Compatibility

- `Pagila` plus `Sakila`
- Ziel: Dialekt- und Strukturvergleiche, Cross-DB-Verhalten, FK-Graphen

### 5.3 Phase 3 - Scale

- `Employees`
- Ziel: Streaming, Resume, groessere Datenmengen, laengere End-to-End-Laeufe

### 5.4 Phase 4 - Performance

- `TPC-H` und spaeter `TPC-DS`
- Ziel: Benchmark-naehere Last- und Analysefaelle

---

## 6. Empfehlung

Fuer den naechsten praktischen Schritt sollten zuerst genau diese drei
Datensaetze operationalisiert werden:

- `Pagila`
- `Sakila`
- `Employees`

Diese Kombination deckt kleine bis mittlere Smoke-/Kompatibilitaetstests
sowie einen ersten groesseren Datenpfad ab, ohne die Komplexitaet eines
formalen Benchmark-Sets zu frueh in den Testaufbau zu ziehen.

> **Operationalisierung (Stand 2026-06-18):** Pagila/Sakila sind im 0.9.9-Pilot
> real genutzt (ad-hoc), aber noch **nicht** in der automatisierten CI-Suite
> (die nutzt synthetische Fixtures); Employees ist noch nicht geladen. Der
> Umsetzungsplan fuer einen automatisierten Sample-DB-Integrationstest-Harness
> liegt in [`../done/sample-db-integration-harness.md`](../done/sample-db-integration-harness.md)
> (Smoke/Compatibility = Test-Infra; Scale/Performance = 1.0.0-QA, LF 8.1/8.2).
