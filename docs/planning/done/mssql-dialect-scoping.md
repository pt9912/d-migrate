# Vorabklärung: MS SQL Server als vierter Dialekt (Milestone 1.7.0, vorgezogen)

> **Status:** Abgeschlossen — Slices 0 bis 10 geliefert, Entscheidungen in
> [ADR 0047](../../adr/0047-mssql-vierter-dialekt-scoping.md) festgehalten.
> Was offen blieb, steht als Ticket in `../open/`; siehe
> [Closure](#closure) am Ende.
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
| **5** ✅ | Diff/Migrate (`MssqlDiff*Ops` — bei allen Dialekten der größte Brocken) **inkl. Beitritt zum Cross-Dialekt-Matrix-Sweep** (`test/cross-dialect-matrix`: Renderer und Matrix-Zellen gehören zusammen, sonst entstünden Wegwerf-Carve-outs) und Entscheidung zur Enum-CHECK-Kante ([`enum-inline-check-fidelity.md`](../open/enum-inline-check-fidelity.md)) | `schema migrate` |
| **6** ✅ | Gefilterte Indizes (WHERE) + clustered/nonclustered-Steuerung + INCLUDE-Spalten, Reverse + Generate + Diff | volle Index-Treue |
| **7** ✅ | Partitionierung: Partition Functions + Schemes + Filegroups (Anschluss an `PartitionBoundScanner`/Cross-Dialekt-Muster des PG-Slices) | Partitionstabellen im Round-Trip |
| **8** ✅ | Volltext: Full-Text Search (Muster aus dem Fulltext-Slice, `fullTextVectorColumn`-Modell) | Volltext-Indizes Generate + Reverse |
| **9** ✅ | Routinen/Trigger: T-SQL-Prozeduren, `CREATE OR ALTER` | Routinen-Migration |
| **10** ✅ | Profiling-Modul `driver-mssql-profiling` | Live belegt gegen echtes SQL Server. Das `DialectCommandGate` ist damit ohne Kommando und **entfernt** — kein Dialekt wird mehr an der Kommando-Grenze abgewiesen |

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
| `MatrixSweepRunner` / `MatrixCell.ALL_DIALECTS` | `MSSQL -> null`, nicht im Sweep | Renderer verdrahtet, mssql in der Dialektliste; zwei Zellen als Carve-out (siehe unten) |
| Neutral-Typ-Projektion | `Enum(refType)` bleibt Identität | braucht Schema-Kontext, siehe offene Punkte |

### T-SQL-Eigenheiten, die den Diff-Pfad von den anderen drei trennen

Diese Liste ist der eigentliche Grund, warum Slice 5 nicht „wie PostgreSQL,
nur mit Klammern" ist:

- **Defaults sind benannte Constraint-Objekte, keine Spalteneigenschaft.**
  `ALTER TABLE … ALTER COLUMN` scheitert, solange ein Default-Constraint an der
  Spalte hängt. Jede Typ-/Nullability-Änderung ist also ein Dreischritt:
  Default lösen → `ALTER COLUMN` → Default zurück. Dass Slice 2 die Constraints
  **benannt** rendert (`df_`/`uq_`/`ck_`/`pk_`), ist dafür die Vorleistung auf
  der Anlege-Seite. Zum **Lösen** reicht die Konvention aber nicht: ein Schema,
  das d-migrate nicht angelegt hat, trägt SQL Servers Auto-Namen
  (`DF__tabelle__spalte__1A2B3C4D`, zufälliges Suffix) — und fremde Datenbanken
  zu migrieren ist der Zweck des Kommandos. Der Renderer schlägt den Namen
  deshalb im Katalog nach (`sys.default_constraints` bzw. `sys.key_constraints`)
  und führt das `DROP CONSTRAINT` über `sp_executesql` aus. Das ist das erste
  dynamische SQL in einem gerenderten Migrationsstatement; es interpoliert
  ausschliesslich Katalogwerte, gequotet über `QUOTENAME`.
- **Umbenennen ist `sp_rename`**, kein `ALTER TABLE … RENAME`. Der Aufruf nimmt
  String-Literale (kein Klammer-Quoting) und benennt Constraints und Indizes
  einer umbenannten Tabelle **nicht** mit; deren Namen driften damit von der
  `df_<tabelle>_<spalte>`-Konvention ab.
- **IDENTITY ist per ALTER unveränderlich.** Eine Spalte zu/von IDENTITY zu
  ändern verlangt einen Tabellen-Neubau — die einzige Stelle, an der MSSQL ein
  SQLite-artiges Rebuild-Muster braucht.
- **Constraint-Namen sind schema-global, nicht tabellenlokal.** Sie liegen in
  `sys.objects`; `pk_users` gibt es im Schema genau einmal. Das trifft nur den
  Neubau, aber den hart: seine Zwischentabelle darf keinen benannten Constraint
  tragen, solange die alte Tabelle lebt (Msg 2714). Indexnamen sind davon
  ausgenommen — die sind tabellenlokal.
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
| **5a-2** ✅ | — | IDENTITY-Rebuild (create, copy, drop, rename) als eigener Renderer (`MssqlRebuildPlanner`/`MssqlRebuildRenderer`) nach dem Muster der SQLite-Rebuild-Sequenz. **Der Auslöser wurde beim Bau breiter als geplant** (siehe unten): nicht nur `identifier(auto_increment)` von/zu, sondern jede Typänderung an einer Spalte, die in SQL Server als IDENTITY landet — auch die aus `generation` | Live-Test, dass Schlüssel und Zähler den Rebuild überleben — erbracht (`MssqlDiffCatalogLookupIntegrationTest`, Werte 7/42 bleiben, die nächste Zeile bekommt 43) |
| **5b** ✅ | `AddConstraint`, `DropConstraint`, `AddIndex`, `DropIndex` | `WITH CHECK` beim Nachziehen auf Bestandsdaten (ohne das gilt ein nachtraeglicher FK/CHECK als *not trusted*); SET-Optionen im Migrate-Pfad; Kaskaden-Wächter gegen den Zielzustand statt gegen das Generate-Schema. Dazu die beiden Stellen, die 5a deswegen blockte: `CreateTable` rendert seine Indizes wieder, und abhängige Indizes und Constraints werden um eine Spaltenänderung herum abgeräumt und neu angelegt | Live-Integrationstest, der einen **gefilterten** Index per Migrate anlegt (Msg-1934-Regressionsschutz) — belegt zugleich, dass die SET-Optionen im selben Batch wirken |
| **5c** ✅ | `CreateView`, `ReplaceView`, `DropView`, `RenameView`, `CreateCustomType`, `AlterCustomType`, `DropCustomType` | `CREATE OR ALTER VIEW` (ein Statement, kein Fenster); Portabilitätsprüfung wie im Generate-Pfad; Custom Types haben in T-SQL kein Objekt — `AlterCustomType` fächert stattdessen auf jede nutzende Spalte auf | Unit-Tests je Operation und Richtung; die Enum-CHECK-Entscheidung fällt **nicht** hier, sondern mit 5e (siehe unten) |
| **5d** ✅ | `CreateSequence`, `AlterSequence`, `DropSequence`, `RenameSequence`, `AlterSequenceCurrentValue` | `ALTER SEQUENCE … RESTART WITH` plus Probe über `sys.sequences`; `supportsCurrentValuePreserve` steht auf `true` | Live-Test pinnt die gemessene Sequenz-Semantik; die Zeile aus [`neutral-model-spec.md`](../../../spec/neutral-model-spec.md) Abschnitt 9.1 ist wahr. **Pipeline-Verdrahtung bleibt bei 5e** (siehe dort) |
| **5e** ✅ | — | Abschluss. **Erledigt:** `RenameProjectionDialect`-Eintrag samt Rename-Abhängigkeitspolitik + `SequencePreserveStage`-Dialektliste, Schema-Kontext für die Typ-Projektion (`Enum(refType)`), Renderer-Registry, **Gate-Fall**, CLI-E2E (der Ablehnungsfall ist in einen Funktionsnachweis gekippt), Live-Round-Trip über die echten Runner. **Vorbedingung erfüllt:** [`fingerprint-v8-enum-check-projection.md`](../done/fingerprint-v8-enum-check-projection.md) gebaut ([ADR 0048]). Matrix-Sweep beigetreten, Handbücher berichtigt | `schema migrate` ist für mssql nutzbar |

### Wie der Neubau aussieht (gebaut in 5a-2)

Die Sequenz folgt dem, was auch SSMS für einen Tabellen-Neubau schreibt, und
sie fällt genau so aus, weil Constraint-Namen schema-global sind:

1. eingehende Fremdschlüssel lösen (sonst lehnt SQL Server das `DROP TABLE` mit
   Msg 3726 ab),
2. `CREATE TABLE <tabelle>__dmg_rebuild_<hash>` — **nur Spalten**: Typ,
   `IDENTITY`, `NULL`/`NOT NULL`, kein einziger benannter Constraint,
3. `SET IDENTITY_INSERT … ON` + `INSERT … SELECT` + `OFF` in **einem**
   Statement (der Schalter ist sitzungsweit; ein abgebrochener Lauf dürfte ihn
   nicht offen lassen),
4. `DROP TABLE`, dann `sp_rename`,
5. die gesamte benannte Oberfläche unter ihren **endgültigen** Namen: PK,
   `df_`/`uq_`/`ck_`, Fremdschlüssel, Indizes, eingehende Fremdschlüssel.

**Der Auslöser ist breiter als der Schnitt vermuten ließ.** Geplant war
„von/zu `identifier(auto_increment)`". Beim Bau fiel auf, dass 5a damit einen
Fall offen ließ, der ungültiges T-SQL erzeugte: eine Typänderung an einer
Spalte, die IDENTITY **bleibt** (`int identity` → `bigint identity`), lief in
`alterColumnWithDefaultDance` und rendete ein `ALTER COLUMN`, das SQL Server
mit Msg 156 ablehnt — eine IDENTITY-Spalte lässt sich überhaupt nicht neu
deklarieren. Dazu kommt, dass IDENTITY nicht nur aus dem Typ stammt, sondern
ebenso aus `generation`; der Auslöser muss deshalb die Spalte sehen, nicht nur
den Typ. Beides deckt der Neubau jetzt ab.

Zwei weitere Entscheidungen sind nicht offensichtlich:

- **Woher die Spaltendeklaration kommt.** Nicht aus einer zweiten, für den
  Neubau geschriebenen Kopie, sondern aus dem Spalten-Helfer des
  Generate-Pfads. Der liefert seit 5a-2 Deklaration und benannte Objekte
  getrennt (`MssqlColumnConstraintHelper.renderColumn`), beide aus derselben
  Liste. Die Frage „welche Objekte hat diese Spalte" — kein UNIQUE auf LOB,
  CHECK nur bei Enum und Domain, kein DEFAULT auf IDENTITY — wird damit
  weiterhin an genau einer Stelle beantwortet. Die Generate-Ausgabe ist
  zeichengleich geblieben (DDL-Goldens unverändert).
- **Warum der Neubau die übrigen Operationen seiner Tabelle schluckt.** Er legt
  die Tabelle im Zielzustand an; ein danach noch laufendes `CREATE INDEX` für
  einen Index, den er schon angelegt hat, scheiterte mit Msg 1913 — T-SQL kennt
  kein `IF NOT EXISTS` für Indizes. Der Eimer läuft an der Stelle seiner
  **letzten** Operation, damit alles erledigt ist, was der Planner davor
  einsortiert hat.

**Die eingehenden Fremdschlüssel waren der Fehlerherd** (sieben Review-Runden,
in jeder ein Befund darin). Tragfähig ist erst die Trennung zwischen dem, was
ein Schema *führt*, und dem, was zur Laufzeit *dasteht*: abgeräumt wird, was
der Ausgangszustand kennt plus alles, was eine schon gerenderte Operation oder
ein früherer Neubau angelegt hat; wiederhergestellt davon, was das Ziel weiter
vorsieht, plus die Fremdschlüssel, deren eigene Operation der Eimer absorbiert
hat. Der RenderContext führt dafür Buch (`noteRendered`/`noteRebuilt`), und
zwar auch ohne Neubau — der Spaltentanz stellt dieselbe Frage. Es gibt **drei**
Erzeuger, und sie rendern unterschiedliche Modellformen: der Neubau beide, ein
`CreateTable` nur die Constraint-Liste, ein `AddConstraint` genau seinen einen.

Eine neue Spalte, die der Neubau nicht füllen kann (NOT NULL, kein Default),
blockt ihn — `MSSQL_REBUILD_COLUMN_NOT_FILLABLE`; der Default-Constraint
existiert während der Kopie noch nicht, der Wert muss also im `SELECT` stehen.
Ebenso blockt `--strict-gap-operations`: zwischen `DROP` und `sp_rename` fehlt
die Tabelle.

Was **nicht** über den Neubau geht: die Nullability einer IDENTITY-Spalte. SQL
Server kennt keine nullable IDENTITY-Spalte, ein Neubau schriebe sie wieder als
`NOT NULL` und verschluckte die Abweichung still. Der Fall bleibt ein Blocker,
jetzt unter `MSSQL_IDENTITY_COLUMN_NOT_NULLABLE` und mit dem echten Grund.

### Was 5c gekostet hat — und wo die Enum-Entscheidung wirklich fällt

Die Sichten waren der billige Teil: `CREATE OR ALTER VIEW` gibt es nativ,
`ReplaceView` ist damit **ein** Statement ohne Fenster, in dem die Sicht fehlt.
Materialized Views bleiben dauerhaft geblockt (kein Äquivalent), Rümpfe werden
nicht übersetzt (E053 wie im Generate-Pfad), und `sp_rename` lässt den
gespeicherten Rumpf in `sys.sql_modules` auf dem alten Namen stehen — für SQL
Server folgenlos, für den Reverse nicht (`MSSQL_RENAME_KEEPS_VIEW_BODY`).

Die Custom Types waren der teure. T-SQL hat für Enum und Domain **kein
Objekt**: der Generate-Pfad löst beide an der Spalte auf. Anlegen und Löschen
sind damit gegenstandslos — bezahlt wird beim Ändern. Wo PostgreSQL
`ALTER TYPE … ADD VALUE` kennt, trägt in SQL Server jede nutzende Spalte ihre
eigene Breite und ihren eigenen CHECK; `AlterCustomType` fächert deshalb auf
und führt für jede Spalte denselben Tanz wie eine gewöhnliche Typänderung.

**Drei Defekte aus 5a sind dabei aufgefallen** und mitbehoben worden, alle mit
derselben Ursache — der Diff-Pfad hatte etwas selbst gerechnet, statt den
Spalten-Helfer des Generate-Pfads zu fragen:

1. Der generierte `ck_<t>_<c>` steht in keiner Modell-Liste; der
   Abhängigkeits-Tanz sah ihn nicht und ließ ihn vor `ALTER COLUMN` stehen
   (Msg 5074).
2. `ALTER COLUMN` auf eine Enum-Spalte rendete `NVARCHAR(MAX)` statt der
   begrenzten Breite — die Spalte wäre danach nicht mehr schlüsselfähig
   gewesen und hätte von `schema generate` abgewichen.
3. Eine Operation ohne Down-Risikoprofil ließ `emit` mit einer Exception
   scheitern, statt einen Blocker zu liefern.

**Die Enum-CHECK-Entscheidung** ([`enum-inline-check-fidelity.md`](../open/enum-inline-check-fidelity.md))
fällt entgegen der ursprünglichen Zeile **nicht** in 5c. Ob der Diff-Pfad den
CHECK rendert, war nie offen: er tut es seit 5a, weil `CreateTable` und
`AddColumn` den Spalten-Helfer nutzen. Offen ist der Round-Trip — der Reverse
liest den CHECK als eigenständigen Constraint zurück, den das authored Schema
nicht hat. Das trifft den Postcompare, und der läuft erst mit
`schema migrate --execute` in **5e**.

### Was 5d gemessen hat

Zwei Eigenheiten der SQL-Server-Sequenzen entscheiden über den Preserve-Pfad
und stehen in keinem Handbuch so:

- `sys.sequences.current_value` trägt den zuletzt ausgegebenen Wert — bei einer
  **nie benutzten** Sequenz aber den Startwert, und der erste `NEXT VALUE FOR`
  gibt genau diesen zurück, ohne `current_value` zu bewegen. „Frisch" und
  „einmal benutzt" sind daran nicht zu unterscheiden. Fortgesetzt wird deshalb
  bei `current_value` + Schrittweite: ein übersprungener Wert ist folgenlos,
  ein doppelt vergebener nicht.
- `ALTER SEQUENCE … RESTART WITH` schreibt auch `start_value` um. Ein Reverse
  nach dem Fortsetzen meldet den fortgesetzten Wert als Startwert
  (`MSSQL_RESTART_REWRITES_START`).

Dazu die Grenze, die `ALTER SEQUENCE` in T-SQL hat: **der Startwert ist
unveränderlich.** Ändert das Schema ihn, wendet der Renderer die übrigen
Attribute an und meldet `MSSQL_SEQUENCE_START_IMMUTABLE`, statt die Abweichung
stehen zu lassen. Beides ist als Live-Test festgehalten, nicht als Kommentar.

**Was 5d bewusst NICHT anfasst:** `SequencePreserveStage` schließt mssql heute
an seiner Dialekt-Liste aus, und `SequenceObjectRef` braucht einen
`RenameProjectionDialect`-Eintrag, den es für mssql noch nicht gibt. Beides
gehört zur Pipeline-Verdrahtung von 5e — bis dahin beschreibt die Capability,
was der Renderer **ausdrücken** kann, wie bei den anderen drei Dialekten auch.
Die Atomic-Preserve-Fähigkeiten bleiben `false`: dafür wäre eine eigene
Sperrstrategie zu entwerfen, und die ist weder entworfen noch belegt.

### Abschluss von 5e

**Matrix-Sweep.** mssql steht in `MatrixCell.ALL_DIALECTS`, der
`MatrixSweepRunner` in
[`test/cross-dialect-matrix`](../../../test/cross-dialect-matrix) liefert den
`MssqlDiffDdlGenerator`. Acht Zellen sind mit Fixtures gepinnt; sechs davon
laufen für mssql gegen den echten Renderer und halten auf Anhieb. Die zwei
übrigen fielen durch, und beide waren korrektes Verhalten statt einer Lücke:
`D.3/mssql/positive` (Materialized View) und `E.2/mssql/positive` (Trigger)
enden mit Exit 8 statt 0, weil der Renderer sie blockt — mit einer Diagnose,
die benennt warum. Sie stehen jetzt als Carve-out in `fixtures/carve-outs.yaml`:
die MV-Zelle dauerhaft wie bei MySQL und SQLite, die Trigger-Zelle vorläufig,
denn sie wird pinnbar, sobald der Reverse Rümpfe liest. Die ungepinnten
Workstreams deckten die bestehenden `dialect: "*"`-Einträge bereits ab.

**Handbücher.** `DialectCommandGate.AVAILABLE_FOR_MSSQL` ist die autoritative
Liste dafür, was `docs/user/` behaupten darf. Nachgezogen sind die zwei Stellen
des Anwenderhandbuchs, an denen `schema migrate` steht — Gültigkeitsbereich und
FAQ — sowie die Dialekttabellen beider READMEs; ausstehend ist dort nur noch
`data profile`. Das Administrationshandbuch braucht nichts: seine
SQL-Server-Stellen betreffen Verbindungsform, Timeouts und SSL, keine
Kommando-Verfügbarkeit.

### Was in Slice 5 bewusst geblockt bleibt

Routinen und Trigger, Partitionierung und die Index-Feinsteuerung gehören den
Ausbau-Slices 9, 7 und 6 — was der gebaute Code über sie heute schon aussagt,
steht bei den jeweiligen Slices weiter unten. Der Diff-Pfad blockt sie bis
dahin — anders als PostgreSQL, das Routinen und Trigger im Diff rendert
(siehe Slice 9 unten); MSSQL kann das erst, wenn sein Reverse die Rümpfe liest.
Ohne eigenen Slice bleibt eine Fläche: **Materialized Views** haben in SQL Server kein Äquivalent (siehe
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

- **Reverse** las `is_unique`, `has_filter` und `filter_definition` aus
  `sys.indexes`; INCLUDE-Spalten wies er als `R341` aus, clustered vs.
  nonclustered fiel auf ein generisches `BTREE` zusammen. Beides ist mit 6b
  gebaut, `R341` entfällt.
- **Generate** rendert gefilterte Indizes bereits (der `WHERE`-Teil kommt aus
  dem neutralen Modell) und immer nonclustered. Genau hier fand der
  sqlcmd-Apply-E2E aus Slice 2a den Msg-1934-Fall.
- **Diff** (Sub-Slice 5b) rendert `AddIndex`/`DropIndex` für das, was das
  neutrale Modell heute trägt — die Feinsteuerung kommt mit diesem Slice.

#### Die Index-Fläche über alle vier Dialekte

Der Slice ist keine T-SQL-Aufgabe, sondern eine Modellaufgabe. Diese Tabelle
hält fest, was jeder Dialekt ausdrücken kann und was das neutrale Modell davon
heute trägt — damit der Schnitt nicht wieder an einer Eigenschaft scheitert,
die erst beim Bauen auffällt:

| Eigenschaft | PostgreSQL | MySQL | SQLite | SQL Server | Neutrales Modell |
| --- | --- | --- | --- | --- | --- |
| Unique | ✓ | ✓ | ✓ | ✓ | `unique` |
| Sortierrichtung je Spalte | ✓ | ✓ | ✓ | ✓ | `IndexColumn.direction` |
| Teilindex (`WHERE`) | ✓ | — | ✓ | ✓ (gefiltert) | `where` |
| Präfixlänge | — | `col(n)` | — | — | `IndexColumn.prefixLength` |
| Zugriffsmethode | btree/gin/gist/brin/spgist | btree/hash | — | — | `type` |
| Volltext-Details | tsvector + GIN/GiST | nativ | FTS5 | Slice 8 | `fullText*` |
| **INCLUDE-Spalten** | ✓ (ab 11) | — | — | ✓ | **fehlt** |
| **clustered-Steuerung** | — | implizit am PK | implizit `rowid` | ✓ explizit | **fehlt** |

Zwei Lücken also, und sie sind verschieden geartet. **INCLUDE** ist echt
cross-dialektal (PostgreSQL und SQL Server), **clustered** trägt nur SQL Server
explizit.

#### Was beim Lesen des Bestands auffiel

`MssqlMetadataQueries.scanIndexes` liest `is_included_column` bereits und trennt
Schlüssel- von eingeschlossenen Spalten; nur trägt die Projektion die Namen der
betroffenen Indizes ausschließlich für die `R341`-Meldung und wirft die Spalten
danach weg. Der Reverse-Anteil des Slices ist deshalb klein: die Spalten ins
Modell durchreichen und `i.type` mitlesen.

Größer ist eine Kante, die weder in der Slice-Zeile noch in dieser Rubrik stand:
**`clustered` ist in SQL Server keine reine Index-Eigenschaft.** Der clustered
Index *ist* die Ablage der Tabelle, es gibt höchstens einen, und der Primary Key
bekommt ihn per Default. Ein Modell, das `clustered` nur am Index trägt, kann
deshalb DDL erzeugen, die der Server ablehnt (Msg 1902, „Cannot create more than
one clustered index"), sobald ein Index clustered sein will, während der PK es
schon ist. Das neutrale `TableDefinition.primaryKey` ist heute eine blanke
`List<String>` — es hat keinen Platz für „dieser PK ist nonclustered".

Auflösen lässt sich das ohne Eingriff in das PK-Modell, weil die Bedingung
*herleitbar* ist: trägt irgendein Index der Tabelle `clustered`, dann rendert der
MSSQL-Pfad den PK als `PRIMARY KEY NONCLUSTERED`. Der Rückweg ist eindeutig — der
Reverse vermerkt `clustered` genau dann am Index, wenn der clustered Index nicht
der PK ist. Beide Richtungen schließen, und das Modell braucht ein einziges Feld
statt eines aufgebohrten Primärschlüssels.

#### Entscheidung: `clustered` wird verglichen und ausgeführt (Eigner, 2026-08-28)

Zur Wahl standen drei Grade. **Generate-only** — wie `fullTextAccessMethod`
getragen, aber aus der Vergleichs-Semantik ausgeschlossen — wurde verworfen,
weil es denselben blinden Fleck erzeugt hätte, den die Enum-CHECK-Kante gerade
gekostet hat: `schema compare` meldete zwei Schemata als gleich, die es nicht
sind. **Erkennen, aber blocken** wurde ebenfalls verworfen.

Gewählt ist die volle Treue: Comparator und Fingerprint sehen `clustered` und
`includeColumns`, und der Diff-Pfad führt den Unterschied aus. Das ist der
teuerste der drei Wege, und der Preis steht hier, damit er beim Bauen niemanden
überrascht — das Umschalten des Primärschlüssels auf `NONCLUSTERED` verlangt,
ihn zu verwerfen und neu anzulegen, und SQL Server baut dabei jeden
nonclustered Index der Tabelle neu:

```sql
ALTER TABLE [orders] DROP CONSTRAINT [PK_orders];
ALTER TABLE [orders] ADD CONSTRAINT [PK_orders] PRIMARY KEY NONCLUSTERED ([id]);
DROP INDEX [idx_orders_date] ON [orders];
CREATE CLUSTERED INDEX [idx_orders_date] ON [orders] ([date]);
```

Die Reihenfolge ist nicht frei wählbar: der clustered PK muss fallen, bevor ein
anderer Index clustered werden darf, sonst antwortet der Server mit Msg 1902.

`includeColumns` geht denselben Weg, ist aber unkritischer — INCLUDE-Spalten
ändern nur den Index, nicht die Ablage der Tabelle.

Für die drei anderen Dialekte gilt die bekannte Abstufung: PostgreSQL rendert
`INCLUDE` nativ (ab 11) und kennt kein `clustered`; MySQL und SQLite können
beides nicht und lassen es mit einer Warnung fallen. Damit der Cross-Dialekt-
Vergleich daraus keine Drift macht, gehören beide Felder in die dialekt-bewusste
Kanonisierung ([ADR 0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md)) — dasselbe
Muster wie beim Enum-Wertevorrat.

#### Was an Slice 6 offen ist (Review, 2026-08-28)

Der Schnitt hat „Generate" als **eine** Naht behandelt. Jeder Dialekt hat zwei:
den `DdlGenerator` und den Diff-Builder. Daraus folgen die offenen Punkte:

| Offen | Wirkung |
| --- | --- |
| ~~`PostgresDiffSqlBuilders.createIndexSql` rendert kein `INCLUDE`~~ ✅ | behoben: die Klausel steht jetzt in `PostgresIndexClauses` und wird von beiden Pfaden benutzt; ein Paritätstest stellt Generate und Diff gegeneinander |
| ~~PG-Reverse liest über `unnest(ix.indkey)` ohne Schnitt bei `indnkeyatts`~~ ✅ | behoben: `FILTER (WHERE k.n <= ix.indnkeyatts)` trennt Schlüssel- von eingeschlossenen Spalten; live gegen PG 16 belegt |
| ~~`MssqlSchemaReader` verwirft ungefilterte Unique-Indizes samt beider Felder~~ ✅ | behoben: ein Unique-Index, der die Ablage beansprucht oder Spalten einschließt, ist als Constraint nicht ausdrückbar und bleibt Index |
| ~~W142/W143 hängen nur an den Generate-Helfern~~ ✅ | behoben: alle drei Diff-Pfade melden dieselben Codes wie ihr Generate-Pfad, über eine geteilte Abbildung `TransformationNote → DiffDiagnostic` in `driver-common` |
| ~~`DiffPlanner.endpoint()` rechnet ohne `canonicalizeIndex`~~ ✅ | behoben: die Projektion wird durchgereicht, Parität in `DiffPlannerCanonicalizationTest` festgehalten |
| ~~`emitStorageFlip` löst keine eingehenden Fremdschlüssel~~ ✅ | behoben: gelöst und wiederhergestellt, aus **beiden** Zuständen — auch ein Fremdschlüssel, den dieselbe Migration kurz zuvor anlegt, hält den Schlüssel fest |
| ~~Umbenannter clustered Index ist `indicesRemoved` + `indicesAdded`~~ ✅ | behoben: die Ablage-Freigabe ordnet die Übernahme; live gegen SQL Server belegt |

Vorrang hatte davor ein **älterer, dialektübergreifender** Defekt, den derselbe
Review zutage gebracht hat: `OperationIdFactory.makeId` stellt die Operationsart
voran, und `TopologicalSorter.stableOrder` bricht den Gleichstand über die ID.
Damit stand `AddPrimaryKey` vor `DropPrimaryKey` und `AddConstraint` vor
`DropConstraint` — deterministisch, nicht zufällig. **Behoben** über eine
Abhängigkeitskante an allen drei Paaren, in `OperationMapper` wie in
`RenameIntraObjectDeltaSynthesizer`.

Der Live-Beleg fiel je Dialekt verschieden aus, und der Unterschied ist der
Rede wert: PostgreSQL, MySQL und SQL Server rendern `ALTER`-Anweisungen, dort
ist die Reihenfolge unmittelbar sichtbar. **SQLite** behandelt Primärschlüssel-
und Constraint-Änderungen als Rebuild-Auslöser und absorbiert sie
(`SqliteRebuildPlanner.isAbsorbedByRebuild`) — die Tabelle wird aus der
Zieldefinition neu gebaut, eine Add/Drop-Reihenfolge erreicht das SQL nie.
Index-Operationen stehen dort ausdrücklich **nicht** in der Absorptionsliste;
der SQLite-Beleg prüft deshalb einen geänderten Index. Ein Test, der dort einen
PK-Wechsel geprüft hätte, wäre grün geworden, ohne die Kante je zu berühren.

#### Nebenbefund: der W-Code-Ledger trug einen veralteten Namen

Beim Registrieren von W142/W143 fiel auf, dass `ledger/warn-code-ledger-0.9.9.yaml`
seit der 1.0.x-Linie weiterbeschrieben wurde — die MSSQL-Codes W136–W141 kamen auf
demselben Weg hinein. [`spec/ledger.md`](../../../spec/ledger.md) verlangt das
Gegenteil: die Dateien einer älteren Version werden nicht verändert, neue Codes
stehen in denen der neuen. Eigner-Entscheidung: Konvention bleibt, neue Datei
`warn-code-ledger-1.1.0.yaml` mit dem fortgetragenen Stand; 0.9.9 ist eingefroren.

`CodeLedgerValidationTest` prüft die Ledger-Dateien **namentlich**, nicht per
Verzeichnis-Scan — eine neue Datei stünde also ungeprüft da. Der Test hat deshalb
einen 1.1.0-Block bekommen, samt Zusicherung, dass die neue Datei den Stand der
alten vollständig fortträgt. Die bekannte Lücke der W100er-Serie wandert
unverändert mit; sie gehört zu
[`warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md).

#### Sub-Slice-Schnitt

| Sub-Slice | Inhalt | Endet mit |
| --- | --- | --- |
| **6a** ✅ | Neutrales Modell: `IndexDefinition.clustered` + `includeColumns`, Serialisierung samt `spec/schema.json`, [ADR 0049](../../adr/0049-abdeckende-und-clustered-indizes-im-neutralen-modell.md) für die Vergleichs-Semantik, Fingerprint `v9`, Kanonisierung je Dialekt über `DialectCapabilities` | Modell trägt beides, Cross-Dialekt-Vergleich driftet nicht |
| **6b** ✅ | MSSQL-Reverse: `i.type` mitlesen, INCLUDE-Spalten durchreichen statt sie für `R341` zu zählen; `R341` entfällt. Die Sortierung braucht `ic.index_column_id` als drittes Kriterium — eingeschlossene Spalten haben alle `key_ordinal = 0`, ihre Reihenfolge wäre sonst unbestimmt | `schema reverse` liest die volle Index-Treue |
| **6c** ✅ | Generate: MSSQL rendert `CLUSTERED`/`INCLUDE` und leitet `PRIMARY KEY NONCLUSTERED` über `MssqlClusteredStorage` her (vier Stellen, eine Quelle); PostgreSQL rendert `INCLUDE`; MySQL/SQLite degradieren mit W142/W143. Die eingeschlossenen Spalten werden **nicht** an den Schlüssel gehängt — das änderte bei `unique` die Eindeutigkeit | `schema generate` gibt zurück, was der Reverse gelesen hat |
| **6d** ✅ | Diff: `AddIndex`/`DropIndex` tragen die neuen Felder (kostenlos, weil ein geänderter Index schon als Drop+Add abgebildet wird und beide denselben Renderer nutzen). Der Wechsel der Ablage kommt aus `MssqlClusteredStorage.flip` und rendert den Primärschlüssel **vor** dem übernehmenden und **nach** dem abgebenden Index. Dazu ein Blocker (`E066`) für den Fall, den das neutrale Modell ausdrücken kann und T-SQL nicht: zwei Indizes, die dieselbe Ablage beanspruchen | `schema migrate` führt den Wechsel aus |

6a ist die einzige Stufe, die das Hexagon anfasst; 6b–6d hängen daran und
sind je für sich CI-grün abschließbar.

### Slice 7 — Partitionierung

- **Generate** meldet eine partitionierte Tabelle als `E055` und legt sie als
  EINE plain Tabelle an; das Sample-DB-Leg belegt das an Pagilas `payment`.
- **Reverse** liest Partitionierung für SQL Server gar nicht.
- **Diff** blockt Partitionierungs-Operationen bis dahin.

#### Die Partitionierungs-Fläche über alle vier Dialekte

Der Bruch ist strukturell, nicht syntaktisch. PostgreSQL und MySQL partitionieren
eine Tabelle in **benannte Kinder**; SQL Server beschreibt stattdessen zwei
eigenständige Schemaobjekte und hängt die Tabelle daran:

```sql
CREATE PARTITION FUNCTION pf_orders (date) AS RANGE RIGHT FOR VALUES ('2024-01-01', '2025-01-01');
CREATE PARTITION SCHEME  ps_orders AS PARTITION pf_orders TO ([PRIMARY], [PRIMARY], [PRIMARY]);
CREATE TABLE orders (…) ON ps_orders (placed_on);
```

| Eigenschaft | PostgreSQL | MySQL | SQL Server | Neutrales Modell |
| --- | --- | --- | --- | --- |
| Strategie | RANGE, LIST, HASH | RANGE, LIST, HASH | **nur RANGE** | `PartitionType` |
| Kinder | benannt | benannt | **nummeriert, namenlos** | `PartitionDefinition.name` |
| Grenzen | `FROM`/`TO` | `VALUES LESS THAN` (nur obere) | Grenzwertliste + `LEFT`/`RIGHT` | `from`/`to`/`values` |
| Ablageort | Tablespace (ungenutzt) | — | **Filegroup je Partition, Pflicht** | **fehlt** |
| Partitionsobjekt | keins (Teil der Tabelle) | keins | **Function + Scheme, eigenständig** | **fehlt** |

Vier Dinge folgen daraus, und drei davon sind Entscheidungen, keine Arbeit:

1. **LIST und HASH haben kein Äquivalent.** SQL Server kennt ausschließlich
   RANGE. Beide müssen blocken — dieselbe Lage wie Materialized Views.
2. **Kindnamen überleben den Round-Trip nicht.** SQL Server nummeriert; ein
   Reverse kann `p_2024` nicht zurückgeben. Das ist der MySQL-`W112`-Fall in
   schärfer: dort geht eine Grenze verloren, hier die Identität jedes Kindes.
3. **Filegroups sind Pflicht.** Das Scheme braucht je Partition eine Filegroup.
   `[PRIMARY]` für alle ist gültiges DDL und der einzige Wert, den das neutrale
   Modell heute hergibt — aber es ist auch der Wert, der die Partitionierung
   ihres eigentlichen Zwecks beraubt (getrennte Ablage).
4. **`RANGE LEFT` vs. `RANGE RIGHT`** ist kein Freiheitsgrad: `FROM x TO y` ist
   das halboffene Intervall `[x, y)`, und das ist exakt `RANGE RIGHT`. Beim
   Reverse eines `LEFT`-Funktions muss der Fall entschieden sein.

#### Entscheidungen und Schnitt (Eigner-Gespräch, 2026-08-28)

Der erste Entwurf wollte LIST und HASH blocken und die Filegroups ins neutrale
Modell nehmen. Beides ist verworfen, und zwar gegen Präzedenzfälle im Repo:

**Filegroups gehören nicht ins Modell, sondern an die Realisierung.**
`DdlGenerationOptions.spatialProfile` löst dieselbe Art Problem — ein neutrales
Konzept, das je Ziel verschieden realisiert wird, mit `defaultFor(dialect)` und
`allowedFor(dialect)`. Ein `PartitionDefinition.storage` hätte drei von vier
Dialekten mit einem Feld belastet, das sie ignorieren, und beim Cross-Dialekt-
Vergleich nur Drift erzeugt. Der Ablageort ist eine Deployment-Eigenschaft.

**Kindnamen und LIST bekommen ein Overlay, keinen Konfigurationsschalter.**
`MigrationOverlayKinds` führt bereits `rename-mapping` für genau diese Lage: die
Identität kennt nur der Anwender, das Werkzeug kann sie nicht ableiten. Ein
`partition-mapping` ist die dritte Art derselben Sorte — und es *stellt Identität
her*, statt Gleichheit zu lockern. Die Grenze aus
[ADR 0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md) bleibt damit
unangetastet: `schema compare` bleibt streng. Ein Schalter, der entscheidet, ob
zwei Partitionssätze gleich sind, wäre das Gegenteil davon gewesen.

Für LIST kann das Werkzeug die Zuordnung sogar **verifizieren**: eine
LIST-Partitionierung ist genau dann RANGE-fähig, wenn die Wertemengen in
Sortierreihenfolge zusammenhängend und überschneidungsfrei sind. Sortieren,
prüfen, bei Verschränkung ablehnen — eine Zuordnung, die falsches Routing
erzeugte, kommt nicht durch.

**HASH wird nachgebaut, nicht geblockt.** Der Einwand „eine Emulation fügt
Objekte hinzu, die im Schema nicht stehen" trägt nicht: MySQL und SQLite haben
keine Sequenzen, und das Werkzeug baut sie nach — `dmg_sequences` plus
`nextval`-Routine bzw. Trigger-Paar. Der zweite Einwand („Zeilen landen anders
als in der Quelle") trägt ebenfalls nicht: genau das sagt `W130` für PG↔MySQL,
und es ist dort akzeptiert, nicht geblockt.

Das Vorbild liefert gleich die ganze Bauform:

- ein **Modus-Gate** wie `SqliteNamedSequenceMode` (`ACTION_REQUIRED` /
  `HELPER_TABLE`) statt einer festen Entscheidung,
- ein **modusspezifischer Validator** wie `SqliteHelperTableSequenceValidator`,
  der bewusst NICHT im dialekt-agnostischen `SchemaValidator` sitzt — dasselbe
  Schema ist für andere Ziele einwandfrei,
- und **benannte Bruchstellen** (`E058`, `E059`) für die Fälle, in denen der
  Nachbau nicht trägt.

#### Sub-Slice-Schnitt

| Sub-Slice | Inhalt | Endet mit |
| --- | --- | --- |
| **7a** ✅ | Reverse: Partition Function, Scheme und Grenzen aus `sys.partition_*`; Kindnamen synthetisiert (`R346`). `RANGE LEFT` wird **nicht** umgerechnet, trägt aber die Partitionierung ohne Kinder (`R347`) — mit `null` wäre der Rebuild-Wächter blind, der eine partitionierte Tabelle vor dem Neubau schützt. Grenzliterale gegen **beide** Server gemessen: `datetime2` brauchte eine Korrektur (`java.sql.Timestamp` hängt `.0` an), `decimal` nicht (beide liefern `1.50`) | `schema reverse` liest partitionierte Tabellen |
| **7b** ✅ | Generate RANGE: Function + Scheme + `ON ps(spalte)`, Filegroups über ein Profil in `DdlGenerationOptions` (Default `[PRIMARY]`). Dabei zu klären, was der Review offenließ: ob ein **clustered Columnstore**-Index eine `partition_ordinal`-Zeile führt (eine Tabelle mit clustered Index entsteht hier ohnehin); ob Function und Scheme, die in SQL Server **geteilte** Objekte sind, pro Tabelle dupliziert werden dürfen; und das `N`-Präfix für `nvarchar`-Grenzen | `schema generate --target mssql` partitioniert. Die drei offenen Fragen sind beantwortet: je Tabelle ein eigenes Function/Scheme-Paar (`W144`, die Teilung ist aus dem Modell nicht rekonstruierbar); `N`-Präfix für Zeichenketten-Grenzen; und eine Tabelle mit **clustered** Index findet ihre Partitionierungsspalte über `partition_ordinal` — live belegt, bis dahin waren alle Testtabellen Heaps |
| **7c** ✅ | Diff: Partitionierungs-Operationen rendern statt blocken. **Entschieden:** der Neubau (`MssqlRebuildRenderer`) nimmt die Partitionierung mit, statt zu blocken — Function und Scheme existieren beim Neubau bereits, und SQL Server lässt mehrere Tabellen an demselben Scheme hängen, also hängt sich die Zwischentabelle an das vorhandene. Geblockt bleibt nur, was der Neubau nicht kann: die Partitionierung **ändern** | `schema migrate` legt partitionierte Tabellen an, live gegen echtes SQL Server belegt; der Rückbau entfernt Scheme und Function nach der Tabelle (`DROP TABLE` allein lässt sie stehen). **Nicht geliefert:** `SPLIT`/`MERGE RANGE` für Grenzänderungen — dafür gibt es im neutralen Modell keine Operation, siehe unten |
| **7d** ✅ | HASH-Emulation als Modus-Gate nach Sequenz-Vorbild: `--mssql-hash-partitions computed_column`, persistierte berechnete Spalte `dmg_hash_bucket` + RANGE über die Eimergrenzen, Bruchstellen als E067/E068, Hinweis W145 | HASH-Partitionierung nutzbar, live belegt (Server nimmt es an **und** die Zeilen verteilen sich). Der Schnitt kam aus einer Messung, nicht aus Annahmen: SQL Server verlangt die Partitionsspalte in **jedem eindeutigen Index**, der Eimer tritt also in Primär- und UNIQUE-Schlüssel. Das ist genau dann bedeutungserhaltend, wenn der Hash-Schlüssel dort enthalten ist — sonst **E067** statt eines still geschwächten Schlüssels |

**Was 7c nicht liefern konnte, und warum es kein MSSQL-Thema ist:** Eine
Grenzänderung an einer bestehenden Tabelle (`SPLIT`/`MERGE RANGE`) hat im
neutralen Modell **keine** Operation. `OperationMapper.mapTablePartitioning`
meldet für alle vier Dialekte eine Warnung (`PARTITIONING_CHANGE_NOT_APPLIED`)
und emittiert nichts — die Partitionierung einer Tabelle lässt sich nicht in
place ändern. Ein MSSQL-Renderer könnte das also nicht rendern, weil ihm nie
etwas zu rendern gegeben wird. Der Weg dahin führt über eine neue
`DiffOperation` im Hexagon und trifft alle Dialekte zugleich (PostgreSQL:
`ATTACH`/`DETACH PARTITION`, MySQL: `ADD`/`DROP`/`REORGANIZE PARTITION`).

**Nebenbefund aus 7c, cross-dialekt und schwerer als der Slice selbst:** Der
Migrationspfad legte eine partitionierte Tabelle bei **PostgreSQL und MySQL
unpartitioniert** an — ohne Blocker, ohne Diagnose. MSSQL war der einzige
Dialekt, der das korrekt behandelte (durch Blocken). Gemessen und behoben,
bevor 7c gebaut wurde; beide Generate-Pfade konnten es längst, nur die
Diff-Pfade waren nie angeschlossen.

**Ausgegliedert:** das `partition-mapping`-Overlay (Kindnamen und LIST→RANGE) —
geplant als [`partition-mapping-overlay.md`](../next/partition-mapping-overlay.md).
Es braucht ein neues Overlay-Format samt Verifikation und ist über SQL Server
hinaus nützlich, gehört also nicht in diesen Slice. Der Plan hält fest, was
dabei zuerst zu entscheiden ist: das Overlay-Dokument bindet heute an ein
Fingerabdruck**paar** und hängt nur an `schema migrate`, während der
Kindnamen-Fall im Reverse und der LIST-Fall im Generate entsteht.

### Slice 8 — Volltext

- **Generate** meldet einen Volltext-Index als `E057`; der Spaltentyp
  degradiert nach [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) zu
  `NVARCHAR(MAX)` (`W132`/`W137`).
- **Der gepinnte Container kann kein Full-Text Search.** Gemessen am
  Harness-Digest (`mcr.microsoft.com/mssql/server`, 2022):
  `SERVERPROPERTY('IsFullTextInstalled')` liefert `0`, und
  `mssql-server-fts` ist im Image nicht auflösbar — die Microsoft-Paketquelle
  ist zwar eingetragen, der Paketindex aber nicht eingelesen.
- **Eigner-Entscheidung 2026-08-22: der Slice baut sich eine Testumgebung.**
  Ein abgeleitetes Image auf dem digest-gepinnten Basis-Image installiert
  Full-Text Search nach. Der Weg ist erprobt, nicht vermutet — im Container
  nachgestellt:

  ```
  # Der prod-Feed des Basis-Images fuehrt das Paket NICHT; die Server-Pakete
  # liegen in einem eigenen Repo.
  deb [arch=amd64] https://packages.microsoft.com/ubuntu/22.04/mssql-server-2022 jammy main
  apt-get update && apt-get install -y mssql-server-fts
  ```

  Der Simulationslauf zieht `mssql-server-fts` samt passender
  `mssql-server`-Version (16.0.4265.3) — die Engine wird also mitgehoben, das
  abgeleitete Image ist keine reine Ergaenzung.
- Zwei Folgen, die der Slice mitentscheiden muss: der Bau braucht **Netz zur
  Bauzeit**, und ein selbst gebautes Image hat keinen Upstream-Digest, an dem
  die Harness sonst pinnt
  ([ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)) — es
  bleibt der Basis-Digest plus die Reproduzierbarkeit des Dockerfiles.

#### Schnitt

Derselbe Bruch wie bei der Partitionierung, und deshalb derselbe Aufbau: SQL
Server verlangt zwei Dinge, die das neutrale Modell nicht trägt — einen
**Volltext-Katalog** (eigenständiges Datenbankobjekt) und einen **eindeutigen
Schlüsselindex** der Tabelle, an dem der Volltext-Index hängt.

| Sub-Slice | Inhalt | Fertig, wenn |
| --- | --- | --- |
| **8a** ✅ | Testumgebung: abgeleitetes Image mit `mssql-server-fts` (`test/integration-mssql/fts/Dockerfile`, `make mssql-fts-image`), Beleg-Spec im Integrationslauf | Belegt: `IsFullTextInstalled` = `1`, und `CREATE FULLTEXT CATALOG` + `CREATE FULLTEXT INDEX … KEY INDEX` laufen durch. Gemessen kam dazu: **Volltext ist in `master`/`tempdb`/`model` verboten** — die Spec legt eine eigene Datenbank an. Das Paket hebt die Engine auf 16.0.4265.3 mit, das Image ist 3,63 GB |
| **8b** ✅ | Generate: `CREATE FULLTEXT CATALOG` + `CREATE FULLTEXT INDEX … KEY INDEX …`. **Entschieden:** Katalog je Tabelle (`ftc_<tabelle>`, **W146**) wie bei Function/Scheme; fehlt ein tauglicher Schlüssel, bricht es mit **E070** ab statt zu raten | Live belegt: der Server nimmt das erzeugte DDL an und indiziert beide Spalten. Gemessen kamen drei Regeln dazu, die ich falsch geraten hätte: ein **zusammengesetzter** Primärschlüssel taugt nicht als `KEY INDEX` (einspaltig, eindeutig, nicht nullbar), je Tabelle ist genau **ein** Volltext-Index zulässig (**E071**), und `DROP TABLE` lässt den **Katalog stehen** |
| **8c** ✅ | Reverse: Volltext-Indizes über `sys.fulltext_index_columns` zurücklesen | Round-Trip live belegt (erzeugen, anwenden, zurücklesen). **Der Name ist der Bruchpunkt:** SQL Server benennt Volltext-Indizes gar nicht, der Reverse synthetisiert `ft_<tabelle>` und meldet `R348` — dieselbe Lage wie bei den Partitions-Kindnamen (`R346`) |
| **8d** ✅ | Diff/Migrate | **Ergebnis ist ein benannter Abbruch, keine Anwendung** — und das ist eine Eigenschaft von SQL Server: `CREATE FULLTEXT INDEX` ist in einer offenen Transaktion verboten, der Lauf klammert aber alles in eine. Gemessen in beide Richtungen: nur das **Anlegen des Katalogs** darf in die Transaktion — `CREATE FULLTEXT INDEX`, `DROP FULLTEXT INDEX` und `DROP FULLTEXT CATALOG` nicht. `schema migrate` bricht jetzt mit `E072` **vor** der Ausführung ab (Exit 8) statt mitten darin an einer Servermeldung (Exit 5). Freischalten kann das nur eine Ausführung ausserhalb der Transaktion — `open/no-transaction-execution-strategy.md`, dieselbe Naht, auf die PostgreSQLs `CREATE INDEX CONCURRENTLY` wartet. **Vorarbeit:** der Fingerabdruck trug den synthetisierten Indexnamen; live gemessen und über `capabilityIndexCanonicalizer` behoben |

**Die Schlüsselindex-Regel ist die 7d-Regel in anderem Gewand.** SQL Server
verlangt für `KEY INDEX` einen **einspaltigen**, eindeutigen, nicht-nullbaren
Index. Ein Primärschlüssel über einer Spalte erfüllt das; ein zusammengesetzter
nicht. Das ist entscheidbar und muss deshalb ein benannter Bruchpunkt werden,
kein Raten — wie `E067` bei der HASH-Emulation.

**Die Spec überspringt sich, wenn das Image fehlt** (`docker image inspect`,
nicht Pull-Versuch — ein Pull gegen ein nur lokal gebautes Tag läuft in einen
404 der Registry). Ohne diesen Wächter riss der erste Push den CI-Job
`Integration Tests`: das Image existiert dort nicht. Offen für 8d: ob CI das
Image irgendwann selbst baut, oder ob der Volltext-Beleg dauerhaft ein
lokaler/opt-in-Lauf bleibt.

**Was 8a mitentscheidet:** der Bau braucht Netz und hat keinen Upstream-Digest
([ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md) pinnt sonst
darauf). Gepinnt bleibt der Basis-Digest; die Reproduzierbarkeit kommt aus dem
Dockerfile. Das Image gehört deshalb **nicht** in die netzlosen Gates.

### Slice 9 — Routinen und Trigger

PostgreSQL ist hier das Vorbild, nicht der Mitblockierer: es **rendert**
Funktionen, Prozeduren und Trigger im Diff-Pfad
([`PostgresDiffFunctionOps`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffFunctionOps.kt),
`PostgresDiffProcedureOps`) — in seinem Dispatch ist einzig `AlterCustomType`
`UNSUPPORTED`. Über die vier Stufen verglichen:

| Stufe | PostgreSQL heute | MSSQL heute |
| --- | --- | --- |
| Modell | `FunctionDefinition.body`, `TriggerDefinition.body` (plus `language`, `sourceDialect`) | dieselben Felder — das Modell trägt Rümpfe bereits |
| Reverse | liest `information_schema.routines.routine_definition` bzw. `triggers.action_statement` in `body` | liest Rümpfe **nicht**, meldet `R342` + `skippedObjects` |
| Generate | rendert `CREATE OR REPLACE FUNCTION … $body$ … $body$` | `E053` |
| Diff | rendert `CREATE OR REPLACE`; ein **fehlender Rumpf** ist der Blocker, nicht der Dialekt | blockt alles |

Der eigentliche Aufwand liegt damit nicht im Diff-Renderer, sondern **im
Reverse**: solange `body` leer ist, blockt der Diff aus demselben Grund wie
PostgreSQL es täte — „body is unknown", nicht „dialect unsupported".

- Der Rumpf steht in `sys.sql_modules.definition`; der MSSQL-Reverse fragt
  diese Sicht für Views bereits ab, das Abfragemuster existiert also.
- `CREATE OR ALTER` gibt es für Prozeduren, Funktionen und Trigger nativ. Der
  Replace-Pfad ist damit **einfacher** als bei PostgreSQL, das dafür
  Dollar-Quoting mit festem `$body$`-Tag braucht.
- Die offene Frage ist keine T-SQL-Frage, sondern eine Modell-Frage: ein
  reverse-gelesener T-SQL-Rumpf ist auf keinem anderen Ziel gültig. Für
  View-Bodies löst das `ViewQueryTransformer.assessPortability`; für
  Routinen-Rümpfe gibt es kein Gegenstück, auch nicht bei PostgreSQL.

#### Schnitt

Die Reihenfolge folgt der Analyse oben: der Reverse ist der Engpass, alles
andere hängt daran.

| Sub-Slice | Inhalt | Fertig, wenn |
| --- | --- | --- |
| **9a** ✅ | Reverse liest Rümpfe aus `sys.sql_modules`; **Signatur aus `sys.parameters`**, Rumpf aus dem Definitionstext geschnitten | Live belegt. `R342` schrumpft auf das, was wirklich keinen T-SQL-Rumpf hat — und das sind **zwei** Fälle, nicht einer: CLR-Routinen (keine `sql_modules`-Zeile) und `WITH ENCRYPTION` (Zeile mit `definition IS NULL`). Der zweite wäre sonst still verschwunden. Festgehalten: T-SQL-Trigger feuern je **Anweisung**, und SQL Server kennt kein `BEFORE` — nur `AFTER` und `INSTEAD OF`. **Der `body`-Vertrag war ungeschrieben** und wurde beim Bauen erst falsch getroffen: die Spec sagte nur „Quell-Code im Quell-Dialekt", nicht ob innerer Block oder ganze Anweisung. Entschieden über die Pflichtfelder des Triggers im JSON-Schema (`table`, `event`, `timing` wären sonst redundant) und in `neutral-model-spec.md` nachgetragen |
| **9b** ✅ | Generate rendert `CREATE OR ALTER` statt `E053` | Live belegt: Reverse → Generate → Apply → Reverse, der Rumpf überlebt wörtlich. Zwei Vertragsbrüche aus 9a fielen dabei auf und sind mitbehoben: Parametertypen standen **nativ** im Modell (`int` statt `integer`), obwohl sie in den kanonischen Key eingehen — dieselbe Routine hätte je nach Quell-Dialekt einen anderen Key getragen; und die Keys waren blanke Namen statt `name(in:typ)` / `tabelle::name`. Zwei Dinge hat erst der Server entschieden: `CREATE PROCEDURE p () AS` ist Syntaxfehler (Funktionen verlangen die Klammern umgekehrt immer), und `sys.sql_modules` legt den Text ab, wie er gesendet wurde — das abschließende `;` wuchs pro Umlauf um eines, bis der Reverse es als das nahm, was es ist: Ende der `CREATE`-Anweisung, nicht Teil des Rumpfes. Nicht darstellbar bleiben `BEFORE`, `FOR EACH ROW`, `WHEN` und schemaweit kollidierende Trigger-Namen (E053) sowie die mehrteilige Tabellenfunktion beim Reverse (`R350`) |
| **9b-R1** ✅ | Review-Härtung nach `/code-review high` | Neun Befunde, sieben behoben, einer als Ticket, einer widerlegt. Der schwerste: `WITH EXECUTE AS OWNER` — dessen `AS` ist das **erste auf oberster Ebene**, der Rumpf-Schnitt landete dort. `WITH SCHEMABINDING` ging nur gut, weil darin kein `AS` steht. Beide melden jetzt `R351`, weil das Modell für die Optionsklausel kein Feld hat und ein Zurückschreiben die Schemabindung still verlöre. Dazu `R352` (tabellenwertiger Parameter), `R353` (Elterntabelle nicht auflösbar), E053 für überladene Routinen (dieselbe Falle wie schemaweite Trigger-Namen) und für neutrale Typnamen ohne T-SQL-Entsprechung. **Widerlegt:** der DDL-Trigger-Befund — live gemessen erreicht ein datenbankweiter DDL-Trigger den Reverse gar nicht, er ist nicht schemagebunden. **Ticket:** [Emissionsreihenfolge der Funktionen](../done/mssql-routine-emit-order.md) |
| **9c** ✅ | Diff/Migrate | Live belegt: anlegen, ersetzen, entfernen in einem Lauf gegen echtes SQL Server. `CREATE OR ALTER` macht das Ersetzen zu **einem** Statement — kein Fenster, in dem die Routine fehlt, dieselbe Ersparnis wie bei den Sichten. Die Urteile über das Nicht-Renderbare kommen aus **demselben** Code wie im Generate-Pfad (`MssqlRoutineDdl`): beim Partitionieren war das einmal nicht so, und beide Pfade waren für sich grün. `sp_rename` lässt den Rumpf stehen (`MSSQL_RENAME_KEEPS_ROUTINE_BODY`), wie bei den Sichten |

**Was `R342` behalten muss:** CLR-basierte Routinen (`PC`, `FS`, `FT`, `TA`)
haben keinen T-SQL-Rumpf — ihr Code liegt in einer .NET-Assembly und steht
nicht in `sys.sql_modules`. Sie bleiben ungelesen, aber mit einem eigenen
Grund statt des pauschalen „wird für MSSQL nicht gelesen".

**Die Modell-Frage bleibt offen und gehört nicht in diesen Slice:** ein
reverse-gelesener T-SQL-Rumpf ist auf keinem anderen Ziel gültig. Für
View-Bodies beurteilt `ViewQueryTransformer.assessPortability` den Rumpf
inhaltlich; Routinen-Rümpfe prüfen alle vier Dialekte nur nach Herkunft — ein
fremder `sourceDialect` fällt als `E053` weg, ein fehlender geht ungeprüft
durch. Slice 9 macht die Rümpfe lesbar und schreibbar; ob und wie sie
*übersetzt* werden, ist eine cross-dialektale Frage:
[`routine-body-cross-dialect-portability.md`](../open/routine-body-cross-dialect-portability.md).

### Slice 10 — Profiling ✅

- Das Modul folgt dem Muster der drei bestehenden `driver-*-profiling`-Module.
- Der Ertrag lag nicht im Muster, sondern in **sechs Typen, die T-SQL nicht
  vergleichen kann**: `geometry`, `geography`, `xml` und die LOB-Alttypen
  `text`, `ntext`, `image` weisen `COUNT`, `COUNT(DISTINCT)`, `GROUP BY` und
  `ORDER BY` ab. Die Aggregate laufen deshalb auf einer Textprojektion.
  `image` braucht dabei einen eigenen Weg — es lässt sich nicht nach `nvarchar`
  wandeln, nur über `varbinary`.
- Dazu ein Semantikunterschied: T-SQL füllt beim Vergleich mit Leerzeichen auf,
  `'   ' = ''` ist dort **wahr**. Der Test auf die leere Zeichenkette läuft
  deshalb über `DATALENGTH`, sonst zählte jeder Leerraum-Wert als leer statt
  als blank.
- Mit dem letzten Kommando fällt das `DialectCommandGate` ganz weg: ein Gate
  ohne Kommando ist toter Code.

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

## Offene Punkte

Der Abschnitt trug den Stand nach Slice 4. Drei seiner Einträge waren
inzwischen erledigt, ohne dass er es sagte; die übrigen sind als Tickets
ausgeschnitten, damit sie auffindbar bleiben.

**Erledigt:**

- ~~`Enum(refType)` liefert unter der MSSQL-Projektion falschen Drift.~~ —
  `MssqlNeutralTypeCanonicalizer` löst den `refType` auf, sobald das Schema
  mitkommt (`canonicalize(type, customTypes)`), und `TypeCanonicalizerWiring`
  reicht `schema.customTypes` durch. Das war die geforderte Auflösung: der
  Projektion Schema-Kontext geben.
- ~~Spaltenlevel `references` im Diff-Pfad.~~ —
  [`mssql-column-level-foreign-keys.md`](../done/mssql-column-level-foreign-keys.md).
- ~~Clustered/nonclustered-Steuerung und INCLUDE-Spalten.~~ — Slice 6, samt
  der Review-Nacharbeit an beiden Generate-Nähten (`DdlGenerator` und
  Diff-Builder).

**Ausgeschnitten nach `open/`:**

- [`neutral-default-function-fidelity.md`](../open/neutral-default-function-fidelity.md)
  — fremde Funktions-Defaults werden beim Round-Trip zum String-Literal.
  Dialektübergreifend, am MSSQL-Leg aufgefallen.
- [`mssql-bulk-import-fast-path.md`](../open/mssql-bulk-import-fast-path.md) —
  der Import läuft über gebatchte `INSERT`s, ohne `BULK INSERT`-Weg.
- [`mssql-import-skip-without-pk-preflight.md`](../open/mssql-import-skip-without-pk-preflight.md)
  — `--on-conflict skip` ohne Primärschlüssel meldet sich im Import-Pfad später
  als im Transfer-Pfad.

- [`mssql-srid-fidelity-data-path.md`](../open/mssql-srid-fidelity-data-path.md)
  — der Datenpfad schreibt Geometrien mit der Typ-Default-SRID; abweichende
  Wert-SRIDs gehen verloren.

---

## Closure

MS SQL Server ist der vierte Dialekt: `schema reverse`, `generate`, `migrate`,
`data export`/`import`/`transfer` und `data profile` sind gebaut und live gegen
echtes SQL Server belegt. Das `DialectCommandGate`, das unfertige Pfade an der
Kommando-Grenze abwies, ist mit Slice 10 ohne Gegenstand und **entfernt** — kein
Kommando weist MSSQL mehr ab.

Der Plan wandert damit nach `done/`. Was er an offener Arbeit trug, ist
ausgeschnitten und einzeln nachhaltbar:

| Ticket | Gegenstand |
| --- | --- |
| [`neutral-default-function-fidelity.md`](../open/neutral-default-function-fidelity.md) | Fremde Funktions-Defaults werden beim Round-Trip zu String-Literalen. Dialektübergreifend. |
| [`mssql-srid-fidelity-data-path.md`](../open/mssql-srid-fidelity-data-path.md) | Der Datenpfad schreibt Geometrien mit der Typ-Default-SRID; Wert-SRIDs gehen verloren. |
| [`mssql-bulk-import-fast-path.md`](../open/mssql-bulk-import-fast-path.md) | Der Import schreibt gebatchte `INSERT`s, ohne `BULK INSERT`-Weg. Durchsatz, kein Defekt. |
| [`mssql-import-skip-without-pk-preflight.md`](../open/mssql-import-skip-without-pk-preflight.md) | `--on-conflict skip` ohne Primärschlüssel meldet sich im Import-Pfad später als im Transfer-Pfad. |
| [`routine-body-cross-dialect-portability.md`](../open/routine-body-cross-dialect-portability.md) | Routinen-Rümpfe werden nach Herkunft übersprungen statt inhaltlich beurteilt. Cross-dialektal. |
| [`enum-inline-check-fidelity.md`](../open/enum-inline-check-fidelity.md) | Enum-CHECK-Kante aus Slice 5; wartet auf eine Eigner-Entscheidung zwischen drei Varianten. |
| [`no-transaction-execution-strategy.md`](../open/no-transaction-execution-strategy.md) | `CREATE FULLTEXT INDEX` verträgt keine offene Transaktion (Slice 8d, `E072`); dieselbe Naht wartet PGs `CREATE INDEX CONCURRENTLY` ab. |
| [`partition-boundary-change-operation.md`](../open/partition-boundary-change-operation.md) | Grenzänderungen an Partitionen werden erkannt und gemeldet, aber nicht als Operation emittiert. |
