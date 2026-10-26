package dev.dmigrate.core.seed

import dev.dmigrate.core.dependency.FkEdge
import dev.dmigrate.core.dependency.sortTablesByDependency
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import java.math.BigDecimal
import java.math.RoundingMode
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
    val random: Random,
    val circularEdges: Set<FkEdge>,
    val valuePools: Map<Pair<String, String>, List<Any?>>,
    val usedValues: MutableMap<String, MutableSet<Any?>>,
    val rules: SeedRuleSet?,
    /** Spalten, deren Werte eindeutig sein muessen: `unique`, ein einspaltiger UNIQUE-Constraint oder ein einspaltiger eindeutiger Index. */
    val uniqueColumns: Set<String> = emptySet(),
    /** AE-6: pro Spalte einmalig (Fisher-Yates) gemischte, dann konsumierte `values`-Warteschlange für `unique`. */
    val valueQueues: MutableMap<String, ArrayDeque<Any?>> = mutableMapOf(),
    /** Domain-Spalten der Tabelle (Basistyp und `CHECK` der Domain), je Spaltenname; die Spalten sind schon aufgeloest. */
    val domains: Map<String, DomainSeed> = emptyMap(),
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
class TableRowSeeder(
    private val random: Random,
    private val locale: SeedLocale,
    /** `--rules`-Regelmenge (P2); `null` = P1-Verhalten unverändert (AE-1). */
    private val rules: SeedRuleSet? = null,
) {

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
        // Vor dem ersten Schreiben: eine Domain-Spalte, fuer die kein Wert erzeugt werden kann, soll nicht erst die
        // dritte Tabelle abbrechen, nachdem die ersten beiden geschrieben sind.
        preflightDomainColumns(schema, order.sorted)
        for (tableName in order.sorted) {
            val table = schema.tables.getValue(tableName)
            onTable(tableName, seedTable(tableName, table, schema.customTypes, countPerTable, circularEdges, valuePools))
        }
    }

    private fun preflightDomainColumns(schema: SchemaDefinition, tableNames: List<String>) {
        for (tableName in tableNames) {
            val table = schema.tables.getValue(tableName)
            for ((columnName, domain) in DomainSeed.of(table.columns, schema.customTypes)) {
                val covered = table.columns.getValue(columnName).references != null ||
                    rules?.resolve(tableName, columnName) != null
                if (!covered) domain.preflight(tableName, columnName, locale)
            }
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

    /**
     * Eine Spalte ist eindeutig, wenn sie `unique` traegt oder allein einen UNIQUE-Constraint bzw. einen eindeutigen
     * Index ohne Praedikat und Ausdruck bildet. SQLite liefert Eindeutigkeit seit #53 am Index (`CREATE UNIQUE INDEX`),
     * nicht mehr an der Spalte; ein mehrspaltiger Schluessel bleibt unberuecksichtigt (Nicht-Scope).
     */
    private fun uniqueColumnsOf(table: TableDefinition): Set<String> {
        val fromConstraints = table.constraints
            .filter { it.type == ConstraintType.UNIQUE && it.columns?.size == 1 }
            .mapNotNull { it.columns?.single() }
        val fromIndices = table.indices
            .filter { it.unique && it.where == null && it.columns.size == 1 && it.columns.single().expression == null }
            .map { it.columns.single().name }
        return table.columns.filterValues { it.unique }.keys + fromConstraints + fromIndices
    }

    private fun seedTable(
        tableName: String,
        declared: TableDefinition,
        customTypes: Map<String, CustomTypeDefinition>,
        count: Int,
        circularEdges: Set<FkEdge>,
        valuePools: MutableMap<Pair<String, String>, MutableList<Any?>>,
    ): List<Map<String, Any?>> {
        // Eine Domain-Spalte erzeugt Werte ihres Basistyps unter dem CHECK der Domain (Issue #68).
        val domains = DomainSeed.of(declared.columns, customTypes)
        val table = declared.copy(
            columns = declared.columns.mapValues { (name, column) ->
                domains[name]?.baseType?.let { column.copy(type = it) } ?: column
            },
        )
        val ctx = TableSeedContext(
            tableName = tableName,
            generator = ColumnValueGenerator(random, locale),
            random = random,
            circularEdges = circularEdges,
            valuePools = valuePools,
            usedValues = mutableMapOf(),
            rules = rules,
            uniqueColumns = uniqueColumnsOf(table),
            domains = domains,
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
        if (columnName !in ctx.uniqueColumns) return pool.random(random)

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
        val entry = ctx.rules?.resolve(ctx.tableName, columnName)
        if (entry != null) {
            ctx.rules.markUsed(entry)
            return applyRule(entry.rule, columnName, column, ctx)
        }

        val mustBeUnique = columnName in ctx.uniqueColumns || column.type is NeutralType.Identifier
        if (!mustBeUnique) return generateValue(columnName, column, ctx)

        val used = ctx.usedValues.getOrPut(columnName) { mutableSetOf() }
        repeat(MAX_UNIQUE_ATTEMPTS) {
            val candidate = generateValue(columnName, column, ctx)
            if (used.add(candidate)) return candidate
        }
        throw SeedUniquenessExhaustedException(
            "Spalte '${ctx.tableName}.$columnName' ist eindeutig, aber nach $MAX_UNIQUE_ATTEMPTS Versuchen " +
                "wurde kein neuer Wert gefunden (Wertebereich zu klein für die gewünschte Zeilenzahl).",
        )
    }

    private fun generateValue(columnName: String, column: ColumnDefinition, ctx: TableSeedContext): Any? {
        val domain = ctx.domains[columnName] ?: return ctx.generator.generate(column.type)
        return domain.generate(ctx.tableName, columnName, ctx.generator, ctx.random)
    }

    // ---- --rules-Anwendung (P2, AE-4/AE-5/AE-6) -------------------------------------

    private fun applyRule(rule: ColumnRule, columnName: String, column: ColumnDefinition, ctx: TableSeedContext): Any? =
        when (rule) {
            is ColumnRule.Values -> applyValuesRule(rule, columnName, column, ctx)
            is ColumnRule.Range -> applyRangeRule(rule, columnName, column, ctx)
            is ColumnRule.Template -> applyTemplateRule(rule, columnName, column, ctx)
        }

    private fun applyValuesRule(
        rule: ColumnRule.Values,
        columnName: String,
        column: ColumnDefinition,
        ctx: TableSeedContext,
    ): Any? {
        val incompatible = rule.values.firstOrNull { !isValueTypeCompatible(it, column.type) }
        if (incompatible != null || rule.values.isEmpty()) {
            throw SeedPreflightException(
                "Spalte '${ctx.tableName}.$columnName' hat eine --rules values-Regel mit einem Wert, " +
                    "der nicht zum Spaltentyp ${column.type} passt.",
            )
        }
        val mustBeUnique = columnName in ctx.uniqueColumns || column.type is NeutralType.Identifier
        return if (mustBeUnique) sampleValuesWithoutReplacement(rule, columnName, ctx) else sampleValuesWeighted(rule)
    }

    /**
     * AE-6 (Review-Fix): `unique`-Spalten ziehen aus der `values`-Liste OHNE
     * Zurücklegen (einmal pro Spalte Fisher-Yates-gemischt, dann per
     * Warteschlange konsumiert) statt über den bestehenden
     * Rejection-Sampling-Loop -- der schlägt bei `values.size` nahe `count`
     * mathematisch erwartbar häufig fehl (s. ImpPlan AE-6). `weights` wird
     * hier bewusst ignoriert: eine gewichtete Ziehung ohne Zurücklegen hat
     * keine über den ganzen Lauf stabile Bedeutung.
     */
    private fun sampleValuesWithoutReplacement(rule: ColumnRule.Values, columnName: String, ctx: TableSeedContext): Any? {
        val queue = ctx.valueQueues.getOrPut(columnName) { ArrayDeque(rule.values.shuffled(random)) }
        if (queue.isEmpty()) {
            throw SeedUniquenessExhaustedException(
                "Spalte '${ctx.tableName}.$columnName' ist eindeutig und nutzt eine --rules values-Liste " +
                    "(${rule.values.size} Wert(e)), die für die gewünschte Zeilenzahl erschöpft ist.",
            )
        }
        return queue.removeFirst()
    }

    private fun sampleValuesWeighted(rule: ColumnRule.Values): Any? {
        val weights = rule.weights ?: return rule.values[random.nextInt(rule.values.size)]
        val total = weights.sum()
        var draw = random.nextDouble() * total
        for (index in rule.values.indices) {
            draw -= weights[index]
            if (draw <= 0.0) return rule.values[index]
        }
        return rule.values.last()
    }

    private fun applyRangeRule(rule: ColumnRule.Range, columnName: String, column: ColumnDefinition, ctx: TableSeedContext): Any? =
        renderRangeValue(rule, column.type) ?: throw SeedPreflightException(
            "Spalte '${ctx.tableName}.$columnName' hat eine --rules range-Regel, ist aber vom Typ " +
                "${column.type} (nicht numerisch) -- range passt nur auf numerische Spalten.",
        )

    private fun renderRangeValue(rule: ColumnRule.Range, type: NeutralType): Any? = when (type) {
        is NeutralType.Identifier, NeutralType.Integer, NeutralType.SmallInt, NeutralType.BigInteger ->
            random.nextLong(rule.min.toLong(), rule.max.toLong() + 1)
        is NeutralType.Float -> boundedRandomDouble(rule.min, rule.max)
        is NeutralType.Decimal -> BigDecimal.valueOf(boundedRandomDouble(rule.min, rule.max))
            .setScale(type.scale.coerceAtLeast(0), RoundingMode.HALF_UP)
        else -> null
    }

    private fun boundedRandomDouble(min: Double, max: Double): Double =
        if (min >= max) min else random.nextDouble(min, max)

    private fun applyTemplateRule(
        rule: ColumnRule.Template,
        columnName: String,
        column: ColumnDefinition,
        ctx: TableSeedContext,
    ): String {
        if (!isValueTypeCompatible("", column.type)) {
            throw SeedPreflightException(
                "Spalte '${ctx.tableName}.$columnName' hat eine --rules template-Regel, ist aber vom Typ " +
                    "${column.type} (nicht textuell) -- template erzeugt nur Text.",
            )
        }
        return ctx.generator.renderTemplate(rule.segments)
    }

    /** AE-5: Typ-Kompatibilität einer `--rules`-`values`/`template`-Ausprägung mit dem Spaltentyp. `null` passt immer. */
    private fun isValueTypeCompatible(value: Any?, type: NeutralType): Boolean {
        if (value == null) return true
        return when (type) {
            is NeutralType.Identifier, NeutralType.Integer, NeutralType.SmallInt, NeutralType.BigInteger ->
                value is Long || value is Int
            is NeutralType.Float -> value is Double || value is Long || value is Int
            is NeutralType.Decimal -> value is BigDecimal || value is Double || value is Long || value is Int
            NeutralType.BooleanType -> value is Boolean
            is NeutralType.Text, is NeutralType.Char, NeutralType.Email, NeutralType.Json, NeutralType.Xml ->
                value is String
            NeutralType.Uuid, NeutralType.Date, NeutralType.Time, is NeutralType.DateTime -> value is String
            else -> true
        }
    }

    companion object {
        private const val MAX_UNIQUE_ATTEMPTS = 50
    }
}
