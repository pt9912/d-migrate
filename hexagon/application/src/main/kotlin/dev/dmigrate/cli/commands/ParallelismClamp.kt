package dev.dmigrate.cli.commands

/**
 * Resolves the effective degree of parallelism for the data path
 * (LN-007 / LN-008, ADR 0032).
 *
 * A SQLite source or target caps out at a single pooled connection
 * (`HikariConnectionPoolFactory` forces `maximumPoolSize = 1`), so any
 * requested `--parallel N > 1` is clamped to 1 and the caller is told
 * once, via [onClamp], that the run stays sequential. Non-SQLite paths
 * keep the requested degree (floored at 1).
 *
 * Pure and side-effect-free apart from the [onClamp] note, so it unit-
 * tests without a live connection.
 */
internal object ParallelismClamp {

    fun effective(
        requested: Int,
        involvesSqlite: Boolean,
        sourceLabel: String = "--parallel $requested",
        onClamp: (String) -> Unit = {},
    ): Int {
        val floored = requested.coerceAtLeast(1)
        if (floored > 1 && involvesSqlite) {
            onClamp(
                "$sourceLabel ignored: SQLite uses a single connection (pool size 1); " +
                    "running sequentially.",
            )
            return 1
        }
        return floored
    }

    /**
     * pipeline.parallelism-Slice: reduziert **config-basiertes** `parallel > 1` auf 1 (sequenziell)
     * mit herkunftsbewusstem Hinweis, wenn eine damit inkompatible Option ([incompatibleFlag], z. B.
     * `--resume`/`--atomic`) aktiv ist. **CLI-explizites** `--parallel` wird hier NICHT behandelt —
     * dafür scheitern die Runner vorher hart. [incompatibleFlag] `null` = keine inkompatible Option
     * aktiv → [parallel] unverändert. Gemeinsam für Export/Import/Transfer (sonst 3× dupliziert).
     */
    fun fallbackIfIncompatible(
        parallel: Int,
        fromCli: Boolean,
        sourceLabel: String,
        incompatibleFlag: String?,
        onNote: (String) -> Unit,
    ): Int {
        if (parallel > 1 && !fromCli && incompatibleFlag != null) {
            onNote("$sourceLabel ignored with $incompatibleFlag: running sequentially.")
            return 1
        }
        return parallel
    }
}
