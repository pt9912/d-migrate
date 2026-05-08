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
