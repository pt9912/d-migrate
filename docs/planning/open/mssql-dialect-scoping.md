# Vorabklärung: MS SQL Server als vierter Dialekt (Milestone 1.7.0, vorgezogen)

> **Status:** Entscheidungsvorlage (Draft, 2026-08-16)
> **Trigger:** Eigner-Entscheidung, MSSQL als nächsten großen Punkt vorzuziehen.
> Die Roadmap führt 1.7.0 hinter Trino (1.1.0), gRPC (1.1.8), REST (1.2.0) u. a. —
> diese Reihenfolge wird damit bewusst geändert; die Roadmap ist deskriptiv.
> **Lastenheft:** [LF-019](../../../spec/lastenheft-d-migrate.md#lf-019)
> (Kann-Anforderung: „weitere Datenbanksysteme … Oracle, MS SQL Server").
> **Aktivierungsbedingung** (Move nach `../next/` als ausgearbeiteter Plan):
> die drei Entscheidungen unten sind getroffen.

## Bestandsaufnahme — was ein vierter Dialekt kostet (gemessen)

Umfang der drei bestehenden Dialekte, nur Produktivcode:

| Modul | Zeilen (main) |
| --- | ---: |
| `driver-sqlite` | 10 451 |
| `driver-mysql` | 9 219 |
| `driver-postgresql` | 8 280 |
| `driver-common` (geteilt) | 4 135 |

Dazu je Dialekt: ein Profiling-Modul (`driver-*-profiling`), ein
Integrationstest-Modul (`test/integration-*`), Teilnahme an der
Cross-Dialekt-Matrix, Kanonisierer-/Fingerprint-Beteiligung (Postcompare v7 ist
dialekt-parametrisiert) und sample-db-Smokes. Ein vierter Dialekt ist ein
**Milestone in der Größenordnung mehrerer bisheriger Releases**, kein Feature.

**Der Port verlangt** ([`DatabaseDriver`](../../../hexagon/ports-execute/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt)):
`dialect`, `ddlGenerator()`, `dataReader()`, `tableLister()`, `dataWriter()`,
`urlBuilder()`, `schemaReader()` als Pflicht. **Drei Fähigkeiten haben
No-op-Defaults** (`transferCompatibility`, `typeCanonicalizer`,
`preGenerationValidator`) — das trägt den inkrementellen Aufbau: ein Dialekt ist
ab Slice 1 registrierbar, ohne alles zu können.

**Querschnittskosten im Hexagon:** ~70 `DatabaseDialect`-Verzweigungen im
Produktivcode (28× SQLITE, 26× MYSQL, 15× POSTGRESQL) brauchen einen
MSSQL-Zweig. Es gilt die `DdlDialectContext`-Regel: dialektspezifische Daten als
sealed Varianten, keine nullable `mssql*`-Felder auf generischen Ports.

**Keine strukturellen Blocker:**

- [ADR 0037](../../adr/0037-database-agnostic-first-staffelung.md)
  („Database-Agnostic First", Umbau auf 2.0.0 vertagt) steht nicht im Weg —
  MSSQL *ist* ein JDBC-Ziel und passt in den heutigen Port.
- Treiber: `com.microsoft.sqlserver:mssql-jdbc` ist **MIT-lizenziert** —
  verträglich mit diesem Repo. Major-Bumps gehören wie bei den anderen Treibern
  in die Dependabot-Ignore-Liste (Cross-Dialekt-Matrix als Gate).

## T-SQL-Inventar — was anders ist und wohin es fällt

| Fläche | MSSQL-Realität | Einordnung für Cut A |
| --- | --- | --- |
| Auto-Increment | `IDENTITY(seed, increment)` an der Spalte; Sequenzen seit 2012 separat | Kern — `identifier`/`auto_increment` mappt auf IDENTITY |
| Schemata | `dbo` als Default, Namen zweiteilig `schema.table` | Kern — wie PG-`public` behandeln |
| Text/Unicode | `NVARCHAR` vs. `VARCHAR` + Collations | **Entscheidung im Typmapping**: `text` → `NVARCHAR` (Unicode-sicher), Collation nicht modellieren |
| Boolean | kein Boolean-Typ; `bit` mit 0/1 | Kern — Reverse muss `bit` → `BooleanType` falten |
| Temporal | `datetime2`, `datetimeoffset`, `date`, `time` | Kern — `DateTime(timezone=true)` → `datetimeoffset` |
| UUID | `uniqueidentifier` | Kern |
| Binary | `VARBINARY(MAX)` | Kern |
| Indizes | clustered/nonclustered; **gefilterte** Indizes (WHERE) existieren | Kern ohne clustered-Steuerung; gefiltert = Folgearbeit |
| Partitionierung | Partition Functions + Schemes + Filegroups — strukturell anders als PG | **Carve-Out** (wie beim PG-Slice: benannt, nicht still) |
| Volltext | eigener Dienst (Full-Text Search), eigene Installation | **Carve-Out** — Muster aus dem Fulltext-Slice |
| Routinen/Trigger | T-SQL-Prozeduren, `CREATE OR ALTER` | Folge-Slice, nicht Cut A |
| Paginierung | `OFFSET … FETCH` (2012+), kein `LIMIT` | Kern — betrifft DataReader-Chunking |
| Quoting | `[eckige Klammern]` oder `"` bei `QUOTED_IDENTIFIER ON` | **Entscheidung**: `[]` als kanonisch (Vertrag „Modell trägt Quotes" beachten) |

## Die drei Entscheidungen

1. **Versions-Untergrenze.** Vorschlag: **SQL Server 2017+**. Begründung:
   Linux-Container erster Klasse (Testcontainers), `STRING_AGG`, und alles aus
   dem Inventar oben ist ab 2012 verfügbar — 2017 ist die älteste Version mit
   brauchbarer Container-Story, alles Ältere ist EOL.
2. **Feature-Schnitt Cut A.** Vorschlag: Kern = Reverse-Read, DDL-Generate,
   Datentransfer, Matrix-Teilnahme. **Ausdrücklich ausgeschlossen** (benannte
   Carve-Outs, je ein Ticket): Partitionierung, Volltext, Routinen/Trigger,
   gefilterte/clustered-gesteuerte Indizes, Profiling-Modul.
3. **Test-Infrastruktur.** `mcr.microsoft.com/mssql/server:2022-latest` braucht
   `ACCEPT_EULA=Y` und ist mit ~1,5 GB Image / 2 GB RAM der schwerste Container
   im Haus. Zu entscheiden: läuft die MSSQL-Integrationsschiene in jedem
   CI-Lauf mit (Laufzeit!) oder wie `perf-acceptance` gestaffelt? Die
   EULA-Akzeptanz gehört dokumentiert (Administrationshandbuch,
   Testcontainers-Setup).

## Vorgeschlagener Slice-Schnitt

Dem gewachsenen Muster folgend (Kern zuerst, Carve-Outs benannt):

| Slice | Inhalt | Registrierbar ab |
| --- | --- | --- |
| **0** | Scoping-ADR (die drei Entscheidungen), Gradle-Modul `driver-mssql`, Testcontainers-Spike (Connect + `SELECT @@VERSION`), Dependabot-Ignore | — |
| **1** | `JdbcUrlBuilder` + `SchemaReader`/`TableLister` (Reverse-Read, nur lesen) | ja — `schema reverse` funktioniert |
| **2** | `DdlGenerator` + Typtabelle NeutralType→T-SQL (Generate-Richtung) | `schema generate --target mssql` |
| **3** | `DataReader`/`DataWriter` (Transfer; Fast-Path später) | `data export/import/transfer` |
| **4** | Cross-Dialekt-Matrix, `NeutralTypeCanonicalizer`, Postcompare-Fingerprint, `transferCompatibility` | Matrix-Gate |
| **5** | Diff/Migrate (`MssqlDiff*Ops` — bei allen Dialekten der größte Brocken) | `schema migrate` |
| danach | Profiling-Modul, Carve-Out-Tickets nach Bedarf | — |

Jeder Slice endet CI-grün und einzeln nutzbar; die No-op-Defaults des Ports
machen das möglich, ohne UNSUPPORTED-Stopgaps (No-Carveouts-Regel: was Cut A
nicht kann, ist als Carve-Out benannt statt als else-Zweig versteckt).

## Risiken

- **Reverse-Read-Treue**: `INFORMATION_SCHEMA` reicht bei MSSQL nicht für
  Identity/Defaults/Indizes — es braucht `sys.*`-Katalogsichten. Der
  SQLite-Präzedenzfall (PK-NOT-NULL-Verlust, identifier-Narrowing) zeigt, dass
  Reverse-Fidelity-Fehler erst im Round-Trip auffallen → Round-Trip-Tests ab
  Slice 2, nicht erst in Slice 4.
- **Collation-Semantik** (case-insensitive Default!) berührt Vergleiche im
  Postcompare — der Kanonisierer muss Namensvergleiche dialektbewusst falten.
- **CI-Gewicht** des Containers (Entscheidung 3).
- **Kein MSSQL-Wissen in den Goldens**: DDL-Goldens entstehen neu; der
  Regenerier-Weg läuft per CLI (nicht `make golden-update`).
