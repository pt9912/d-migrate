package dev.dmigrate.core.seed

import dev.dmigrate.core.dependency.FkEdge
import dev.dmigrate.core.dependency.sortTablesByDependency
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import kotlin.random.Random

/**
 * Zielspalte kann nicht sicher befüllt werden (echter FK-Zyklus,
 * Geometry/FullText/Enum ohne Werte, jeweils `required`). Runner mappt
 * das auf Exit 3 (AE-4/AE-10).
 */
class SeedPreflightException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * `unique`-Spalte fand nach den erlaubten Versuchen keinen neuen Wert.
 * Runner mappt das auf Exit 5 (AE-7).
 */
class SeedUniquenessExhaustedException(message: String) : RuntimeException(message)

/**
 * Gebündelter Seeding-Zustand für eine einzelne Tabelle — hält
 * `columnValue`/`referencedValue`/`uniqueAwareValue` unter der
 * Detekt-Parameterlisten-Grenze (bündelt, was sonst als 6-8
 * Einzelparameter durchgereicht werden müsste).
 */
private class TableSeedContext(
    val tableName: String,
    val generator: ColumnValueGenerator,
    val circularEdges: Set<FkEdge>,
    val valuePools: Map<Pair<String, String>, List<Any?>>,
    val usedValues: MutableMap<String, MutableSet<Any?>>,
)

/**
 * Orchestriert die Zeilengenerierung für ein ganzes Schema in
 * FK-sicherer Reihenfolge (`data seed` P1, ImpPlan-1.3.0-cli-data-seed-p1.md
 * AP2). Pure Domänenlogik — kein JDBC-Bezug, deshalb in `hexagon:core`.
 *
 * FK-Kanten werden hier lokal aus `column.references` und
 * `constraint.references` gebaut (nicht aus `SchemaFkEdges`, das in
 * `hexagon:application` liegt und von `hexagon:core` aus nicht erreichbar
 * ist — Abhängigkeitsrichtung core→application existiert nicht). Nur
 * `column.references` bekommt FK-konsistente Werte (Werte-Pool-Sampling);
 * constraint-modellierte (ggf. mehrspaltige) Fremdschlüssel fließen nur in
 * die Tabellen-Reihenfolge ein, nicht in die Wertegenerierung (Nicht-Scope,
 * siehe ImpPlan).
 */
class TableRowSeeder(private val random: Random, private val locale: SeedLocale) {

    /** Generiert [countPerTable] Zeilen je Basistabelle, in FK-sicherer Reihenfolge. */
    fun seedAll(schema: SchemaDefinition, countPerTable: Int): Map<String, List<Map<String, Any?>>> {
        val result = linkedMapOf<String, List<Map<String, Any?>>>()
        seedEach(schema, countPerTable) { tableName, rows -> result[tableName] = rows }
        return result
    }

    /**
     * Wie [seedAll], ruft aber [onTable] sofort nach jeder fertig generierten
     * Tabelle auf, statt alle Tabellen im Rückgabewert zu sammeln. Erlaubt dem
     * Aufrufer, Generierung und Schreiben zu verschränken (Zeilen einer bereits
     * geschriebenen Tabelle müssen dann nicht länger im Speicher gehalten
     * werden) — `valuePools` bleibt unabhängig davon für die gesamte
     * Schema-Laufzeit erhalten, da spätere Tabellen ggf. auf frühere
     * FK-referenzieren.
     */
    fun seedEach(schema: SchemaDefinition, countPerTable: Int, onTable: (String, List<Map<String, Any?>>) -> Unit) {
        val tableNames = schema.tables.keys
        val order = sortTablesByDependency(tableNames, buildFkEdges(schema, tableNames))
        val circularEdges = order.circularEdges.toSet()
        val valuePools = mutableMapOf<Pair<String, String>, MutableList<Any?>>()
        for (tableName in order.sorted) {
            val table = schema.tables.getValue(tableName)
            onTable(tableName, seedTable(tableName, table, countPerTable, circularEdges, valuePools))
        }
    }

    private fun buildFkEdges(schema: SchemaDefinition, tables: Collection<String>): List<FkEdge> =
        tables.flatMap { table ->
            val edges = mutableListOf<FkEdge>()
            schema.tables[table]?.columns?.values?.forEach { column ->
                column.references?.let { edges += FkEdge(table, toTable = it.table) }
            }
            schema.tables[table]?.constraints?.forEach { constraint ->
                constraint.references?.let { edges += FkEdge(table, toTable = it.table) }
            }
            edges
        }

    private fun seedTable(
        tableName: String,
        table: TableDefinition,
        count: Int,
        circularEdges: Set<FkEdge>,
        valuePools: MutableMap<Pair<String, String>, MutableList<Any?>>,
    ): List<Map<String, Any?>> {
        val ctx = TableSeedContext(
            tableName = tableName,
            generator = ColumnValueGenerator(random, locale),
            circularEdges = circularEdges,
            valuePools = valuePools,
            usedValues = mutableMapOf(),
        )
        val rows = ArrayList<Map<String, Any?>>(count)
        repeat(count) {
            val row = linkedMapOf<String, Any?>()
            for ((columnName, column) in table.columns) {
                row[columnName] = columnValue(columnName, column, ctx)
            }
            rows += row
            for ((columnName, _) in table.columns) {
                valuePools.getOrPut(tableName to columnName) { mutableListOf() } += row[columnName]
            }
        }
        return rows
    }

    private fun columnValue(columnName: String, column: ColumnDefinition, ctx: TableSeedContext): Any? {
        val reference = column.references
        if (reference != null) {
            return referencedValue(columnName, column, reference.table, reference.column, ctx)
        }
        return try {
            uniqueAwareValue(columnName, column, ctx)
        } catch (e: UnsupportedSeedTypeException) {
            if (!column.required) {
                null
            } else {
                throw SeedPreflightException(
                    "Spalte '${ctx.tableName}.$columnName' (${e.type}) kann in P1 nicht generiert werden " +
                        "und ist NOT NULL.",
                    e,
                )
            }
        }
    }

    private fun referencedValue(
        columnName: String,
        column: ColumnDefinition,
        targetTable: String,
        targetColumn: String,
        ctx: TableSeedContext,
    ): Any? {
        // Selbstreferenzen (targetTable == tableName) sind in `circularEdges` nie enthalten
        // (TableDependencySort filtert Self-Edges vor Kahn heraus), aber fuer die erste(n)
        // Zeile(n) einer Tabelle gibt es zwangslaeufig noch keinen eigenen Wert zum
        // Referenzieren -- dieselbe Behandlung wie ein echter Zyklus (AE-4).
        val isCircular = targetTable == ctx.tableName ||
            ctx.circularEdges.any { it.fromTable == ctx.tableName && it.toTable == targetTable }
        if (isCircular) {
            if (!column.required) return null
            throw SeedPreflightException(
                "Spalte '${ctx.tableName}.$columnName' ist Teil eines echten FK-Zyklus (oder referenziert " +
                    "die eigene, noch leere Tabelle) und NOT NULL -- kann in P1 nicht ohne " +
                    "Datenverlust befüllt werden.",
            )
        }
        val pool = ctx.valuePools[targetTable to targetColumn]
        if (pool.isNullOrEmpty()) {
            throw SeedPreflightException(
                "Spalte '${ctx.tableName}.$columnName' referenziert '$targetTable.$targetColumn', " +
                    "dort wurden aber noch keine Werte generiert (FK-Reihenfolge inkonsistent).",
            )
        }
        if (!column.unique) return pool.random(random)

        val used = ctx.usedValues.getOrPut(columnName) { mutableSetOf() }
        repeat(MAX_UNIQUE_ATTEMPTS) {
            val candidate = pool.random(random)
            if (used.add(candidate)) return candidate
        }
        throw SeedUniquenessExhaustedException(
            "Spalte '${ctx.tableName}.$columnName' ist eindeutig (FK auf '$targetTable.$targetColumn'), aber " +
                "nach $MAX_UNIQUE_ATTEMPTS Versuchen wurde kein neuer Wert gefunden (zu wenige Werte im " +
                "Ziel-Pool für die gewünschte Zeilenzahl).",
        )
    }

    private fun uniqueAwareValue(columnName: String, column: ColumnDefinition, ctx: TableSeedContext): Any? {
        val mustBeUnique = column.unique || column.type is NeutralType.Identifier
        if (!mustBeUnique) return ctx.generator.generate(column.type)

        val used = ctx.usedValues.getOrPut(columnName) { mutableSetOf() }
        repeat(MAX_UNIQUE_ATTEMPTS) {
            val candidate = ctx.generator.generate(column.type)
            if (used.add(candidate)) return candidate
        }
        throw SeedUniquenessExhaustedException(
            "Spalte '${ctx.tableName}.$columnName' ist eindeutig, aber nach $MAX_UNIQUE_ATTEMPTS Versuchen " +
                "wurde kein neuer Wert gefunden (Wertebereich zu klein für die gewünschte Zeilenzahl).",
        )
    }

    companion object {
        private const val MAX_UNIQUE_ATTEMPTS = 50
    }
}
