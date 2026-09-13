package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * oracle-doppelter-generierungsausdruck.md: zwei Faelle, live gegen Oracle 23
 * gemessen, in denen der Server eine Tabelle ablehnt, die jeder andere
 * Dialekt annimmt.
 *
 * **`E073` — doppelter Ausdruckstext.** Zwei berechnete Spalten derselben
 * Tabelle mit identischem Ausdruckstext scheitern mit `ORA-54015`. Gemessen
 * gilt das fuer `VIRTUAL` UND `MATERIALIZED`, auch **gemischt** (eine
 * `VIRTUAL`, eine `MATERIALIZED`, derselbe Text) — anders als der urspruengliche
 * Befund vermutete, der beide Formen getrennt maass. Die Pruefung hier
 * unterscheidet deshalb nicht nach `stored`.
 *
 * Oracle normalisiert dabei mehr als nur Leerzeichen: Operanden-Reihenfolge
 * (`a*b`/`b*a`), Funktionsnamens-Grossschreibung (`UPPER`/`upper`) und
 * zusaetzliche Klammern faellen im Live-Test alle unter dieselbe Meldung.
 * Diese Pruefung ist bewusst **nur textuell** (getrimmt, sonst wortgleich) —
 * sie faengt die Faelle, die ein Mensch baut (dieselbe Rechnung zweimal
 * hingeschrieben), nicht die normalisierten. Eine semantische Normalisierung
 * braeuchte einen Ausdrucks-Parser (siehe
 * `docs/planning/open/check-ausdruck-analyse-per-parser.md`) und ist bewusst
 * nicht Teil dieser Pruefung — die verbleibenden Faelle laufen bis zum Server
 * durch und kommen von dort als `ORA-54015` zurueck, benannt durch den
 * bestehenden Ausfuehrungspfad (kein Sonderfall noetig: jeder SQL-Fehler beim
 * `--execute` landet bereits strukturiert in `ExecutionTrace.executionError`,
 * nicht als roher Stacktrace).
 *
 * **`E074` — Index-Kollision.** Ein Ausdrucks-Index mit demselben Text wie
 * eine berechnete Spalte laesst sich anlegen; ein **zweiter**, gewoehnlicher
 * Index direkt auf dieser Spalte scheitert danach mit `ORA-01408` ("such
 * column list already indexed") — fuer Oracle ist die berechnete Spalte
 * intern derselbe Ausdruck wie der Index, beide Indizes waeren also
 * doppelt. Gemessen gilt das auch fuer `MATERIALIZED`, nicht nur `VIRTUAL`.
 */
internal object OracleComputedExpressionDuplication {

    const val DUPLICATE_EXPRESSION: String = "E073"
    const val EXPRESSION_INDEX_COLLISION: String = "E074"

    /**
     * Namen der Geschwisterspalten von [column], deren (getrimmter)
     * Ausdruckstext mit [expression] uebereinstimmt — leer, wenn keine.
     * Fuer den Migrate-Pfad: prueft nur die eine Spalte, die eine Operation
     * gerade einfuehrt oder aendert, nicht die ganze Tabelle (ein
     * vorbestehendes, von dieser Operation unberuehrtes Duplikat waere kein
     * Befund dieser Operation).
     */
    fun duplicateSiblings(table: TableDefinition, column: String, expression: String): List<String> {
        val trimmed = expression.trim()
        return table.columns.entries
            .filter { (name, def) ->
                name != column && (def.generation as? ColumnGeneration.Computed)?.expression?.trim() == trimmed
            }
            .map { it.key }
            .sorted()
    }

    /**
     * Alle Gruppen berechneter Spalten von [table] mit identischem
     * (getrimmten) Ausdruckstext — fuer den Vollschema-Check (`schema
     * generate`). Jede Gruppe hat mindestens zwei Spaltennamen.
     */
    fun duplicateGroups(table: TableDefinition): List<List<String>> =
        table.columns.entries
            .mapNotNull { (name, def) -> (def.generation as? ColumnGeneration.Computed)?.expression?.trim()?.let { it to name } }
            .groupBy({ it.first }, { it.second })
            .values
            .filter { it.size > 1 }
            .map { it.sorted() }

    /** Ob [table] einen Index traegt, dessen (getrimmter) Ausdruckstext [expression] entspricht. */
    private fun hasExpressionIndex(table: TableDefinition, expression: String): Boolean {
        val trimmed = expression.trim()
        return table.indices.any { idx -> idx.columns.any { it.expression?.trim() == trimmed } }
    }

    /** Ob [table] einen gewoehnlichen (nicht-Ausdrucks-)Index exakt auf [column] traegt. */
    private fun hasPlainColumnIndex(table: TableDefinition, column: String): Boolean =
        table.indices.any { it.columnNames == listOf(column) }

    /**
     * Ob die berechnete Spalte [column] (Ausdruck [expression]) mit einem
     * bestehenden Ausdrucks-Index kollidiert, weil [table] zusaetzlich einen
     * gewoehnlichen Index direkt auf [column] traegt (`ORA-01408`). Fuer den
     * Vollschema-Check — iteriert ueber jede berechnete Spalte.
     */
    fun collidesWithExpressionIndex(table: TableDefinition, column: String, expression: String): Boolean =
        hasPlainColumnIndex(table, column) && hasExpressionIndex(table, expression)

    /**
     * Ob [index] — neu auf [table] hinzugefuegt — mit einem bereits
     * bestehenden Index derselben Tabelle kollidiert (`ORA-01408`). Prueft
     * beide Richtungen: [index] selbst ist der neue gewoehnliche
     * Spalten-Index (Gegenstueck ist ein Ausdrucks-Index) oder [index] ist
     * der neue Ausdrucks-Index (Gegenstueck ist ein gewoehnlicher
     * Spalten-Index auf einer berechneten Spalte mit demselben Ausdruck).
     */
    fun indexCollides(table: TableDefinition, index: IndexDefinition): Boolean {
        val plainColumn = index.columnNames.singleOrNull()
            ?.takeIf { index.columns.size == 1 && index.columns.single().expression == null }
        if (plainColumn != null) {
            val computed = table.columns[plainColumn]?.generation as? ColumnGeneration.Computed
            if (computed != null && hasExpressionIndex(table, computed.expression)) return true
        }
        val exprText = index.columns.singleOrNull()?.expression?.takeIf { index.columns.size == 1 }
        if (exprText != null) {
            val trimmed = exprText.trim()
            val matchingComputedColumns = table.columns.entries
                .filter { (_, def) -> (def.generation as? ColumnGeneration.Computed)?.expression?.trim() == trimmed }
                .map { it.key }
            if (matchingComputedColumns.any { hasPlainColumnIndex(table, it) }) return true
        }
        return false
    }

    /**
     * Bricht [op] mit `E073` ab -- geteilt zwischen [OracleDiffTableOps]s
     * `CreateTable`/`AddColumn`/`AlterColumnGeneration`-Renderern, damit
     * keiner der drei Aufrufer die Meldung eigenstaendig formuliert.
     */
    fun blockDuplicateExpression(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        table: String,
        duplicateColumns: List<String>,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} would create/leave columns ${duplicateColumns.joinToString { "'$it'" }} of " +
                "table '$table' with the same computed-column expression text. Oracle refuses that with " +
                "ORA-54015 (\"Duplicate column expression was specified\", measured against 23), regardless " +
                "of VIRTUAL/MATERIALIZED. Give each column a distinct expression, or drop the duplicate.",
            code = DUPLICATE_EXPRESSION,
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
    }

    /** Bricht [op] mit `E074` ab -- siehe [blockDuplicateExpression]. */
    fun blockIndexCollision(
        op: DiffOperation,
        ctx: OracleDiffRenderContext,
        table: String,
        column: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} would leave table '$table' with both an expression index and a plain index " +
                "on the computed column '$column', which carries the same expression. Oracle treats both as " +
                "the same index and refuses the second one with ORA-01408 (\"such column list already " +
                "indexed\", measured against 23). Drop one of the two indexes.",
            code = EXPRESSION_INDEX_COLLISION,
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, setOf(op.id))
    }
}
