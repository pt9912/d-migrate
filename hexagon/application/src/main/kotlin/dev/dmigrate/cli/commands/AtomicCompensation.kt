package dev.dmigrate.cli.commands

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter

/**
 * LN-013: Clean-Load-Rollback für einen fehlgeschlagenen `--atomic`-Lauf
 * (`data import`/`data transfer`).
 *
 * Setzt den **vollständigen** Operations-Tabellensatz auf leer zurück — so wird
 * „alle Tabellen oder keine" auf einer bekannt-leeren Basis hergestellt, ohne den
 * >10-TB-Streaming-Pfad zu brechen (Truncate ist eine O(1)-Metadaten-Operation,
 * unabhängig vom Datenvolumen). Kompensationsbasiert, also unabhängig von
 * Checkpoints (deshalb auch im checkpoint-freien Transfer nutzbar).
 *
 * Vertrag: best-effort + geloggt. Die Kompensations-Truncate ist selbst nicht
 * transaktional, aber idempotent — ein `--atomic`-Lauf startet ohnehin per
 * erzwungenem `--truncate` sauber, sodass ein abgebrochenes Rollback beim Re-Run
 * re-cleant wird (ADR 0031).
 */
internal object AtomicCompensation {
    fun rollback(
        writer: DataWriter,
        pool: ConnectionPool,
        tables: List<String>,
        stderr: (String) -> Unit,
    ) {
        try {
            writer.truncateTables(pool, tables)
            stderr("atomic rollback: reverted ${tables.size} table(s) to empty state (no partial import left)")
        } catch (e: Exception) {
            stderr(
                "atomic rollback FAILED: ${e.message} — target may hold partial data; " +
                    "re-run with --truncate to clean up"
            )
        }
    }
}
