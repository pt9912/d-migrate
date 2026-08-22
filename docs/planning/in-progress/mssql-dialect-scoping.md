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
>
> **Status-Update 2026-08-22:** Slice 1a umgesetzt — CLI-E2E-Netz in
> `test/e2e-cli`: `MssqlCommandGateE2ETest` (containerlos, echte CLI als
> Kind-Prozess; alle Gate-Kommandos inkl. aller vier `export <tool>` enden
> mit Exit 2 + Gate-Meldung, bevor eine Verbindung versucht wird) und
> `MssqlSchemaReverseE2ETest` (`schema reverse` gegen den SQL-Server-
> Testcontainer: Schema-Datei + Sidecar-Report, Credential-Scrubbing bei
> falschem Passwort). Geteilter Runner `runRealCli` neben dem
> MCP-Subprozess-Plumbing; Dockerfile-`deps`-Stage kennt jetzt auch die
> beiden MSSQL-Module.
>
> **Status-Update 2026-08-22 (4):** Slice 3 (Datenpfad) umgesetzt —
> `MssqlDataReader` (adaptive Pufferung ohne offene Transaktion, Geometrie als
> WKB über `.STAsBinary()`), `MssqlDataWriter` + `MssqlTableImportSession`
> (`SET`-Optionen je Session für gefilterte Indizes, `SET IDENTITY_INSERT` wenn
> der Chunk die Identity-Spalte trägt, `MERGE … OUTPUT $action` für
> `skip`/`update` statt des in T-SQL fehlenden `INSERT IGNORE`,
> `DBCC CHECKIDENT`-Reseed, `truncateTables` mit `NOCHECK CONSTRAINT`),
> `MssqlInsertSql` (reine SQL-Erzeugung, mock-frei prüfbar) und
> `MssqlSchemaSync`. Gate: `data export/import/transfer` entfernt (damit auch
> die Transfer-Naht `preConnectGate` und die MCP-Worker-Vorprüfungen, die nur
> dafür existierten); es bleiben `schema migrate` und `data profile`.
> Geteilter `loadTargetColumns` nimmt jetzt die leere Zeilen-Klausel als
> Parameter (T-SQL kennt kein `LIMIT 0`). Live: `MssqlDataPathIntegrationTest`
> (Identity, MERGE-Modi, Reseed, Geometrie-Round-Trip, FK-sicheres Truncate)
> und E2E `MssqlTransferE2ETest` (PostgreSQL → SQL Server über die echte CLI:
> reverse → generate → sqlcmd-Apply → `data transfer`). Zwei
> `/code-review high`-Runden (8 + 6 Befunde, alle eingearbeitet): fehlendes
> `transferCompatibility` (Interface-Default = strikte Gleichheit, hätte jede
> Typweitung im Preflight abgelehnt), Identity-Seed/Increment und „nie befüllt"
> (`last_value`) aus dem Katalog statt 1/1 geraten, Computed Columns benennend
> abgelehnt, `skip` braucht einen PK (Preflight) **und** die PK-Spalten im
> Chunk, NULL-Geometrie bindet als `varbinary` statt mit dem GEOMETRY-Typcode,
> ein fehlgeschlagenes `SET IDENTITY_INSERT OFF` verwirft die Connection statt
> sie vergiftet zurückzugeben, die `NOCHECK`-Schleife liegt in der
> Fehlerbehandlung, drei-/vierteilige Namen rendern wie im Lesepfad.

> **Status-Update 2026-08-22 (5):** Slice 3b umgesetzt — MSSQL-Leg der
> Sample-DB-Harness: digest-gepinnter `mssql`-Compose-Service, `.env`-Block,
> Connection-Alias `pagila_ms_target`, `examples/sample-db/scripts/smoke-cross-pg2ms.sh`,
> `make sample-db-cross-smoke-pg2ms` und ein Best-Effort-Workflow. Der Smoke
> fährt Pagila PostgreSQL → SQL Server über das echte CLI: reverse → validate →
> generate `--target mssql --split pre-post` → **Anwendung per `sqlcmd`**
> (dem Client, der Batches nur an `GO` trennt) → `data transfer --verify` →
> Per-Tabelle-Parität, Typ-Stichproben und IDENTITY-Treue gegen die gepinnte
> Notes-Baseline `expected/pagila-cross-ms.notes.txt`.
>
> Der Leg fand sofort **zwei Produktdefekte**, die Unit-Tests und die kleinen
> E2E-Schemata nicht zeigten: (1) PostgreSQL erlaubt `ORDER BY` im View-Body,
> SQL Server lehnt die Sicht damit ab (Msg 1033) — der `ViewQueryTransformer`
> meldet ein `ORDER BY` auf oberster Ebene ohne `TOP`/`OFFSET` jetzt als nicht
> portabel (E053) statt ungültiges DDL zu erzeugen; ein eingeschmuggeltes
> `TOP 100 PERCENT` hätte die Sortierung still verworfen. (2) mssql-jdbc liefert
> `DATETIMEOFFSET` als treibereigenes `microsoft.sql.DateTimeOffset`, das bis in
> die `--verify`-Kanonisierung durchlief und **jede** Tabelle mit `timestamptz`
> als „inconclusive" abwies — der Lesepfad hat jetzt eine Wert-Naht
> (`AbstractJdbcDataReader.mapValue`), die daraus `OffsetDateTime` macht; das
> repariert zugleich die Export-Serialisierung.

> **Status-Update 2026-08-22 (2):** Slice 2 umgesetzt — `MssqlDdlGenerator`
> (+ `MssqlTypeMapper`, Spalten-/Index-Helfer) im Treibermodul: Tabellen mit
> benannten DF/UQ/CK/PK-Constraints, Identity, Enum/Domain inline, FKs
> (inkl. zirkulär/aufgeschoben), gefilterte Indizes, native Sequenzen +
> `NEXT VALUE FOR`, `CREATE OR ALTER VIEW`, Spatial-Profil `native`
> (`geography` bei geodätischem SRID 4000–4999/4326, sonst `geometry`;
> Spatial-Index auf `geography` gerendert — Eigner-Einwand 2026-08-22),
> Rollback mit `DROP INDEX … ON`. Nicht gerendert (sichtbar als
> Notes/`skipped_objects`): Routinen/Trigger (E053, Slice 9), Aggregate
> (E054), Partitionierung (E055, Slice 7), planare Spatial-Indizes (E057,
> Slice 6), Volltext-Indizes (E057, Slice 8). Neue Codes W136–W141 (Ledger + Spec). Gate für
> `schema generate` und `export <tool>` entfernt (CLI + MCP); Spatial-Policy
> `mssql` → `native`/`none`; Reverse liest `geometry`/`geography` generisch.
> Goldens `*.mssql.sql` (inkl. pre/post-data, spatial) per CLI erzeugt;
> E2E `MssqlSchemaGenerateE2ETest` (generate + export flyway); Spec
> (`type-mapping.md` §6, `ddl-generation-rules.md` §3.8/§16.9 u. a.) und
> Handbuch nachgezogen. Review (`/code-review high`, 10 Befunde, alle
> eingearbeitet): Schlüssel (UNIQUE/PK) auf LOB-Spalten → E057 statt
> ungültigem DDL; View-Portabilität für mssql (`::`, `||`, `LIMIT` → E053);
> Spatial-Index nur mit PK + genau einer Spalte; Domain-Basistypen über die
> neutrale Typtabelle (PG-Katalognamen) statt Roh-Durchreichen, unauflösbar →
> E053; `VALUE`-Ersetzung literal-bewusst; W138 mit PK-Ausnahme; Identity auf
> nicht identity-fähigem Typ → W140; bracket-bewusster Inverter auch bei
> „ on " im Indexnamen; `SequenceCapabilityDefaults.Mssql` auf Generate-
> Realität (native Sequenzen) gehoben. Zweites Review (10 Befunde, 9
> eingearbeitet): Kaskaden-Zyklus-/Mehrfachpfad-Wächter (`NO ACTION` + E057,
> SQL-Server-Fehler 1785); `SYSDATETIMEOFFSET()` als tz-Default + Reverse-
> Kanonisierung; View-Transformer mit T-SQL-Funktionsmenge, kontextsensitivem
> `LIMIT`-Marker und Bracket-Erkennung für mssql-Quellen; Spatial-Index nur
> mit tatsächlich gerendertem PK; Sequence-`CYCLE` mit expliziter Standard-
> Schranke; `float`-Domain = double; `--split`-FK-Deferral über die Port-
> Fähigkeit `supportsDeferredForeignKeys` (PG + MSSQL). Nebenbefund: Tool-Export-Artefakte
> tragen keine `GO`-Batch-Trenner (Views/Routinen müssen in SQL Server allein
> im Batch stehen) — Ticket siehe unten.

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
| Materialized Views | kein Äquivalent; indizierte Sichten haben andere Semantik und Einschränkungen | **Dauerhafte Lücke** — Generate degradiert zur normalen View (`W103`), Diff blockt |
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
   MSSQL erst mit nutzersichtbarem Support (ab Slice 1) — nachgezogen nach
   Slice 3b (`1d0142e5`) zusammen mit Anwenderhandbuch, Guide,
   Migrations-Leitfaden, Best-Practices und der Release-Artefaktliste.
   Massstab dafuer, was ein Handbuch behaupten darf, ist
   `DialectCommandGate.AVAILABLE_FOR_MSSQL`.

## Slice-Schnitt

Dem gewachsenen Muster folgend (Kern zuerst, Ausbau als eigene Slices —
Entscheidung 2):

| Slice | Inhalt | Registrierbar ab / liefert |
| --- | --- | --- |
| **0** ✅ | Scoping-ADR ([ADR 0047](../../adr/0047-mssql-vierter-dialekt-scoping.md)), Gradle-Modul `driver-mssql`, Testcontainers-Spike (Connect + `SELECT @@VERSION`), EULA-Doku, Dependabot-Ignore | — |
| **1** ✅ | `JdbcUrlBuilder` + `SchemaReader`/`TableLister` (Reverse-Read, nur lesen) + `MSSQL`-Enum-Querschnitt + `DialectCommandGate` | ja — `schema reverse` funktioniert |
| **1a** ✅ | CLI-E2E-Absicherung in `test/e2e-cli`: Gate-Ablehnungen als Subprozess-E2E (containerlos — generate/export/import/transfer/migrate/profile/`export <tool>` liefern Exit 2 + Gate-Meldung) und `schema reverse`-Subprozess-E2E gegen den Testcontainer | E2E-Netz für den nutzersichtbaren MSSQL-Pfad und die Gates; vor Slice 2, damit Gate-Wegfall pro Slice testgetrieben ist |
| **2** ✅ | `DdlGenerator` + Typtabelle NeutralType→T-SQL (Generate-Richtung) | `schema generate --target mssql` |
| **3** ✅ | `DataReader`/`DataWriter` (Transfer; Fast-Path später); **3b** ✅ sample-db-MSSQL-Leg im Harness (`examples/sample-db`, fetch+compose gemäß [ADR 0013](../../adr/0013-sample-db-sourcing.md)/[ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)): Reverse→Generate→Import-Roundtrip-Smoke als eigener Workflow | `data export/import/transfer` + MSSQL-Smoke in CI |
| **4** ✅ | `NeutralTypeCanonicalizer` + Postcompare-Fingerprint-Beleg gegen echtes SQL Server, Spec-Sequenz-Matrix, `transferCompatibility` (bereits mit Slice 3 geliefert) + Cross-Dialekt-sample-db-Smoke in der Gegenrichtung (MSSQL→PG) | Vergleichs-Substrat für Slice 5 + Cross-Smoke |
| **5** | Diff/Migrate (`MssqlDiff*Ops` — bei allen Dialekten der größte Brocken) **inkl. Beitritt zum Cross-Dialekt-Matrix-Sweep** (`test/cross-dialect-matrix`: Renderer und Matrix-Zellen gehören zusammen, sonst entstünden Wegwerf-Carve-outs) und Entscheidung zur Enum-CHECK-Kante ([`enum-inline-check-fidelity.md`](../open/enum-inline-check-fidelity.md)) | `schema migrate` |
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
| `schema generate` | **Slice 2** | — |
| `export flyway/liquibase/django/knex` (Tool-Export) | **Slice 2** | — |
| `data export` / `data import` / `data transfer` | **Slice 3** | — |
| `schema migrate` | Slice 5 | Gate + `MigrateRendererRegistry` → `null` („No renderer registered") |
| `data profile` (CLI + MCP-Job) | Slice 10 | Gate |

## Slice 5 im Detail — Diff/Migrate für MSSQL

### Warum dieser Slice einen Schnitt braucht (gemessen 2026-08-22)

`DiffOperation` hat **42 Arten**. Die drei gebauten Dialekte brauchen dafür
2387 (PostgreSQL, 9 Dateien), 2582 (MySQL, 7) und 1923 Zeilen (SQLite, 6).
Slice 5 ist damit grösser als die Slices 1–4 zusammen und passt weder in einen
Commit noch in einen Review. Der Schnitt unten folgt der Familien-Gliederung,
die der `renderOp`-Dispatch der drei bestehenden Renderer bereits hat — nicht
einer erfundenen Reihenfolge.

### Was Slice 5 ausser dem Renderer anfasst

| Naht | Heute | Nach Slice 5 |
| --- | --- | --- |
| `MigrateRendererRegistry` | `MSSQL -> null` | liefert den Renderer |
| `RenameProjectionCapabilitiesFactory` | `error("unreachable: DialectCommandGate …")` | Spiegelwert in `RenameProjectionDialect` |
| `DialectCommandGate` | `SCHEMA_MIGRATE` gated | Eintrag entfällt (nur `DATA_PROFILE` bleibt) |
| `SequenceCapabilityDefaults` | preserve/atomic `false` | preserve `true` (Sub-Slice 5d) |
| `MatrixSweepRunner` / `MatrixCell.ALL_DIALECTS` | `MSSQL -> null`, nicht im Sweep | Renderer + Zellen (Eigner-Entscheidung Slice 4: beides zusammen) |
| Neutral-Typ-Projektion | `Enum(refType)` bleibt Identität | braucht Schema-Kontext, siehe offene Punkte |

### T-SQL-Eigenheiten, die den Diff-Pfad von den anderen drei trennen

Diese Liste ist der eigentliche Grund, warum Slice 5 nicht „wie PostgreSQL,
nur mit Klammern" ist:

- **Defaults sind benannte Constraint-Objekte, keine Spalteneigenschaft.**
  `ALTER TABLE … ALTER COLUMN` scheitert, solange ein Default-Constraint an der
  Spalte hängt. Jede Typ-/Nullability-Änderung ist also ein Dreischritt:
  `DROP CONSTRAINT df_…` → `ALTER COLUMN` → `ADD CONSTRAINT df_…`. Dass Slice 2
  die Constraints **benannt** rendert (`df_`/`uq_`/`ck_`/`pk_`), war genau die
  Vorleistung dafür — anonyme Constraints wären hier nicht adressierbar.
- **Umbenennen ist `sp_rename`**, kein `ALTER TABLE … RENAME`. Der Aufruf nimmt
  String-Literale (kein Klammer-Quoting) und benennt Constraints und Indizes
  einer umbenannten Tabelle **nicht** mit; deren Namen driften damit von der
  `df_<tabelle>_<spalte>`-Konvention ab.
- **IDENTITY ist per ALTER unveränderlich.** Eine Spalte zu/von IDENTITY zu
  ändern verlangt einen Tabellen-Neubau — die einzige Stelle, an der MSSQL ein
  SQLite-artiges Rebuild-Muster braucht.
- **Gefilterte Indizes brauchen SET-Optionen zur DDL-Zeit** (Msg 1934). Slice 2a
  löst das für die Skript-Darstellung über die Präambel und Slice 3 für die
  Import-Session; der Migrate-Pfad führt Statements **einzeln** über den Runner
  aus und ist von beidem nicht abgedeckt.
- **Der Kaskaden-Wächter muss den Live-Zustand sehen.** `MssqlCascadePathGuard`
  analysiert heute das Soll-Schema. Ein Diff fügt einzelne FKs zu einer
  bestehenden Datenbank hinzu; ob dabei ein Mehrfachpfad entsteht (Fehler 1785),
  entscheidet die Vereinigung aus vorhandenen und neuen FKs.
- **DDL ist transaktional** — wie PostgreSQL, anders als MySQL. Der
  `transactionScope` der gerenderten Statements kann also überwiegend
  `RUNNER_OWNED` bleiben.
- **`CREATE OR ALTER VIEW` gibt es nativ**, `ReplaceView` ist damit billig.

### Sub-Slice-Schnitt

| Sub-Slice | Operationen | Kern der Arbeit | Abnahme |
| --- | --- | --- | --- |
| **5a** ✅ | `CreateTable`, `DropTable`, `RenameTable`, `AddColumn`, `DropColumn`, `RenameColumn`, `AlterColumnType`, `AlterColumnNullability`, `AlterColumnDefault`, `AddPrimaryKey`, `DropPrimaryKey` | Gerüst (Dispatch UP/DOWN, RenderContext, SqlBuilders) + der Default-Constraint-Dreischritt + `sp_rename` | Unit-Tests je Operation und Richtung; Down-Pfad kehrt jede Operation um |
| **5a-2** | — | IDENTITY-Rebuild (create, copy, drop, rename) für `AlterColumnType` von/zu `identifier(auto_increment)`. Aus 5a herausgeschnitten: das ist ein eigener Renderer nach dem Muster der SQLite-Rebuild-Sequenz, kein Zusatz zum Skelett. 5a blockt den Fall laut (`MSSQL_IDENTITY_CHANGE_NEEDS_REBUILD`), statt ein `ALTER COLUMN` zu schicken, das die Identity kommentarlos verlöre | Live-Test, dass Schlüssel und Zähler den Rebuild überleben |
| **5b** | `AddConstraint`, `DropConstraint`, `AddIndex`, `DropIndex` | `WITH CHECK`/`NOCHECK` beim Nachziehen auf Bestandsdaten; SET-Optionen im Migrate-Pfad; Kaskaden-Wächter gegen den Live-Zustand | Live-Integrationstest, der einen **gefilterten** Index per Migrate anlegt (Msg-1934-Regressionsschutz) |
| **5c** | `CreateView`, `ReplaceView`, `DropView`, `RenameView`, `CreateCustomType`, `AlterCustomType`, `DropCustomType` | `CREATE OR ALTER VIEW`; die View-Portabilitätsprüfung aus Slice 3b greift auch hier | Hier fällt die Enum-CHECK-Entscheidung ([`enum-inline-check-fidelity.md`](../open/enum-inline-check-fidelity.md)) — sie ist im Diff-Pfad nicht mehr aufschiebbar |
| **5d** | `CreateSequence`, `AlterSequence`, `DropSequence`, `RenameSequence`, `AlterSequenceCurrentValue` | `ALTER SEQUENCE … RESTART WITH` plus Probe über `sys.sequences`; flippt `supportsCurrentValuePreserve` | Macht die Zeile wahr, die Slice 4 als Zielbild in [`neutral-model-spec.md`](../../../spec/neutral-model-spec.md) Abschnitt 9.1 eingetragen hat |
| **5e** | — | Abschluss: Schema-Kontext für die Typ-Projektion (`Enum(refType)`), Beitritt zum Matrix-Sweep, Registry + `RenameProjectionDialect`, **Gate-Fall**, Live-Round-Trip-Integrationstest analog den drei bestehenden Dialekten, CLI-E2E, Handbücher | `schema migrate` ist für mssql nutzbar |

### Was in Slice 5 bewusst geblockt bleibt

Routinen und Trigger, Partitionierung und die Index-Feinsteuerung gehören den
Ausbau-Slices 9, 7 und 6 — was der gebaute Code über sie heute schon aussagt,
steht bei den jeweiligen Slices weiter unten. Der Diff-Pfad blockt sie bis
dahin, wie es auch die drei bestehenden Dialekte an denselben Stellen tun
(PostgreSQL 11, MySQL 17, SQLite 8 Fälle). Ohne eigenen Slice bleibt nur eine
Fläche: **Materialized Views** haben in SQL Server kein Äquivalent (siehe
T-SQL-Inventar), der Diff-Pfad blockt sie dauerhaft.

### Wann das Gate fällt

Erst mit **5e**. 5a–5d sind interne Zwischenstände: sie enden CI-grün und sind
einzeln reviewbar, aber sie schalten `schema migrate` nicht frei. Der Renderer
aus 5a ist deshalb heute nur über seine Tests erreichbar — `MigrateRendererRegistry`
liefert für mssql weiterhin `null` und das Gate weist das Kommando ab. Ein
Zwischenstand, der das Kommando mit halbem Renderer öffnet, wäre genau der
`UNSUPPORTED`-Stopgap, den Entscheidung 2 ausschliesst. Mit 5e ist mssql dann
auf Augenhöhe mit den anderen drei Dialekten — inklusive der Operationen, die
dort ebenfalls einem späteren Slice gehören.

## Ausbau-Slices 6-10 — was heute schon feststeht

Der Kern (Slices 0-5) lässt diese Flächen bewusst liegen. Damit die Slices
nicht bei null anfangen, hält dieser Abschnitt fest, was der gebaute Code über
sie schon aussagt: was Generate und Reverse heute tun, und was der Diff-Pfad
aus Slice 5 bis dahin mit ihnen macht. Das ist kein Slice-Entwurf, sondern die
Bestandsaufnahme, auf der er aufsetzt.

### Slice 6 — Gefilterte Indizes, clustered/nonclustered, INCLUDE

- **Reverse** liest `is_unique`, `has_filter` und `filter_definition` aus
  `sys.indexes`; INCLUDE-Spalten liest er **nicht** und weist das als `R341`
  aus. Clustered vs. nonclustered wird als generisches `BTREE` gelesen.
- **Generate** rendert gefilterte Indizes bereits (der `WHERE`-Teil kommt aus
  dem neutralen Modell) und immer nonclustered. Genau hier fand der
  sqlcmd-Apply-E2E aus Slice 2a den Msg-1934-Fall.
- **Diff** (Sub-Slice 5b) rendert `AddIndex`/`DropIndex` für das, was das
  neutrale Modell heute trägt — die Feinsteuerung kommt mit diesem Slice.
- Offen ist damit die **Steuerung**: ein neutrales Feld für clustered/INCLUDE,
  plus Reverse und Diff dafür.

### Slice 7 — Partitionierung

- **Generate** meldet eine partitionierte Tabelle als `E055` und legt sie als
  EINE plain Tabelle an; das Sample-DB-Leg belegt das an Pagilas `payment`.
- **Diff** blockt Partitionierungs-Operationen bis dahin.
- SQL Server modelliert Partitionierung über Partition Functions, Schemes und
  Filegroups — strukturell anders als PostgreSQLs Partitionshierarchie. Das
  neutrale Modell trägt diese Objekte heute nicht; das ist der eigentliche
  Umfang des Slices, nicht das Rendern.

### Slice 8 — Volltext

- **Generate** meldet einen Volltext-Index als `E057`; der Spaltentyp
  degradiert nach [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) zu
  `NVARCHAR(MAX)` (`W132`/`W137`).
- SQL Server Full-Text Search ist ein eigener Dienst mit eigener Installation —
  der Container-Spike aus Slice 0 deckt ihn nicht ab. Der Slice braucht also
  zuerst eine Antwort darauf, wogegen er testet.

### Slice 9 — Routinen und Trigger

- **Reverse** liest Routinen-Rümpfe nicht und weist das als `R342` samt
  `skippedObjects` aus.
- **Generate** meldet Funktionen, Prozeduren und Trigger als `E053`.
- **Diff** blockt die zwölf `Create`/`Replace`/`Drop`/`Rename`-Operationen
  dieser drei Objektarten bis dahin — dieselbe Stelle, an der auch die drei
  bestehenden Dialekte blocken.
- `CREATE OR ALTER` gibt es für Prozeduren, Funktionen und Trigger nativ, der
  Replace-Pfad ist also billig; der Aufwand liegt im Reverse (Rumpf lesen) und
  in der Frage, wie viel T-SQL-Rumpf das neutrale Modell tragen soll.

### Slice 10 — Profiling

- `DialectCommandGate` weist `data profile` weiterhin ab; das ist nach Slice 5
  der letzte verbleibende Gate-Eintrag.
- Das Modul folgt dem Muster der drei bestehenden `driver-*-profiling`-Module.

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

## Erledigte Punkte (Slices 2-4)

**Aus Slice 2 und 3:**

- ~~**`GO`-Batch-Trenner im Tool-Export**~~ — Slice 2a: `DdlScript`
  (ports-read) rendert Skripte dialektbewusst (`DialectCapabilities.batchSeparator`,
  mssql = `GO` nach jedem ausführbaren Statement) für `schema generate`-Datei/
  stdout/Split/Rollback/JSON, MCP-Artefakt und `DdlNormalizer` (→ Flyway);
  Liquibase `endDelimiter="GO"`, Django Listenform je Statement, Knex
  unverändert (statementweise). Der E2E `MssqlGenerateApplyE2ETest` wendet das
  Skript per `sqlcmd` an und fand dabei, dass `sqlcmd` mit
  `QUOTED_IDENTIFIER OFF` verbindet und ein gefilterter Index deshalb mit
  Msg 1934 scheitert — seither beginnt die Skript-Darstellung mit einem
  SET-Options-Batch (`DialectCapabilities.scriptPreamble`).
- ~~**SET-Optionen auch im Import-Pfad prüfen**~~ — Slice 3: jede Import-Session
  setzt sie explizit (`MssqlDataWriter.SESSION_SET_OPTIONS`); mssql-jdbc
  verbindet mit `ARITHABORT OFF`, was DML auf einer Tabelle mit gefiltertem
  Index sonst abweist.

**Aus Slice 4** (Vergleichs-Substrat + Gegenrichtung):

- ~~`NeutralTypeCanonicalizer` + Postcompare-Fingerprint~~ — die Projektion ist
  die lebende Komposition `reverse(toSql(t))`, belegt gegen echtes SQL Server
  (`MssqlNeutralTypeCanonicalizerIntegrationTest`,
  `MssqlPostCompareFingerprintIntegrationTest`). Abweichung von den anderen
  Treibern: `Geometry` ist NICHT ausgenommen, weil SQL Server Subtyp und SRID am
  Wert führt und der Reverse sie nicht rekonstruiert — Falten ist dort die
  Speicherrealität.
- ~~Cross-Dialekt-Smoke in der Gegenrichtung~~ — `smoke-cross-ms2pg.sh`
  (`make sample-db-cross-smoke-ms2pg`) mit dreifacher Zeilen-Parität
  PG-Original == SQL Server == PG-Rückziel.
- ~~Spec-Sequenz-Matrix~~ — `neutral-model-spec.md` 9.1/9.2 haben die
  MS-SQL-Server-Spalte.

**Das Gegenrichtungs-Leg fand drei Reverse-Defekte** (alle in Slice 4 behoben),
die kein Unit-Test und kein kleines E2E-Schema gezeigt hatte:

1. Der Unicode-Literal-Präfix `N'…'` blieb im reverse-gelesenen CHECK stehen →
   die Validierung las das `N` als Spaltenbezug und lehnte **jedes**
   MSSQL-Reverse mit einem String-CHECK mit E012 ab.
2. Klammer-Quoting `[col]` blieb im CHECK stehen → jedes andere Ziel scheiterte
   an der Syntax.
3. Der von d-migrate selbst erzeugte `current_date`-Default kam als
   `CONVERT([date],getdate())` zurück (SQL Server speichert seine eigene Form)
   und wurde beim YAML-Round-Trip zum String-Literal → `DEFAULT
   'CONVERT([date],getdate())'` im Zielskript.

Die Regel dahinter steht jetzt in `spec/type-mapping.md` 6.2: der MSSQL-Reverse
liefert **neutrale** Syntax, keine T-SQL-Oberfläche.

## Offene Punkte (Stand nach Slice 4)

**Pflicht für Slice 5 (Migrate-Postcompare):**

- **`Enum(refType)` liefert unter der MSSQL-Projektion falschen Drift.** Der
  Kanonisierer lässt ihn als Identität stehen, weil eine
  `(NeutralType) -> NeutralType`-Projektion die Custom-Types des Schemas nicht
  sieht. T-SQL degradiert einen `refType`-Enum aber immer zu
  `NVARCHAR(width)` + CHECK (und eine Domain zu ihrem Basistyp), der Reverse
  kann den `refType` also nie zurückgeben. Anders als bei PostgreSQL/MySQL —
  die einen echten Custom-Type emittieren und zurücklesen — ist Identität hier
  **nicht** die genaue Projektion, sondern die konservative: sie meldet lieber
  laut Drift, als eine Abflachung zu verstecken. Ein blinder Fold wäre
  genauso falsch (Breite und Basistyp stehen im Schema, nicht im Typ). Auflösen
  heißt: der Projektion Schema-Kontext geben. Muss fallen, bevor
  `schema migrate --execute` für mssql Postcompare fährt.

**Nächste Arbeitsschritte:**

- **Fremde Funktions-Defaults verlieren ihre Funktions-Natur:** das neutrale
  Format kennt nur `current_timestamp`/`current_date`/`current_time`/`gen_uuid`
  (plus `nextval(...)`) als Funktion — jeder andere Default-Text wird beim
  YAML-Round-Trip zum String-Literal
  ([`SchemaNodeStructureParsers`](../../../adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/SchemaNodeStructureParsers.kt)).
  Das ist dialektübergreifend und nicht MSSQL-spezifisch, fiel aber am
  MSSQL-Leg auf. Keinem Slice zugeordnet.
- **SRID-Treue im Datenpfad:** WKB trägt keine SRID, SQL Server führt sie am
  Wert (nicht an der Spalte) — übertragene Geometrien landen mit dem
  Spalten-Default (0 bzw. 4326). Eine SRID-treue Übertragung bräuchte eine
  eigene Projektion (Wert-SRID als Zusatzspalte oder EWKB-ähnliche Kodierung);
  dokumentiert in `spec/type-mapping.md`, keinem Slice zugeordnet.

**Im Slice-Schnitt eingeplant:**

- **Clustered/nonclustered-Steuerung und INCLUDE-Spalten:** Slice 6 (siehe
  Slice-Tabelle oben). Der Reverse liest Indizes heute als `BTREE`, der
  Generate rendert nonclustered — die Steuerung fehlt noch.

**Ohne Slice-Zuordnung, Priorisierung noch zu entscheiden:**

- **Bulk-Fast-Path** (`BULK INSERT`/`SqlServerBulkCopy`): der Slice-Schnitt
  notiert für Slice 3 nur „Fast-Path später", ohne Slice-Nummer. Der Import
  läuft heute über gebatchte `INSERT`s; ein Bulk-Pfad wäre eine
  Durchsatz-Optimierung analog PG-`COPY`.
- **`data import --on-conflict skip` ohne PK:** der Transfer-Pfad lehnt das im
  Preflight ab (`DialectCapabilities.requiresPrimaryKeyForSkip`); der
  Import-Pfad hat an dieser Stelle keinen Schema-Preflight und meldet es erst
  beim Öffnen der Tabelle. Ob er denselben frühen Check bekommt, ist offen.
