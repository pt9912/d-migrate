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
 *
 * Neben dem Trenner je **Dialekt** gibt es den Trenner je **Anweisung**
 * ([DdlStatement.scriptTerminator]). Er geht vor und deckt den Fall ab, den
 * ein Dialekt-Trenner nicht trifft: einen PL/SQL-Block, dessen Text selbst
 * Semikola führt und den SQL*Plus erst an einem `/` enden sieht — in einem
 * Skript, dessen übrige Anweisungen keinen brauchen.
 */
object DdlScript {

    fun render(result: DdlResult, dialect: DatabaseDialect): String =
        renderStatements(result.statements, dialect)

    fun renderPhase(result: DdlResult, phase: DdlPhase, dialect: DatabaseDialect): String =
        renderStatements(result.statementsForPhase(phase), dialect)

    /**
     * Eine einzelne Anweisung in Skriptform, ohne Präambel.
     *
     * Für Konsumenten, die die Anweisungsgrenzen brauchen statt des ganzen
     * Skripts — ein Werkzeug etwa, das je Anweisung einen eigenen Block
     * schreibt und sie nicht aus dem Text zurückschneiden können soll.
     */
    fun renderStatement(statement: DdlStatement, dialect: DatabaseDialect): String {
        val separator = DialectCapabilities.forDialect(dialect).batchSeparator
        val rendered = statement.render()
        // Der Trenner am Statement geht vor: er steht dort, weil diese eine
        // Anweisung ihn braucht, nicht weil der Dialekt Batches führt.
        val terminator = statement.scriptTerminator ?: separator
        return if (terminator != null && isExecutable(statement.sql)) "$rendered\n$terminator" else rendered
    }

    private fun renderStatements(statements: List<DdlStatement>, dialect: DatabaseDialect): String {
        val capabilities = DialectCapabilities.forDialect(dialect)
        val body = statements.joinToString("\n\n") { renderStatement(it, dialect) }
        val preamble = capabilities.scriptPreamble ?: return body
        // Eigener Batch: die SET-Optionen müssen vor den folgenden Batches wirken.
        return "$preamble${capabilities.batchSeparator?.let { "\n$it" }.orEmpty()}\n\n$body"
    }

    /** Kommentar- oder leere Statements tragen keinen Batch. */
    private fun isExecutable(sql: String): Boolean =
        sql.lines().any { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("--")
        }
}
