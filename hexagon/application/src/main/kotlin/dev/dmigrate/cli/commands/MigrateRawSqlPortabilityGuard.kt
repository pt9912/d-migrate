package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * Ob ein roher Ausdruck auf dem Zieldialekt parsebar ist: `null` = ja, sonst
 * der Grund.
 *
 * Ein Port, weil das Urteil die Grammatik der Dialekte kennt und damit in den
 * Adapter gehoert (`RawSqlExpressionPortability`) — die Anwendungsschicht
 * behandelt die Referenz als undurchsichtig, wie bei den uebrigen
 * Preflight-Sonden auch.
 */
typealias RawSqlPortabilityFn = (String?, DatabaseDialect) -> String?

/**
 * Rohen SQL-Text, den das **Ziel** nicht parsen kann, vor dem Anwenden
 * abfangen.
 *
 * **Warum hier und nicht in den Renderern.** Der generate-Pfad verwirft
 * solchen Text seit dem Konsumentenbefund benannt (`E053`) — je Dialekt fuer
 * CHECK und Berechnung, zentral fuer den Funktions-Default. Der migrate-Pfad
 * tat es fuer PostgreSQL, MySQL und SQLite nicht: ihre Diff-Renderer bauen die
 * Spaltenzeile selbst, und SQLite zusaetzlich im Tabellen-Neubau. Das waeren
 * vier weitere Aufrufstellen gewesen, mit vier Gelegenheiten, dass die Aussage
 * auseinanderlaeuft. Diese Stelle liegt **hinter allen fuenf Renderern**.
 *
 * **Warum blocken statt weglassen.** Im generate-Pfad entsteht eine Datei, die
 * jemand liest, bevor er sie anwendet — dort ist Weglassen plus Meldung das
 * Richtige. `schema migrate --execute` schickt die Anweisung sofort an den
 * Server; ihn ablehnen zu lassen heisst, den Lauf **mitten** in einer Folge
 * angewandter Anweisungen abzubrechen. Ein Blocker davor ist billiger als eine
 * halb angewandte Migration — und er nimmt dem Anwender keine Entscheidung ab,
 * die er nicht ohnehin treffen muesste.
 *
 * Beurteilt werden nur Operationen, die wirklich **gerendert** wurden: was
 * ohnehin uebersprungen ist, braucht keine zweite Absage.
 */
internal object MigrateRawSqlPortabilityGuard {

    /** Ein Fund: die Operation, das Feld und warum der Text hier nicht gilt. */
    private data class Finding(val operationId: String, val field: String, val reason: String)

    fun apply(
        rendered: MigrationDdlResult,
        plan: DiffResult,
        dialect: DatabaseDialect,
        assess: RawSqlPortabilityFn?,
    ): MigrationDdlResult {
        if (assess == null) return rendered
        val findings = plan.operations
            .filter { it.id in rendered.operationsRendered }
            .flatMap { findingsFor(it, dialect, assess) }
        if (findings.isEmpty()) return rendered

        val diagnostics = findings.map { finding ->
            DiffDiagnostic(
                code = CODE,
                message = "The ${finding.field} in operation ${finding.operationId} is not valid for " +
                    "${dialect.name.lowercase()} (${finding.reason}); d-migrate does not translate raw SQL " +
                    "expressions between dialects, so the migration was not applied.",
                severity = DiffDiagnostic.Severity.BLOCKER,
                operationId = finding.operationId,
            )
        }
        val ids = findings.map { it.operationId }.toSet()
        return rendered.copy(
            diagnostics = rendered.diagnostics + diagnostics,
            blockers = rendered.blockers + MigrationBlocker(
                reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                operationIds = ids,
            ),
            primaryBlockedReason = rendered.primaryBlockedReason ?: MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
        )
    }

    /** Derselbe Code wie im generate-Pfad: dieselbe Aussage, anderer Weg. */
    const val CODE: String = "E053"

    private fun findingsFor(
        op: DiffOperation,
        dialect: DatabaseDialect,
        assess: RawSqlPortabilityFn,
    ): List<Finding> = when (op) {
        is DiffOperation.CreateTable -> buildList {
            op.table.columns.forEach { (name, column) -> addAll(columnFindings(op.id, name, column, dialect, assess)) }
            op.table.constraints.forEach { constraint ->
                add(check(op.id, "CHECK expression of '${constraint.name}'", constraint.expression, dialect, assess))
            }
            op.table.indices.forEach { index -> addAll(indexFindings(op.id, index.name, index, dialect, assess)) }
        }.filterNotNull()

        is DiffOperation.AddColumn ->
            columnFindings(op.id, op.objectRef.path.last(), op.column, dialect, assess)

        is DiffOperation.AddConstraint ->
            listOfNotNull(
                check(op.id, "CHECK expression of '${op.constraint.name}'", op.constraint.expression, dialect, assess),
            )

        is DiffOperation.AddIndex -> indexFindings(op.id, op.index.name, op.index, dialect, assess)

        is DiffOperation.AlterColumnGeneration ->
            listOfNotNull(
                check(op.id, "computed expression", (op.after as? ColumnGeneration.Computed)?.expression, dialect, assess),
            )

        is DiffOperation.AlterColumnDefault ->
            listOfNotNull(check(op.id, "function default", functionText(op.after), dialect, assess))

        else -> emptyList()
    }

    private fun columnFindings(
        opId: String,
        colName: String,
        column: ColumnDefinition,
        dialect: DatabaseDialect,
        assess: RawSqlPortabilityFn,
    ): List<Finding> = listOfNotNull(
        check(
            opId,
            "computed expression of '$colName'",
            (column.generation as? ColumnGeneration.Computed)?.expression,
            dialect,
            assess,
        ),
        check(opId, "function default of '$colName'", functionText(column.default), dialect, assess),
    )

    private fun indexFindings(
        opId: String,
        indexName: String?,
        index: dev.dmigrate.core.model.IndexDefinition,
        dialect: DatabaseDialect,
        assess: RawSqlPortabilityFn,
    ): List<Finding> {
        val label = indexName ?: "an index"
        return listOfNotNull(check(opId, "predicate of index '$label'", index.where, dialect, assess)) +
            index.columns.mapNotNull { column ->
                check(opId, "expression key of index '$label'", column.expression, dialect, assess)
            }
    }

    private fun functionText(default: DefaultValue?): String? = (default as? DefaultValue.FunctionCall)?.name

    private fun check(
        opId: String,
        field: String,
        text: String?,
        dialect: DatabaseDialect,
        assess: RawSqlPortabilityFn,
    ): Finding? {
        val reason = assess(text, dialect) ?: return null
        return Finding(opId, field, reason)
    }
}
