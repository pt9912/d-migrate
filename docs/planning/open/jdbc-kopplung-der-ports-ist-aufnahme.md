# JDBC-Kopplung der Ports-Schicht — Ist-Aufnahme

> Status: **Referenz/Ist-Aufnahme** — kein eigenständiges Vorhaben.
> Trigger: Einwand „kein JDBC im Hexagon" (2026-07-17). Die Ursachenklärung ergab, dass die
> JDBC-Typcodes ein **Symptom** sind; die Richtung entscheidet
> [ADR 0037](../../adr/0037-database-agnostic-first-staffelung.md) („Database-Agnostic First":
> Zielbild bestätigt, Umsetzung **nach 1.0.0**).
> Zweck dieses Dokuments: die **verifizierte Faktenlage** für dieses Post-1.0.0-Vorhaben
> festhalten, damit sie nicht erneut erhoben werden muss.
> Aktivierungsbedingung: keine — das Dokument wandert nicht nach `next/`; es ist Zuarbeit zu dem
> Vorhaben, das ADR 0037 terminiert.

## Worum es geht

[`spec/architecture.md`](../../../spec/architecture.md) führt „Database-Agnostic First" als
Leitprinzip: datenbankspezifisches Verhalten lebt ausschließlich in **austauschbaren Adaptern**.
Weder Lastenheft noch `architecture.md` grenzen d-migrate auf relationale Datenbanken ein.

Der Treiber-Port tut es faktisch trotzdem. Entscheidung, Optionen und Staffelung stehen in
[ADR 0037](../../adr/0037-database-agnostic-first-staffelung.md) — hier steht nur, **was** wo sitzt.

## Die Ursache: der Treiber-Port diktiert JDBC

[`DatabaseDriver`](../../../hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt)
verlangt von jedem Treiber, **ohne Default**:

| Pflichtmitglied | Setzt voraus |
| --- | --- |
| `urlBuilder(): JdbcUrlBuilder` | eine JDBC-URL |
| `ddlGenerator(): DdlGenerator` | SQL-DDL |
| `tableLister(): TableLister` | Tabellen |
| `schemaReader(): SchemaReader` | ein relationales Schema |
| `dataReader(fetchSize: Int?)` | JDBC-Cursor-Prefetch (laut KDoc) |

[`JdbcUrlBuilder`](../../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/JdbcUrlBuilder.kt)
ist formal ein Interface (`baseJdbcUrl(config): String` abstrakt) — ein Nicht-JDBC-Treiber könnte es
also implementieren, müsste aus `baseJdbcUrl` aber etwas liefern, das keine JDBC-URL ist, und einen
`DdlGenerator`, der wirft.

[ADR 0022](../../adr/0022-ports-jdbc-entkopplung.md) hat dieselbe Bewegung begonnen und nicht zu
Ende geführt: `java.sql.Connection` wurde durch ein neutrales `DatabaseConnection` ersetzt, die URL
blieb JDBC.

## Die Symptome: Typcode-Träger

| Fundstelle | Modul |
| --- | --- |
| `TargetColumn.jdbcType: Int` | `ports-write` |
| `JdbcTypeHint.jdbcType: Int` | `ports-common` |
| `JdbcTypeCodes` (29 Konstanten, Werte = `java.sql.Types`, kleinster `-16`) | `ports-common` |

`JdbcTypeCodes` entstand in Commit `8fed2013` — **demselben** Commit, der `a-check` scharf schaltete
und den `java.sql.Types`-Import aus `hexagon:application` entfernte. Die Gates prüfen **Importe**;
Konstanten neu zu deklarieren erfüllt sie, ohne die Kopplung zu verringern.

## Der einzige heute belastete Nicht-JDBC-Adapter: `formats`

Eine CSV-Datei hat keine JDBC-Typen. Trotzdem zwingt `JdbcTypeHint` den Format-Adapter, in
`java.sql.Types` zu denken: `DefaultValueDeserializer` castet CSV-/JSON-Werte am Typcode. Die Kette
ist **Adapter → Port → Adapter** und nicht abkürzbar:

`DataWriterUtils.loadTargetColumns` (driver-common) → `TableImportSession.targetColumns`
(**ports-write**) → `TableImporter` (streaming) → `ValueDeserializerFactory.create`
(**ports-write**) → `DefaultValueDeserializerFactory` (formats).

Weder `streaming` noch `formats` haben eine produktive Compile-Kante auf `driver-common` (nur
`testImplementation`). Das ist der natürliche erste Angriffspunkt des Post-1.0.0-Vorhabens — und
der Grund, warum mehrere naheliegende Heilmittel scheitern (s. ADR 0037).

## Verifizierte Fläche (2026-07-17)

- **Eine** produktive `TargetColumn`-Konstruktion:
  [`DataWriterUtils`](../../../adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/data/DataWriterUtils.kt)
  — liegt bereits im Adapter und liest per `ResultSetMetaData`.
- **26** Testdateien konstruieren `TargetColumn`; **keine** Golden-Files, **keine** `testFixtures`.
- `TargetColumn` steckt in **zwei weiteren Port-Verträgen**: `TableImportSession.targetColumns`,
  `ValueDeserializerFactory.create`.
- `setNull(idx, jdbcType)` in drei Import-Sessions; ein `NeutralType→jdbcType`-Rückmapper existiert
  **nicht**.
- `JdbcTypeCodes` hat genau **einen** Nutzer (`ImportTypeCompatibility`).
- **Parquet ist kein Blocker**: der Schreibpfad setzt `jdbcType = null` und emittiert das Feld
  bedingt, also nie — kein von d-migrate geschriebenes Manifest enthält es; es existiert nur
  read-tolerant.
- **deny-by-default gilt nicht durchgängig**: `ImportTypeCompatibility` hat keinen top-level
  `else -> false`; `is NeutralType.FullText -> true` akzeptiert jedes Ziel unbesehen, und
  `isEnumCompatible` akzeptiert `Types.OTHER` gezielt.
- `JdbcToNeutralTypeMapper` ist dialekt-blind und verlustbehaftet: er erzeugt **nie**
  `NeutralType.Geometry` (die Markierung kommt aus der Metadaten-Vorabfrage), und Unbekanntes fällt
  auf `NeutralType.Text`.

## Weitere Dialekt-Träger in Ports — **nicht** Teil des Vorhabens

Ausdrücklich als Nicht-Ziele in [ADR 0037](../../adr/0037-database-agnostic-first-staffelung.md):
`TypeMapper.toSql(): String` und `TransferTypeCompatibility.TEXT_SQL_TYPES` (`ports-common`),
`LogicalTypeResolverPort.resolve(dbType)` und `SchemaIntrospectionPort.ColumnSchema.dbType`
(`profiling`), `SqliteCastPreflight` (vendor-benannter Port, `ports-read`) sowie
`NeutralType.Enum.refType`/`Array.elementType` (`core`, Spannung zu
[ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) — siehe
[`pg-only-types-first-class-candidates.md`](pg-only-types-first-class-candidates.md)).

**Nachtrag 2026-08-21 (MSSQL-Slice 1):**
[`SqlIdentifiers`](../../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/SqlIdentifiers.kt)
(`ports-common`) ist ein weiterer Dialekt-Träger dieser Familie: per-Dialekt
Identifier-Quoting und String-Literal-Escaping (PG/SQLite-Doppelquotes,
MySQL-Backticks + Backslash-Regel, MSSQL-`[]`-Klammern). Die Lage im Hexagon
ist eine bewusste Konsolidierung — **eine** auditierbare Injection-Schutzfläche
statt verstreuter Treiber-Implementierungen, konsumiert u. a. von
Hexagon-eigenen SQL-Renderern wie `AtomicPreserveRestoreSql` — aber jeder neue
Dialekt erweitert das `when` hier. Ein Auszug in treiber-gelieferte Ports
gehört in die Optionsabwägung von
[ADR 0037](../../adr/0037-database-agnostic-first-staffelung.md) (Option D),
nicht in einen Dialekt-Slice.
