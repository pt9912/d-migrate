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
        val tableNames = schema.tables.keys
        val order = sortTablesByDependency(tableNames, buildFkEdges(schema, tableNames))
        val circularEdges = order.circularEdges.toSet()
        val valuePools = mutableMapOf<Pair<String, String>, MutableList<Any?>>()
        val result = linkedMapOf<String, List<Map<String, Any?>>>()
        for (tableName in order.sorted) {
            val table = schema.tables.getValue(tableName)
            result[tableName] = seedTable(tableName, table, countPerTable, circularEdges, valuePools)
        }
        return result
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
        val generator = ColumnValueGenerator(random, locale)
        val usedValues = mutableMapOf<String, MutableSet<Any?>>()
        val rows = ArrayList<Map<String, Any?>>(count)
        repeat(count) {
            val row = linkedMapOf<String, Any?>()
            for ((columnName, column) in table.columns) {
                row[columnName] =
                    columnValue(tableName, columnName, column, generator, circularEdges, valuePools, usedValues)
            }
            rows += row
            for ((columnName, _) in table.columns) {
                valuePools.getOrPut(tableName to columnName) { mutableListOf() } += row[columnName]
            }
        }
        return rows
    }

    private fun columnValue(
        tableName: String,
        columnName: String,
        column: ColumnDefinition,
        generator: ColumnValueGenerator,
        circularEdges: Set<FkEdge>,
        valuePools: Map<Pair<String, String>, List<Any?>>,
        usedValues: MutableMap<String, MutableSet<Any?>>,
    ): Any? {
        val reference = column.references
        if (reference != null) {
            return referencedValue(tableName, columnName, column, reference.table, reference.column, circularEdges, valuePools)
        }
        return try {
            uniqueAwareValue(tableName, columnName, column, generator, usedValues)
        } catch (e: UnsupportedSeedTypeException) {
            if (!column.required) {
                null
            } else {
                throw SeedPreflightException(
                    "Spalte '$tableName.$columnName' (${e.type}) kann in P1 nicht generiert werden und ist NOT NULL.",
                    e,
                )
            }
        }
    }

    private fun referencedValue(
        tableName: String,
        columnName: String,
        column: ColumnDefinition,
        targetTable: String,
        targetColumn: String,
        circularEdges: Set<FkEdge>,
        valuePools: Map<Pair<String, String>, List<Any?>>,
    ): Any? {
        val isCircular = circularEdges.any { it.fromTable == tableName && it.toTable == targetTable }
        if (isCircular) {
            if (!column.required) return null
            throw SeedPreflightException(
                "Spalte '$tableName.$columnName' ist Teil eines echten FK-Zyklus und NOT NULL " +
                    "-- kann in P1 nicht ohne Datenverlust befüllt werden.",
            )
        }
        val pool = valuePools[targetTable to targetColumn]
        if (pool.isNullOrEmpty()) {
            throw SeedPreflightException(
                "Spalte '$tableName.$columnName' referenziert '$targetTable.$targetColumn', " +
                    "dort wurden aber noch keine Werte generiert (FK-Reihenfolge inkonsistent).",
            )
        }
        return pool.random(random)
    }

    private fun uniqueAwareValue(
        tableName: String,
        columnName: String,
        column: ColumnDefinition,
        generator: ColumnValueGenerator,
        usedValues: MutableMap<String, MutableSet<Any?>>,
    ): Any? {
        val mustBeUnique = column.unique || column.type is NeutralType.Identifier
        if (!mustBeUnique) return generator.generate(column.type)

        val used = usedValues.getOrPut(columnName) { mutableSetOf() }
        repeat(MAX_UNIQUE_ATTEMPTS) {
            val candidate = generator.generate(column.type)
            if (used.add(candidate)) return candidate
        }
        throw SeedUniquenessExhaustedException(
            "Spalte '$tableName.$columnName' ist eindeutig, aber nach $MAX_UNIQUE_ATTEMPTS Versuchen " +
                "wurde kein neuer Wert gefunden (Wertebereich zu klein für die gewünschte Zeilenzahl).",
        )
    }

    companion object {
        private const val MAX_UNIQUE_ATTEMPTS = 50
    }
}
