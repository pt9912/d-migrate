package dev.dmigrate.driver

/**
 * Rendert ein [DdlResult] als **ausführbares Skript** für einen Dialekt.
 *
 * Unterschied zu [DdlResult.render]: Dialekte mit Batch-Semantik
 * ([DialectCapabilities.batchSeparator], heute T-SQL `GO`) bekommen nach jedem
 * ausführbaren Statement einen eigenen Batch-Trenner. SQL Server verlangt,
 * dass `CREATE VIEW`/`CREATE OR ALTER …`/Routinen allein in ihrem Batch
 * stehen; Datei-Konsumenten wie sqlcmd, SSMS oder Flyway trennen Batches nur
 * an `GO`-Zeilen (nicht an `;`). Ein Trenner nach **jedem** Statement
 * entspricht genau der statementweisen Ausführung des d-migrate-Runners.
 *
 * `GO` ist kein T-SQL und darf deshalb nie Teil eines [DdlStatement] sein —
 * es gehört ausschließlich in diese Skript-Darstellung (Dateiausgabe von
 * `schema generate`, Tool-Export-Artefakte, MCP-Artefakt). Hinweis-Zeilen
 * (`-- [W…]`) und reine Kommentar-Statements eröffnen keinen Batch.
 *
 * Ebenfalls nur hier: die [DialectCapabilities.scriptPreamble] — für T-SQL die
 * SET-Optionen, ohne die ein `CREATE INDEX … WHERE` unter `sqlcmd` mit
 * Msg 1934 scheitert (`QUOTED_IDENTIFIER OFF` per Default).
 */
object DdlScript {

    fun render(result: DdlResult, dialect: DatabaseDialect): String =
        renderStatements(result.statements, dialect)

    fun renderPhase(result: DdlResult, phase: DdlPhase, dialect: DatabaseDialect): String =
        renderStatements(result.statementsForPhase(phase), dialect)

    private fun renderStatements(statements: List<DdlStatement>, dialect: DatabaseDialect): String {
        val capabilities = DialectCapabilities.forDialect(dialect)
        val separator = capabilities.batchSeparator
            ?: return statements.joinToString("\n\n") { it.render() }
        val body = statements.joinToString("\n\n") { statement ->
            val rendered = statement.render()
            if (isExecutable(statement.sql)) "$rendered\n$separator" else rendered
        }
        val preamble = capabilities.scriptPreamble ?: return body
        // Eigener Batch: die SET-Optionen müssen vor den folgenden Batches wirken.
        return "$preamble\n$separator\n\n$body"
    }

    /** Kommentar- oder leere Statements tragen keinen Batch. */
    private fun isExecutable(sql: String): Boolean =
        sql.lines().any { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("--")
        }
}
