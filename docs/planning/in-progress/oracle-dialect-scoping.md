# Vorabklärung: Oracle als fünfter Dialekt (Milestone 1.8.0)

> **Status:** In Progress (2026-09-05). Scope skizziert, alle fünf
> Grundsatzentscheidungen getroffen (siehe ADR 0052), **Slice 0 geliefert**.
>
> **Status-Update 2026-09-05:** Slice 0 umgesetzt — Modul
> `adapters/driven/driver-oracle` (Skeleton, `ojdbc11` 23.26.3.0.0),
> Spike-Modul `test/integration-oracle` (Container-Start gegen
> `gvenzl/oracle-free:23-slim-faststart` + Treiber-Connect +
> `SELECT banner FROM v$version`, live grün gelaufen), Dependabot-Major-Ignore,
> FUTC-Lizenzdoku in [`THIRD-PARTY-NOTICES.md`](../../../THIRD-PARTY-NOTICES.md).
> Live-Fund: die gleitenden `slim-faststart`-Tags liefern inzwischen „26ai"
> statt „23ai" aus (Banner „Oracle AI Database", nicht mehr „Oracle
> Database") — Spike pinnt deshalb explizit auf `23-slim-faststart`.
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
| Materialized Views | **nativ vorhanden**, echtes Refresh-Modell (FAST/COMPLETE/FORCE, ON COMMIT/ON DEMAND) | Ausbau-Slice (10) — Anschluss ans bestehende Modell, keine Lücke |
| Volltext | Oracle Text, eigene Indextypen (`CONTEXT`/`CTXCAT`) | Ausbau-Slice — Muster aus dem Fulltext-Slice |
| Routinen/Trigger (standalone) | PL/SQL, `CREATE OR REPLACE` | Ausbau-Slice |
| **PL/SQL Packages** | Prozedur-/Funktions-Gruppierung, kein Äquivalent in PG/MySQL/SQLite/MSSQL | **Zeitlich unbestimmte Einschränkung** (ADR 0052 Punkt 4/Konsequenzen) — braucht Neutralmodell-Erweiterung, kein Slice mit Liefertermin |

## Die fünf Entscheidungen (getroffen 2026-09-05, siehe [ADR 0052](../../adr/0052-oracle-fuenfter-dialekt-scoping.md))

1. **Testziel: Oracle 23ai Free**, Testcontainer `gvenzl/oracle-free`
   (`slim`/`faststart`, ~700 MB–1,4 GB komprimiert — vergleichbar mit/leichter
   als der MSSQL-Container). Lizenz-/EULA-Mechanik vor Slice 0 zu verifizieren
   (analog `ACCEPT_EULA=Y` bei MSSQL).
2. **Feature-Schnitt: keine Carve-Outs.** Voller Funktionsumfang als Slices
   0–11 (inkl. Profiling-Modul als eigener Ausbau-Slice), analog MSSQL.
3. **JDBC-Lizenz (FUTC): kein Blocker, aber Compliance-Pflicht** — Lizenztext
   im Docker-Image/Release-Assets mitführen (Teil von Slice 0).
4. **PL/SQL Packages: zeitlich unbestimmte Einschränkung, kein numerierter
   Slice mit Liefertermin.** Anders als die anderen Ausbau-Flächen (die alle
   Slices 6–11 mit Lieferzusage sind) bekommt die Package-Gruppierung
   **bewusst keine Slice-Nummer** — ein Slice ohne Termin wäre ein
   Carve-Out mit anderem Etikett und widerspräche Punkt 2. Package-Inhalte
   werden bis auf Weiteres als entpackte Einzelroutinen erfasst (siehe
   [ADR 0052](../../adr/0052-oracle-fuenfter-dialekt-scoping.md), Konsequenzen).
5. **Test-Infrastruktur: Oracle läuft in jedem CI-Lauf mit**, analog MSSQL
   Entscheidung 3. Das neue Integrationstest-Modul (`test/integration-oracle`,
   dem `test/integration-*`-Muster folgend) nimmt automatisch am generischen
   `-PintegrationTests`-Mechanismus in `integration.yml` teil (jeder Push/PR
   auf main, nicht-blockierend neben dem Hauptbuild) — kein Sonderpfad. Der
   `gvenzl/oracle-free`-Container ist laut Recherche vergleichbar mit oder
   leichter als der MSSQL-Container, eine Staffelung ist deshalb nicht
   vorgesehen; RAM-Bedarf ist trotzdem vor Slice 0 real zu messen (siehe
   „Offene Punkte" unten).

## Slice-Schnitt (Entwurf, analog zum MSSQL-Muster)

Dem gewachsenen Muster folgend (Kern zuerst, Ausbau als eigene Slices):

| Slice | Inhalt | Registrierbar ab / liefert |
| --- | --- | --- |
| **0** ✅ | Scoping-ADR (0052), Gradle-Modul `driver-oracle`, Testcontainers-Spike (Connect + `SELECT banner FROM v$version`), FUTC-Lizenztext-Doku, Dependabot-Ignore | — |
| **1** | `JdbcUrlBuilder` + `SchemaReader`/`TableLister` (Reverse-Read) + `ORACLE`-Enum-Querschnitt + `DialectCommandGate` **wiedereinführen** (die Klasse wurde in Commit `ec3f2d06` beim MSSQL-Slice-10-Abschluss gelöscht, weil ihr letzter Eintrag wegfiel — Oracle braucht sie neu, nicht nur einen weiteren Eintrag) | `schema reverse` funktioniert |
| **1a** | CLI-E2E-Absicherung in `test/e2e-cli` (Gate-Ablehnungen + `schema reverse`-Subprozess-E2E), analog MSSQL Slice 1a | E2E-Netz vor Slice 2 |
| **2** | `DdlGenerator` + Typtabelle NeutralType→Oracle-Typen (Kern-Typen; Materialized Views bewusst **nicht** hier, siehe Slice 10) | `schema generate --target oracle` |
| **3** | `DataReader`/`DataWriter` (Transfer); **3b** sample-db-Oracle-Leg im Harness (analog [ADR 0013](../../adr/0013-sample-db-sourcing.md)/[ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)) | `data export/import/transfer` + Oracle-Smoke in CI |
| **4** | `NeutralTypeCanonicalizer` + Postcompare-Fingerprint-Beleg, `transferCompatibility`, Cross-Dialekt-sample-db-Smoke | Vergleichs-Substrat für Slice 5 |
| **5** | Diff/Migrate (`OracleDiff*Ops`) inkl. Beitritt zum Cross-Dialekt-Matrix-Sweep. Voraussichtlich größter Slice (bei MSSQL größer als Slices 1–4 zusammen) — Sub-Slice-Schnitt folgt Familien-Gliederung, sobald der Slice beginnt | `schema migrate` |
| **6** | Function-based- + Bitmap-Indizes, Reverse + Generate + Diff | volle Index-Treue |
| **7** | Partitionierung: Range/List/Hash/Composite (Anschluss an `PartitionBoundScanner`/Cross-Dialekt-Muster) | Partitionstabellen im Round-Trip |
| **8** | Volltext: Oracle Text (`CONTEXT`/`CTXCAT`, Muster aus dem Fulltext-Slice) | Volltext-Indizes Generate + Reverse |
| **9** | Routinen/Trigger (standalone PL/SQL, `CREATE OR REPLACE`) | Routinen-Migration |
| **10** | Materialized Views: Anschluss ans bestehende 0.9.7-D.3b-Modell (Refresh-Modi FAST/COMPLETE/FORCE, ON COMMIT/ON DEMAND) | Materialized Views im Round-Trip |
| **11** | Profiling-Modul `driver-oracle-profiling` | Live belegt; `DialectCommandGate` verliert seinen letzten Oracle-Eintrag |
| **ohne Nummer** | PL/SQL Packages (Neutralmodell-Erweiterung um Routine-Gruppierung) — **zeitlich unbestimmt, bewusst kein Slice mit Liefertermin** (Entscheidung 4) | Package-Struktur im Round-Trip, sobald angegangen |

Jeder nummerierte Slice endet CI-grün und einzeln nutzbar; die No-op-Defaults
des Ports machen das möglich, ohne UNSUPPORTED-Stopgaps (No-Carveouts-Regel).
PL/SQL Packages sind davon bewusst ausgenommen (Entscheidung 4) — kein
verstecktes else, aber auch keine falsche Terminzusage.

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
| `data profile` (CLI + MCP-Job) | Slice 11 | Gate |

## Offene Punkte

- ~~`gvenzl/oracle-free`-EULA-/Zustimmungsmechanik verifizieren~~ — **erledigt
  (Slice 0):** keine EULA-Zustimmung nötig, anders als beim MSSQL-Image.
- ~~FUTC-Lizenztext dokumentieren~~ — **erledigt (Slice 0):**
  [`THIRD-PARTY-NOTICES.md`](../../../THIRD-PARTY-NOTICES.md) im Repo-Root.
  Die Bündelung dieser Datei in Release-Artefakten ist ein separates Thema,
  siehe [`third-party-notices-release-bundling.md`](../open/third-party-notices-release-bundling.md).
- ~~Testcontainers-Ressourcenbedarf (RAM) real messen~~ — **erledigt (Slice
  1, live entdeckt):** kein RAM-Problem, sondern ein zu knapper Default:
  `org.testcontainers.oracle.OracleContainer` setzt `withStartupTimeout` auf
  nur 60s, ausreichend für ein bereits gezogenes Image auf einer warmen
  lokalen Maschine, zu knapp für einen kalten Pull + Kaltstart auf dem
  GitHub-Actions-Runner (real gemessen: Timeout nach 60s in CI, ~2-3 min bis
  „DATABASE IS READY TO USE!" lokal). Fix: `.withStartupTimeout(Duration
  .ofMinutes(5))` in `OracleContainerConnectIntegrationTest.kt`.
- **Neu (Slice 0, live entdeckt):** `gvenzl/oracle-free`s gleitende
  `slim-faststart`-Tags liefern inzwischen „26ai" statt „23ai" aus, und der
  Versions-Banner heißt jetzt „Oracle AI Database" statt „Oracle Database" —
  der Spike pinnt deshalb explizit auf `23-slim-faststart`
  (siehe `OracleContainerConnectIntegrationTest.kt`).

## Risiken

- UPPERCASE-Default-Bezeichner ohne Quoting sind ein Cross-Cutting-Risiko für
  Reverse-/Postcompare-Kanonisierung, ähnlich MSSQLs Collation-Fallstrick.
- PL/SQL Packages bleiben zeitlich unbestimmt unvollständig abgebildet (siehe
  Entscheidung 4) — muss in Anwenderhandbuch/Administrationshandbuch als
  bekannte Grenze stehen, sobald Oracle nutzersichtbar wird (ab Slice 1).
