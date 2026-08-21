# Vorabklärung: MS SQL Server als vierter Dialekt (Milestone 1.7.0, vorgezogen)

> **Status:** In Progress (2026-08-21) — Entscheidungen getroffen und in
> [ADR 0047](../../adr/0047-mssql-vierter-dialekt-scoping.md) festgehalten;
> Draft 2026-08-16.
> **Trigger:** Eigner-Entscheidung, MSSQL als nächsten großen Punkt vorzuziehen.
> Die Roadmap führt 1.7.0 hinter Trino (1.1.0), gRPC (1.1.8), REST (1.2.0) u. a. —
> diese Reihenfolge wird damit bewusst geändert; die Roadmap ist deskriptiv.
> **Lastenheft:** [LF-019](../../../spec/lastenheft-d-migrate.md#lf-019)
> (Kann-Anforderung: „weitere Datenbanksysteme … Oracle, MS SQL Server").
>
> **Status-Update 2026-08-21:** Slice 0 umgesetzt — Modul
> `adapters/driven/driver-mssql` (Skeleton, `mssql-jdbc` 13.4.0), Spike-Modul
> `test/integration-mssql` (Container-Start + Treiber-Connect +
> `SELECT @@VERSION`), Dependabot-Major-Ignore, EULA-Doku in
> [`quality.md`](../../user/quality.md). (Commit `5a07080b`, CI grün.)
>
> **Status-Update 2026-08-21 (2):** Slice 1 umgesetzt — `MSSQL` in
> `DatabaseDialect` + alle exhaustiven Verzweigungen, `DialectCommandGate`
> mit Kommando-Verfügbarkeits-Tabelle (unten), `MssqlJdbcUrlBuilder`
> (Semikolon-Properties via `SqlServerJdbcUrl`, SSL-Mapping
> `encrypt`/`trustServerCertificate`), `MssqlSchemaReader` über
> `sys.*`-Sichten (Identity, Default-Paren-Unwrapping, gefilterte Indizes,
> CHECKs, native Sequenzen, Views; Routinen/Trigger als `skippedObjects` +
> `R342`), `MssqlTableLister`, ServiceLoader-Registrierung; Spec
> (connection-config 1.6) + Handbücher; Unit- und Live-Integrationstests
> grün (SQL Server 2022).

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

**Der Port verlangt** ([`DatabaseDriver`](../../../hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt)):
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

| Fläche | MSSQL-Realität | Einordnung |
| --- | --- | --- |
| Auto-Increment | `IDENTITY(seed, increment)` an der Spalte; Sequenzen seit 2012 separat | Kern — `identifier`/`auto_increment` mappt auf IDENTITY |
| Schemata | `dbo` als Default, Namen zweiteilig `schema.table` | Kern — wie PG-`public` behandeln |
| Text/Unicode | `NVARCHAR` vs. `VARCHAR` + Collations | **Entscheidung im Typmapping**: `text` → `NVARCHAR` (Unicode-sicher), Collation nicht modellieren |
| Boolean | kein Boolean-Typ; `bit` mit 0/1 | Kern — Reverse muss `bit` → `BooleanType` falten |
| Temporal | `datetime2`, `datetimeoffset`, `date`, `time` | Kern — `DateTime(timezone=true)` → `datetimeoffset` |
| UUID | `uniqueidentifier` | Kern |
| Binary | `VARBINARY(MAX)` | Kern |
| Indizes | clustered/nonclustered; **gefilterte** Indizes (WHERE) existieren | Kern (Default nonclustered); gefiltert + clustered-Steuerung = Slice 6 |
| Partitionierung | Partition Functions + Schemes + Filegroups — strukturell anders als PG | Slice 7 |
| Volltext | eigener Dienst (Full-Text Search), eigene Installation | Slice 8 — Muster aus dem Fulltext-Slice |
| Routinen/Trigger | T-SQL-Prozeduren, `CREATE OR ALTER` | Slice 9 |
| Paginierung | `OFFSET … FETCH` (2012+), kein `LIMIT` | Kern — betrifft DataReader-Chunking |
| Quoting | `[eckige Klammern]` oder `"` bei `QUOTED_IDENTIFIER ON` | **Entscheidung**: `[]` als kanonisch (Vertrag „Modell trägt Quotes" beachten) |

## Die drei Entscheidungen (getroffen 2026-08-21)

1. **Versions-Untergrenze: SQL Server 2017+.** Begründung:
   Linux-Container erster Klasse (Testcontainers), `STRING_AGG`, und alles aus
   dem Inventar oben ist ab 2012 verfügbar — 2017 ist die älteste Version mit
   brauchbarer Container-Story, alles Ältere ist EOL.
2. **Feature-Schnitt: keine Carve-Outs.** Der Plan deckt den vollen
   Funktionsumfang als Slices ab: Kern = Reverse-Read, DDL-Generate,
   Datentransfer, Matrix-Teilnahme, Diff/Migrate (Slices 1–5); Partitionierung,
   Volltext, Routinen/Trigger, gefilterte/clustered-gesteuerte Indizes und das
   Profiling-Modul sind **eigene Slices (6–10)**, keine ausgeschlossenen
   Tickets.
3. **Test-Infrastruktur: MSSQL läuft in jedem CI-Lauf mit.** Das neue
   Integrationstest-Modul (dem `test/integration-*`-Muster folgend) nimmt
   automatisch am generischen
   `-PintegrationTests`-Aufruf in `integration.yml` teil (jeder Push/PR auf
   main, nicht-blockierend neben dem Hauptbuild) — kein Sonderpfad, kein
   Drift-Risiko. `mcr.microsoft.com/mssql/server:2022-latest` braucht
   `ACCEPT_EULA=Y` und ist mit ~1,5 GB Image / 2 GB RAM der schwerste Container
   im Haus; die EULA-Akzeptanz ist im Testcontainers-Setup dokumentiert
   ([`quality.md`](../../user/quality.md)); ins Administrationshandbuch kommt
   MSSQL erst mit nutzersichtbarem Support (ab Slice 1).

## Slice-Schnitt

Dem gewachsenen Muster folgend (Kern zuerst, Ausbau als eigene Slices —
Entscheidung 2):

| Slice | Inhalt | Registrierbar ab / liefert |
| --- | --- | --- |
| **0** ✅ | Scoping-ADR ([ADR 0047](../../adr/0047-mssql-vierter-dialekt-scoping.md)), Gradle-Modul `driver-mssql`, Testcontainers-Spike (Connect + `SELECT @@VERSION`), EULA-Doku, Dependabot-Ignore | — |
| **1** ✅ | `JdbcUrlBuilder` + `SchemaReader`/`TableLister` (Reverse-Read, nur lesen) + `MSSQL`-Enum-Querschnitt + `DialectCommandGate` | ja — `schema reverse` funktioniert |
| **1a** | CLI-E2E-Absicherung in `test/e2e-cli`: Gate-Ablehnungen als Subprozess-E2E (containerlos — generate/export/import/transfer/migrate/profile/`export <tool>` liefern Exit 2 + Gate-Meldung) und `schema reverse`-Subprozess-E2E gegen den Testcontainer | E2E-Netz für den nutzersichtbaren MSSQL-Pfad und die Gates; vor Slice 2, damit Gate-Wegfall pro Slice testgetrieben ist |
| **2** | `DdlGenerator` + Typtabelle NeutralType→T-SQL (Generate-Richtung) | `schema generate --target mssql` |
| **3** | `DataReader`/`DataWriter` (Transfer; Fast-Path später) + sample-db-MSSQL-Leg im Harness (`examples/sample-db`, fetch+compose gemäß [ADR 0013](../../adr/0013-sample-db-sourcing.md)/[ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)): Reverse→Generate→Import-Roundtrip-Smoke als eigener Workflow | `data export/import/transfer` + MSSQL-Smoke in CI |
| **4** | Cross-Dialekt-Matrix, `NeutralTypeCanonicalizer`, Postcompare-Fingerprint, `transferCompatibility` + Cross-Dialekt-sample-db-Smoke (MSSQL↔PG analog `sample-db-cross-smoke`) | Matrix-Gate + Cross-Smoke |
| **5** | Diff/Migrate (`MssqlDiff*Ops` — bei allen Dialekten der größte Brocken) | `schema migrate` |
| **6** | Gefilterte Indizes (WHERE) + clustered/nonclustered-Steuerung, Reverse + Generate + Diff | volle Index-Treue |
| **7** | Partitionierung: Partition Functions + Schemes + Filegroups (Anschluss an `PartitionBoundScanner`/Cross-Dialekt-Muster des PG-Slices) | Partitionstabellen im Round-Trip |
| **8** | Volltext: Full-Text Search (Muster aus dem Fulltext-Slice, `fullTextVectorColumn`-Modell) | Volltext-Indizes Generate + Reverse |
| **9** | Routinen/Trigger: T-SQL-Prozeduren, `CREATE OR ALTER` | Routinen-Migration |
| **10** | Profiling-Modul `driver-mssql-profiling` | `data profile` |

Jeder Slice endet CI-grün und einzeln nutzbar; die No-op-Defaults des Ports
machen das möglich, ohne UNSUPPORTED-Stopgaps (No-Carveouts-Regel). Was ein
Slice noch nicht kann, steht im Plan als späterer Slice — nicht als
else-Zweig versteckt und nicht als ausgeschlossenes Ticket abgelegt.

### Kommando-Verfügbarkeit je Slice (Eigner-Entscheidung 2026-08-21)

Der Enum-Wert `MSSQL` macht ab Slice 1 alle Kommandopfade erreichbar. Damit
kein Pfad einen unfertigen Treiber-Port trifft, weisen Kommandos ohne
gebauten MSSQL-Pfad den Dialekt an ihrer **Kommando-Grenze** ab
(`DialectCommandGate` in `hexagon/application`, Exit 2 mit klarer Meldung;
MCP-Handler übersetzen in ihre Validation-Fehlerform). `when`-Zweige hinter
einem Gate dürfen mit `error("unreachable: …")` auf das Gate verweisen. Der
Slice, der einen Pfad liefert, entfernt sein Kommando aus dem Gate.

| Kommando | MSSQL verfügbar ab | bis dahin |
| --- | --- | --- |
| Verbindungsschicht (`mssql://`-URLs, Pool, SSL) | **Slice 1** | — |
| `schema reverse` (CLI + MCP-Job) | **Slice 1** | — |
| `schema compare` (MCP-Job, via Reverse) | **Slice 1** | — |
| `schema generate` | Slice 2 | Gate (CLI-Runner + MCP-Handler) |
| `export flyway/liquibase/django/knex` (Tool-Export) | Slice 2 | Gate (braucht den `DdlGenerator`) |
| `data export` / `data import` / `data transfer` | Slice 3 | Gate |
| `schema migrate` | Slice 5 | Gate + `MigrateRendererRegistry` → `null` („No renderer registered") |
| `data profile` (CLI + MCP-Job) | Slice 10 | Gate |

## Risiken

- **Reverse-Read-Treue**: `INFORMATION_SCHEMA` reicht bei MSSQL nicht für
  Identity/Defaults/Indizes — es braucht `sys.*`-Katalogsichten. Der
  SQLite-Präzedenzfall (PK-NOT-NULL-Verlust, identifier-Narrowing) zeigt, dass
  Reverse-Fidelity-Fehler erst im Round-Trip auffallen → Round-Trip-Tests ab
  Slice 2, nicht erst in Slice 4.
- **Collation-Semantik** (case-insensitive Default!) berührt Vergleiche im
  Postcompare — der Kanonisierer muss Namensvergleiche dialektbewusst falten.
- **CI-Gewicht** des Containers: mit Entscheidung 3 läuft MSSQL in jedem
  Lauf — die Laufzeit von `integration.yml` ist zu beobachten; wächst sie
  unzumutbar, ist Staffelung (wie `perf-acceptance`) der Ausweichpfad.
- **Kein MSSQL-Wissen in den Goldens**: DDL-Goldens entstehen neu; der
  Regenerier-Weg läuft per CLI (nicht `make golden-update`).
