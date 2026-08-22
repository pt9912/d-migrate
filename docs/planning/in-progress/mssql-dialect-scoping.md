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
| **1a** ✅ | CLI-E2E-Absicherung in `test/e2e-cli`: Gate-Ablehnungen als Subprozess-E2E (containerlos — generate/export/import/transfer/migrate/profile/`export <tool>` liefern Exit 2 + Gate-Meldung) und `schema reverse`-Subprozess-E2E gegen den Testcontainer | E2E-Netz für den nutzersichtbaren MSSQL-Pfad und die Gates; vor Slice 2, damit Gate-Wegfall pro Slice testgetrieben ist |
| **2** ✅ | `DdlGenerator` + Typtabelle NeutralType→T-SQL (Generate-Richtung) | `schema generate --target mssql` |
| **3** ✅ | `DataReader`/`DataWriter` (Transfer; Fast-Path später); **3b** (offen): sample-db-MSSQL-Leg im Harness (`examples/sample-db`, fetch+compose gemäß [ADR 0013](../../adr/0013-sample-db-sourcing.md)/[ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md)): Reverse→Generate→Import-Roundtrip-Smoke als eigener Workflow | `data export/import/transfer` + MSSQL-Smoke in CI |
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
| `schema generate` | **Slice 2** | — |
| `export flyway/liquibase/django/knex` (Tool-Export) | **Slice 2** | — |
| `data export` / `data import` / `data transfer` | **Slice 3** | — |
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

## Offene Punkte (Stand nach Slice 3)

**Erledigt:**

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

**Offen:**

- **Slice 3b — sample-db-MSSQL-Leg:** fetch + compose gemäß
  [ADR 0013](../../adr/0013-sample-db-sourcing.md)/[ADR 0014](../../adr/0014-sample-db-harness-fetch-and-compose.md),
  Reverse→Generate→Import-Smoke als eigener Workflow. Der Datenpfad selbst ist
  mit Slice 3 fertig und live getestet.
- **SRID-Treue im Datenpfad:** WKB trägt keine SRID, SQL Server führt sie am
  Wert (nicht an der Spalte) — übertragene Geometrien landen mit dem
  Spalten-Default (0 bzw. 4326). Eine SRID-treue Übertragung bräuchte eine
  eigene Projektion (Wert-SRID als Zusatzspalte oder EWKB-ähnliche Kodierung);
  dokumentiert in `spec/type-mapping.md`, keinem Slice zugeordnet.

**Bewusst zurückgestellt / anderswo geplant:**

- **Clustered/nonclustered-Steuerung und INCLUDE-Spalten:** Slice 6.
- **Bulk-Fast-Path** (`BULK INSERT`/`SqlServerBulkCopy`): hinter dem
  generischen Batch-Insert, wie im Slice-Schnitt vorgesehen.
- **`data import --on-conflict skip` ohne PK:** Verhaltensnotiz, kein Rückstand
  — der Transfer-Pfad lehnt es im Preflight ab
  (`DialectCapabilities.requiresPrimaryKeyForSkip`), der Import-Pfad hat dort
  keinen Schema-Preflight und meldet es beim Öffnen der Tabelle mit klarer
  Meldung.
