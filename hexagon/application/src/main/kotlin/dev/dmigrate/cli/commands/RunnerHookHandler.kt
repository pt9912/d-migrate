package dev.dmigrate.cli.commands

import java.sql.Statement

/**
 * Phase H.3b: shared runner-hook parser + side-effect applier.
 *
 * The SQLite-rebuild renderer emits comment-marker statements of the
 * form `-- dmigrate:runner-hook=<name>` instead of pauschal `PRAGMA
 * foreign_keys = ON;` when [dev.dmigrate.driver.DdlGenerationOptions.executionMode]
 * is `EXECUTE`. This handler:
 *
 * - recognises the markers via [parseHook] (whitespace-tolerant),
 * - applies the per-hook side-effect via [apply] using the **same
 *   outer JDBC Statement** as the main migration stream — xerial-
 *   sqlite finalises the outer Statement when a sibling Statement is
 *   created on the same connection mid-iteration, so all PRAGMA-state
 *   reads/restores go through [jdbcStmt] directly.
 *
 * Lives in `hexagon:application` so both `JdbcMigrationExecutor`
 * (production CLI path) and the `MigrationExecutorTestSupport`
 * test-fixture variant share one implementation — keeping H.3b's
 * hook contract testable from the Application layer.
 */
object RunnerHookHandler {

    /** Allowlisted hook names — anything else is treated as a regular SQL statement. */
    private val ALLOWED_HOOKS = setOf(
        "save-fk-state-before-pragma-off",
        "restore-fk-state",
    )

    /** Per-stream state holder. One instance per `runStreamOwnedTransaction` invocation. */
    data class State(var savedSqliteForeignKeysPragma: Int? = null)

    /**
     * Returns the hook name when [sql] is a recognised runner-hook
     * marker comment of the form `-- dmigrate:runner-hook=<name>`
     * (whitespace-tolerant, name must be in [ALLOWED_HOOKS]), else null.
     *
     * The allowlist is intentional: an unknown hook name is treated
     * as a regular SQL comment that the JDBC driver will ignore,
     * rather than as a runner error. Allowlist drift between the
     * renderer and this parser is caught at test time by
     * `JdbcMigrationExecutorH3bTest`.
     */
    fun parseHook(sql: String): String? {
        val trimmed = sql.trim()
        val prefix = "-- dmigrate:runner-hook="
        if (!trimmed.startsWith(prefix)) return null
        val name = trimmed.removePrefix(prefix).trim().takeWhile { it != '\n' }
        return if (name in ALLOWED_HOOKS) name else null
    }

    /**
     * Applies the side-effect for [hook] using the outer migration-
     * stream Statement [jdbcStmt]. The hook must already have been
     * extracted by [parseHook]; pass [hook] verbatim. Updates [state]
     * in place.
     *
     * Contract: NEVER opens a sibling Statement on the connection.
     * xerial-sqlite invalidates the outer Statement when a sibling
     * is created mid-iteration ("The prepared statement has been
     * finalized" on the next execute against the original
     * Statement).
     */
    fun apply(jdbcStmt: Statement, hook: String, state: State) {
        when (hook) {
            "save-fk-state-before-pragma-off" -> {
                state.savedSqliteForeignKeysPragma = readPragmaForeignKeys(jdbcStmt)
            }
            "restore-fk-state" -> {
                // If no prior save (renderer-bug or mid-stream hook
                // without paired save) default to ON — the standalone-
                // mode tail and the safer post-migration state.
                val value = state.savedSqliteForeignKeysPragma ?: 1
                jdbcStmt.execute("PRAGMA foreign_keys = $value;")
            }
            else -> error("RunnerHookHandler: unrecognised hook `$hook` — parseHook should have rejected it")
        }
    }

    /**
     * Convenience for executor loops: parse and apply if the
     * statement is a hook, else execute as regular SQL. Returns
     * `true` if the statement was a hook (and therefore consumed),
     * `false` if it should be counted as a normal SQL execution.
     */
    fun executeOrApply(jdbcStmt: Statement, sql: String, state: State): Boolean {
        val hook = parseHook(sql)
        if (hook == null) {
            jdbcStmt.execute(sql)
            return false
        }
        apply(jdbcStmt, hook, state)
        return true
    }

    private fun readPragmaForeignKeys(jdbcStmt: Statement): Int {
        jdbcStmt.executeQuery("PRAGMA foreign_keys;").use { rs ->
            return if (rs.next()) rs.getInt(1) else 0
        }
    }
}
