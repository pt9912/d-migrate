# Vorabklärung: Oracle als fünfter Dialekt (Milestone 1.8.0)

> **Status:** Skeleton — Pending Slice-0-Baubeginn (2026-09-05). Scope
> skizziert, alle vier Grundsatzentscheidungen getroffen (siehe ADR 0052),
> **noch keine aktive Slice-Arbeit** im Code.
> **Trigger:** Eigner-Entscheidung, Oracle nach MSSQL (siehe
> [`mssql-dialect-scoping.md`](../done/mssql-dialect-scoping.md)) als nächsten
> Dialekt zu bauen — dem dort etablierten Muster folgend.
> **Lastenheft:** [LF-019](../../../spec/lastenheft-d-migrate.md#lf-019)
> (Kann-Anforderung: „weitere Datenbanksysteme … Oracle, MS SQL Server").
> **ADR:** [0052](../../adr/0052-oracle-fuenfter-dialekt-scoping.md).

## Bestandsaufnahme — was ein fünfter Dialekt kostet (gemessen 2026-09-05)

Umfang der vier bestehenden Dialekte, nur Produktivcode:

| Modul | Zeilen (main) |
| --- | ---: |
| `driver-sqlite` | 10 606 |
| `driver-mssql` | 9 565 |
| `driver-mysql` | 9 538 |
| `driver-postgresql` | 8 598 |
| `driver-common` (geteilt) | 4 656 |

Dazu je Dialekt: ein Profiling-Modul (`driver-*-profiling`), ein
Integrationstest-Modul (`test/integration-*`), Teilnahme an der
Cross-Dialekt-Matrix, Kanonisierer-/Fingerprint-Beteiligung (Postcompare v7
ist dialekt-parametrisiert) und sample-db-Smokes.

**Der Port verlangt** ([`DatabaseDriver`](../../../hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt),
unverändert seit MSSQL): `dialect`, `ddlGenerator()`, `dataReader()`,
`tableLister()`, `dataWriter()`, `urlBuilder()`, `schemaReader()` als
Pflicht. Drei Fähigkeiten haben No-op-Defaults (`transferCompatibility`,
`typeCanonicalizer`, `preGenerationValidator`) — ein Dialekt ist ab Slice 1
registrierbar, ohne alles zu können.

**Querschnittskosten im Hexagon:** `DatabaseDialect`-Vorkommen (grob gezählt,
`hexagon/` + `adapters/`, Produktivquellen): POSTGRESQL 47, MYSQL 62, SQLITE
70, MSSQL 61 — zusammen 240 Vorkommen über die vier bestehenden Dialekte.
`hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/DatabaseDialect.kt`
trägt bislang **keinen** `ORACLE`-Wert, auch nicht vorbereitend.

**Keine strukturellen Blocker:**

- [ADR 0037](../../adr/0037-database-agnostic-first-staffelung.md)
  („Database-Agnostic First", Umbau auf 2.0.0 vertagt) nennt Oracle explizit
  als JDBC-Fall, der in den heutigen Port passt (Zeile 91) — dieselbe
  Einordnung wie bei MSSQL.
- Treiber `com.oracle.database.jdbc:ojdbc11` — siehe ADR 0052 Punkt 3
  (Oracle Free Use Terms and Conditions, kein Blocker, aber
  Compliance-Pflicht: Lizenztext mitliefern).
- Materialized Views haben im Neutralmodell bereits eine Heimat (0.9.7
  D.3b-Vollscheibe, in `hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/`)
  — Oracle muss hier nur reverse-/generate-seitig andocken, keine
  Modellerweiterung.

## Oracle-Inventar — was anders ist und wohin es fällt

| Fläche | Oracle-Realität | Einordnung |
| --- | --- | --- |
| Auto-Increment | Sequences (klassisch) + `IDENTITY`-Spalten (12c+) | Kern |
| Schemata | Schema = User, kein separates `dbo`-Konzept | Kern |
| Text/Unicode | `VARCHAR2`/`NVARCHAR2`, Byte- vs. Zeichen-Semantik | **Entscheidung im Typmapping** |
| Boolean | kein nativer Typ; Konvention `NUMBER(1)` oder `CHAR(1)` | Kern — Reverse muss falten |
| Temporal | `DATE` (**trägt Uhrzeit!**), `TIMESTAMP [WITH [LOCAL] TIME ZONE]` | Kern — `DATE`-Eigenheit dokumentieren |
| UUID | kein nativer Typ; `RAW(16)` oder `VARCHAR2(36)` | **Entscheidung** |
| Binary | `BLOB`, `RAW` | Kern |
| Paginierung | `ROWNUM` (klassisch), `FETCH FIRST n ROWS ONLY` (12c+) | Kern — betrifft DataReader-Chunking |
| Quoting | `"Anführungszeichen"`; **UPPERCASE-Default ohne Quoting** (Gegenteil von PG/MySQL/SQLite) | **Entscheidung** — Case-Fallstrick, siehe ADR 0052 |
| Indizes | Function-based-Indizes, Bitmap-Indizes | Ausbau-Slice |
| Partitionierung | Range/List/Hash/Composite — strukturell reichhaltiger als PG | Ausbau-Slice |
| Materialized Views | **nativ vorhanden**, echtes Refresh-Modell (FAST/COMPLETE/FORCE, ON COMMIT/ON DEMAND) | Ausbau-Slice — Anschluss ans bestehende Modell, keine Lücke |
| Volltext | Oracle Text, eigene Indextypen (`CONTEXT`/`CTXCAT`) | Ausbau-Slice — Muster aus dem Fulltext-Slice |
| Routinen/Trigger (standalone) | PL/SQL, `CREATE OR REPLACE` | Ausbau-Slice |
| **PL/SQL Packages** | Prozedur-/Funktions-Gruppierung, kein Äquivalent in PG/MySQL/SQLite/MSSQL | **Eigener, terminlich offener Ausbau-Slice** (ADR 0052 Punkt 4) — braucht Neutralmodell-Erweiterung |

## Die vier Entscheidungen (getroffen 2026-09-05, siehe [ADR 0052](../../adr/0052-oracle-fuenfter-dialekt-scoping.md))

1. **Testziel: Oracle 23ai Free**, Testcontainer `gvenzl/oracle-free`
   (`slim`/`faststart`, ~700 MB–1,4 GB komprimiert — vergleichbar mit/leichter
   als der MSSQL-Container). Lizenz-/EULA-Mechanik vor Slice 0 zu verifizieren
   (analog `ACCEPT_EULA=Y` bei MSSQL).
2. **Feature-Schnitt: keine Carve-Outs.** Voller Funktionsumfang als Slices,
   analog MSSQL.
3. **JDBC-Lizenz (FUTC): kein Blocker, aber Compliance-Pflicht** — Lizenztext
   im Docker-Image/Release-Assets mitführen (Teil von Slice 0).
4. **PL/SQL Packages: Ausbau-Slice, keine dauerhafte Lücke.** Package-Inhalte
   werden bis zu diesem Slice als entpackte Einzelroutinen erfasst.

## Slice-Schnitt (Entwurf, analog zum MSSQL-Muster)

Dem gewachsenen Muster folgend (Kern zuerst, Ausbau als eigene Slices):

| Slice | Inhalt | Registrierbar ab / liefert |
| --- | --- | --- |
| **0** | Scoping-ADR (0052), Gradle-Modul `driver-oracle`, Testcontainers-Spike (Connect + `SELECT * FROM v$version` oder `SELECT banner FROM v$version`), FUTC-Lizenztext-Doku, Dependabot-Ignore | — |
| **1** | `JdbcUrlBuilder` + `SchemaReader`/`TableLister` (Reverse-Read) + `ORACLE`-Enum-Querschnitt + `DialectCommandGate`-Einträge | `schema reverse` funktioniert |
| **1a** | CLI-E2E-Absicherung in `test/e2e-cli` (Gate-Ablehnungen + `schema reverse`-Subprozess-E2E), analog MSSQL Slice 1a | E2E-Netz vor Slice 2 |
| **2** | `DdlGenerator` + Typtabelle NeutralType→Oracle-Typen (inkl. Materialized-View-Anschluss ans bestehende Modell) | `schema generate --target oracle` |
| **3** | `DataReader`/`DataWriter` (Transfer); **3b** sample-db-Oracle-Leg im Harness (analog [ADR 0013](../../adr/0013-sample-db-sourcing.md)/[ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)) | `data export/import/transfer` + Oracle-Smoke in CI |
| **4** | `NeutralTypeCanonicalizer` + Postcompare-Fingerprint-Beleg, `transferCompatibility`, Cross-Dialekt-sample-db-Smoke | Vergleichs-Substrat für Slice 5 |
| **5** | Diff/Migrate (`OracleDiff*Ops`) inkl. Beitritt zum Cross-Dialekt-Matrix-Sweep. Voraussichtlich größter Slice (bei MSSQL größer als Slices 1–4 zusammen) — Sub-Slice-Schnitt folgt Familien-Gliederung, sobald der Slice beginnt | `schema migrate` |
| **6** | Function-based- + Bitmap-Indizes, Reverse + Generate + Diff | volle Index-Treue |
| **7** | Partitionierung: Range/List/Hash/Composite (Anschluss an `PartitionBoundScanner`/Cross-Dialekt-Muster) | Partitionstabellen im Round-Trip |
| **8** | Volltext: Oracle Text (`CONTEXT`/`CTXCAT`, Muster aus dem Fulltext-Slice) | Volltext-Indizes Generate + Reverse |
| **9** | Routinen/Trigger (standalone PL/SQL, `CREATE OR REPLACE`) | Routinen-Migration |
| **10** | Profiling-Modul `driver-oracle-profiling` | Live belegt; `DialectCommandGate` verliert seinen letzten Oracle-Eintrag |
| **11** | PL/SQL Packages (Neutralmodell-Erweiterung um Routine-Gruppierung) — **terminlich offen**, kein Commitment auf einen Zeitpunkt | Package-Struktur im Round-Trip |

Jeder Slice endet CI-grün und einzeln nutzbar; die No-op-Defaults des Ports
machen das möglich, ohne UNSUPPORTED-Stopgaps (No-Carveouts-Regel).

### Kommando-Verfügbarkeit je Slice (analog MSSQL)

| Kommando | Oracle verfügbar ab | bis dahin |
| --- | --- | --- |
| Verbindungsschicht (`oracle://`-URLs, Pool, SSL/TLS) | **Slice 1** | — |
| `schema reverse` (CLI + MCP-Job) | **Slice 1** | — |
| `schema compare` (MCP-Job, via Reverse) | **Slice 1** | — |
| `schema generate` | **Slice 2** | — |
| `export flyway/liquibase/django/knex` | **Slice 2** | — |
| `data export` / `data import` / `data transfer` | **Slice 3** | — |
| `schema migrate` | Slice 5 | Gate + `MigrateRendererRegistry` → `null` |
| `data profile` (CLI + MCP-Job) | Slice 10 | Gate |

## Offene Punkte vor Slice-0-Baubeginn

- FUTC-Lizenztext-Bündelung im Docker-Image/Release-Assets konkret ausarbeiten
  (welche Datei, welcher Pfad — analog zu bestehenden Lizenz-Artefakten, falls
  vorhanden).
- `gvenzl/oracle-free`-EULA-/Zustimmungsmechanik verifizieren (vor Slice 0).
- Testcontainers-Ressourcenbedarf (RAM) real messen, nicht nur Image-Größe.

## Risiken

- UPPERCASE-Default-Bezeichner ohne Quoting sind ein Cross-Cutting-Risiko für
  Reverse-/Postcompare-Kanonisierung, ähnlich MSSQLs Collation-Fallstrick.
- PL/SQL Packages bleiben bis Slice 11 unvollständig abgebildet — muss in
  Anwenderhandbuch/Administrationshandbuch als bekannte Grenze stehen, sobald
  Oracle nutzersichtbar wird (ab Slice 1).
