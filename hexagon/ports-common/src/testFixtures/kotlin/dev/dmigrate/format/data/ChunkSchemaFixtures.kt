package dev.dmigrate.format.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.model.NeutralType

/**
 * Test-Helper: baut ein minimales [ChunkSchema] aus einer
 * Liste von [ColumnDescriptor]. Per Default mit
 * [NeutralType.Text]; explizite Spaltentypen koennen pro
 * Spaltenname via [neutralTypes] uebergeben werden.
 *
 * Bewusst in `hexagon:ports-common` testFixtures (statt in
 * jedem Testfile inline), damit die ~25 Bestandstests, die
 * heute `writer.begin(table, columns)` mit
 * `List<ColumnDescriptor>` aufrufen, eine einheitliche
 * Migrationsbruecke bekommen.
 *
 * Verwendung im Test:
 * ```kotlin
 * writer.begin(table, chunkSchemaOf(table, columns))
 * ```
 *
 * Fuer Tests, die echte `NeutralType`s brauchen, kann
 * [neutralTypes] mit einem Mapping `name -> NeutralType`
 * uebergeben werden; alle nicht aufgefuehrten Spalten
 * bekommen [NeutralType.Text].
 */
fun chunkSchemaOf(
    table: String,
    columns: List<ColumnDescriptor>,
    origin: SchemaOrigin = SchemaOrigin.JDBC_METADATA,
    neutralTypes: Map<String, NeutralType> = emptyMap(),
): ChunkSchema = ChunkSchema(
    table = table,
    columns = columns.map {
        ChunkColumnSchema(
            name = it.name,
            nullable = it.nullable,
            neutralType = neutralTypes[it.name] ?: NeutralType.Text(),
        )
    },
    origin = origin,
)

