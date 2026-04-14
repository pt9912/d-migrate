# Refactoring: AbstractJdbcSchemaReader

> **Status**: Geplant
> **Prioritaet**: Hoch (technische Schuld aus Phase D)
> **Voraussetzung**: Phase D abgeschlossen
> **Vorbild**: `AbstractJdbcDataReader` in `driver-common`

---

## Problem

Die drei `SchemaReader`-Implementierungen (`PostgresSchemaReader`,
`MysqlSchemaReader`, `SqliteSchemaReader`) enthalten jeweils ~300-400
Zeilen Orchestrierungslogik, die sich strukturell stark aehnelt:

1. Connection aus Pool borgen
2. Tabellen auflisten
3. Pro Tabelle: Spalten, PKs, FKs, Constraints, Indizes lesen
4. Spalten auf neutrales Modell mappen
5. Einspaltiges UNIQUE/FK auf ColumnDefinition heben
6. Mehrspaltige Constraints auf ConstraintDefinition
7. Optionale Objekte (Views, Routinen, Trigger) unter Include-Flags
8. SchemaReadResult zusammenbauen

Diese Orchestrierung ist in allen drei Dialekten identisch. Nur die
konkreten Queries und das Typ-Mapping unterscheiden sich.

### Konsequenzen

- **Kover**: Die Orchestrierung ist nur mit echter DB testbar, daher
  muessen die SchemaReader-Klassen von Kover ausgeschlossen werden
  (PostgreSQL/MySQL: 40-50% Schwelle statt 80-90%)
- **Duplikation**: Aenderungen an der Orchestrierung (z.B. neue
  Spaltenattribute, PK-Redundanz-Logik) muessen in drei Dateien
  parallel nachgezogen werden
- **Testbarkeit**: Unit-Tests fuer die Orchestrierung fehlen; nur
  Integrationstests (Testcontainers) decken den Pfad ab

---

## Loesung: AbstractJdbcSchemaReader

Nach dem Vorbild von `AbstractJdbcDataReader` in `driver-common`:

```kotlin
// driver-common
abstract class AbstractJdbcSchemaReader : SchemaReader {

    override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
        pool.borrow().use { conn ->
            val session = JdbcMetadataSession(conn)
            val notes = mutableListOf<SchemaReadNote>()
            val skipped = mutableListOf<SkippedObject>()

            val scope = resolveScope(session, conn)
            val tables = readTables(session, scope, notes, skipped)
            val sequences = readSequences(session, scope)
            val customTypes = readCustomTypes(session, scope, notes)
            val views = if (options.includeViews) readViews(session, scope) else emptyMap()
            // ... etc

            return SchemaReadResult(
                schema = SchemaDefinition(
                    name = buildReverseName(scope),
                    version = ReverseScopeCodec.REVERSE_VERSION,
                    tables = tables,
                    // ...
                ),
                notes = notes,
                skippedObjects = skipped,
            )
        }
    }

    // ── Abstrakte Hooks (dialektspezifisch) ─────

    /** Dialect-specific scope resolution (schema, database, etc.) */
    abstract fun resolveScope(session: JdbcMetadataSession, conn: Connection): ReaderScope

    /** Build canonical reverse name from scope */
    abstract fun buildReverseName(scope: ReaderScope): String

    /** List base tables (excluding system/virtual tables) */
    abstract fun listTableEntries(session: JdbcMetadataSession, scope: ReaderScope): List<TableEntry>

    /** Read columns for a table */
    abstract fun readColumns(session: JdbcMetadataSession, scope: ReaderScope, table: String): List<ColumnProjection>

    /** Map a raw column to a neutral type + optional note */
    abstract fun mapColumnType(col: ColumnProjection, isPk: Boolean, ...): TypeMapping.MappingResult

    /** Read PKs, FKs, indices, constraints */
    abstract fun readPrimaryKey(session: JdbcMetadataSession, scope: ReaderScope, table: String): List<String>
    abstract fun readForeignKeys(session: JdbcMetadataSession, scope: ReaderScope, table: String): List<ForeignKeyProjection>
    abstract fun readIndices(session: JdbcMetadataSession, scope: ReaderScope, table: String): List<IndexProjection>

    /** Optional: table metadata (engine, WITHOUT ROWID) */
    open fun readTableMetadata(...): TableMetadata? = null

    /** Optional: sequences, custom types, views, routines, triggers */
    open fun readSequences(...): Map<String, SequenceDefinition> = emptyMap()
    open fun readCustomTypes(...): Map<String, CustomTypeDefinition> = emptyMap()
    // ...

    // ── Gemeinsame Orchestrierung (nicht abstrakt) ──

    /** Hebt einspaltiges UNIQUE auf ColumnDefinition.unique */
    private fun liftSingleColumnUnique(...)

    /** Hebt einspaltigen FK auf ColumnDefinition.references */
    private fun liftSingleColumnFk(...)

    /** PK-implizites required/unique NICHT duplizieren */
    private fun suppressPkRedundancy(...)
}
```

Dann werden die konkreten Reader duenn:

```kotlin
// driver-postgresql
class PostgresSchemaReader : AbstractJdbcSchemaReader() {
    override fun resolveScope(...) = ...
    override fun buildReverseName(scope) = ReverseScopeCodec.postgresName(...)
    override fun listTableEntries(...) = PostgresMetadataQueries.listTableRefs(...)
    override fun readColumns(...) = PostgresMetadataQueries.listColumns(...)
    override fun mapColumnType(...) = PostgresTypeMapping.mapColumn(...)
    // ...
}
```

---

## Erwartete Verbesserungen

| Aspekt | Vorher | Nachher |
|--------|--------|---------|
| Kover-Schwelle (non-integration) | 40-50% | 80%+ |
| Orchestrierungs-Duplikation | 3x ~300 Zeilen | 1x in driver-common |
| Unit-Testbarkeit | Nur Testcontainers | SQLite in-memory fuer Basis |
| Aenderungsaufwand | 3 Dateien parallel | 1 Datei + ggf. Hook |
| SchemaReader LOC pro Dialekt | 300-400 | ~50-100 |

---

## Betroffene Dateien

### Neu
- `driver-common/.../AbstractJdbcSchemaReader.kt`
- `driver-common/.../AbstractJdbcSchemaReaderTest.kt` (SQLite in-memory)

### Zu aendern
- `driver-postgresql/.../PostgresSchemaReader.kt` (auf extends umstellen)
- `driver-mysql/.../MysqlSchemaReader.kt` (auf extends umstellen)
- `driver-sqlite/.../SqliteSchemaReader.kt` (auf extends umstellen)
- `driver-postgresql/build.gradle.kts` (Kover-Schwelle zurueck auf 80%)
- `driver-mysql/build.gradle.kts` (Kover-Schwelle zurueck auf 79%)

### Unveraendert
- `*TypeMapping.kt` (bleiben als reine Funktionen)
- `*MetadataQueries.kt` (bleiben als Query-Helfer)
- Port-Interface `SchemaReader` (bleibt)
- Tests (bestehende bleiben, neue Basis-Tests kommen dazu)

---

## Umsetzungsreihenfolge

1. `AbstractJdbcSchemaReader` in `driver-common` mit der gemeinsamen
   Orchestrierung
2. `AbstractJdbcSchemaReaderTest` mit SQLite in-memory — testet die
   gesamte Orchestrierung (UNIQUE-Lifting, PK-Redundanz, Include-Flags)
3. `SqliteSchemaReader` auf `extends AbstractJdbcSchemaReader` umstellen
4. `PostgresSchemaReader` umstellen
5. `MysqlSchemaReader` umstellen
6. Kover-Schwellen zuruecksetzen
7. Alte Orchestrierungs-Tests in den Integrationstests behalten als
   End-to-End-Verifikation
