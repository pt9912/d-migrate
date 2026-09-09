package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlSequenceSupportNaming
import dev.dmigrate.driver.SequenceCapabilityDefaults
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.MssqlSequenceResume
import dev.dmigrate.driver.OracleSequenceResume

/**
 * Atomic-Preserve Phase C.1 (2026-06-01): per-dialect restore SQL
 * built lazily by [SequencePreserveStage]'s
 * `AtomicSequencePreserveRequest.renderRestore` closures.
 *
 * The atomic executor probes the live sequence value **inside the
 * transaction** at lock time and hands the result to this builder;
 * the returned SQL runs on the same locked connection between the
 * protected operations and `COMMIT`. The shape mirrors the existing
 * UP-side rendering in `*DiffSequenceOps.renderAlterSequenceCurrentValue`
 * for each dialect, but is reproduced here because:
 *
 * - The renderer methods emit through a dialect-specific
 *   `*DiffRenderContext.emit(...)` mechanism instead of returning a
 *   plain `List<String>`.
 * - [SequencePreserveStage] lives in `:hexagon:application`, which
 *   may not depend on adapter modules — the helpers below use
 *   identifiers and constants exposed by `:hexagon:ports-read` /
 *   `:hexagon:ports-common` only.
 *
 * Drift between this object and the dialect renderers' restore SQL
 * is a real regression risk; Phase D/E will extract a shared helper
 * once the dialect renderers no longer go through the context-emit
 * pattern. Until then both implementations carry identical SQL
 * templates per dialect.
 */
internal object AtomicPreserveRestoreSql {

    fun forDialect(
        dialect: DatabaseDialect,
        sequenceRef: SequenceObjectRef,
        probe: SequenceCurrentValueProbeResult.Read,
        /**
         * Die Definition der Sequenz, wo der Dialekt sie zum Fortsetzen
         * braucht. SQL Server rechnet den Fortsetzungspunkt aus Schrittweite
         * und Schranken; die uebrigen drei schreiben den probierten Wert
         * unveraendert zurueck und lassen sie deshalb `null`.
         */
        sequence: SequenceDefinition? = null,
    ): List<String> {
        // Capability-gefuehrt statt hartcodierter Dialekt-Aufzaehlung: ein
        // Dialekt, der spaeter ein Preserve-Fenster bekommt, braucht hier keine
        // Anpassung -- nur einen echten `when`-Zweig weiter unten.
        check(SequenceCapabilityDefaults.forDialect(dialect).preserveWindowIsolation.guardsWindow) {
            "unreachable: SequenceCapabilityDefaults declares no preserve window for " +
                "${dialect.name.lowercase()}, der Atomic-Pfad waehlt den Dialekt also nie aus."
        }
        return when (dialect) {
            DatabaseDialect.POSTGRESQL -> postgres(sequenceRef, probe)
            DatabaseDialect.MYSQL -> mysql(sequenceRef, probe)
            DatabaseDialect.SQLITE -> sqlite(sequenceRef, probe)
            DatabaseDialect.MSSQL -> mssql(sequenceRef, probe, sequence)
            DatabaseDialect.ORACLE -> oracle(sequenceRef, probe, sequence)
        }
    }

    private fun postgres(
        sequenceRef: SequenceObjectRef,
        probe: SequenceCurrentValueProbeResult.Read,
    ): List<String> {
        val literal = SqlIdentifiers.quoteStringLiteral(sequenceRef.name, DatabaseDialect.POSTGRESQL)
        val isCalled = requireNotNull(probe.isCalled) {
            "PG atomic-preserve restore requires isCalled on probe " +
                "(sequence=${sequenceRef.name})"
        }
        return listOf("SELECT setval($literal, ${probe.value}, $isCalled);")
    }

    /**
     * `sys.sequences.current_value` ist der zuletzt **ausgegebene** Wert,
     * `RESTART WITH` setzt den **naechsten** — dazwischen liegt die
     * Schrittweite. Den probierten Wert unveraendert zurueckzuschreiben gaebe
     * ihn ein zweites Mal aus.
     *
     * Fehlt die Definition oder ist die Sequenz an ihrem Rand erschoepft,
     * wird **geworfen** statt geraten: der Executor macht daraus ein benanntes
     * `Failed` mit Rollback, und der Anwender sieht, warum. Ein geratener
     * Fortsetzungspunkt faende dagegen niemand, bis Schluessel kollidieren.
     */
    private fun mssql(
        sequenceRef: SequenceObjectRef,
        probe: SequenceCurrentValueProbeResult.Read,
        sequence: SequenceDefinition?,
    ): List<String> {
        val definition = requireNotNull(sequence) {
            "MSSQL atomic-preserve restore needs the sequence definition to compute the resume point " +
                "(sequence=${sequenceRef.name}): RESTART WITH sets the NEXT value, so increment and " +
                "bounds decide where it continues."
        }
        val next = requireNotNull(MssqlSequenceResume.resumePoint(probe.value, definition)) {
            "Sequence '${sequenceRef.name}' is exhausted at ${probe.value} and does not cycle, so there is " +
                "no value to resume at; SQL Server rejects a RESTART WITH outside the sequence's bounds."
        }
        val quoted = SqlIdentifiers.quoteIdentifier(sequenceRef.name, DatabaseDialect.MSSQL)
        return listOf("ALTER SEQUENCE $quoted RESTART WITH $next;")
    }

    /**
     * `ALL_SEQUENCES.LAST_NUMBER` ist der **naechste** auszugebende Wert, nicht
     * der zuletzt ausgegebene (live gemessen, siehe [OracleSequenceResume]) —
     * anders als SQL Servers `current_value`. Er wird deshalb unveraendert
     * gesetzt; ihn zu versetzen verschenkte bei jedem Preserve einen Wert.
     */
    private fun oracle(
        sequenceRef: SequenceObjectRef,
        probe: SequenceCurrentValueProbeResult.Read,
        sequence: SequenceDefinition?,
    ): List<String> {
        val definition = requireNotNull(sequence) {
            "Oracle preserve restore needs the sequence definition to compute the resume point " +
                "(sequence=${sequenceRef.name}): LAST_NUMBER means different things with and without CACHE."
        }
        val next = requireNotNull(OracleSequenceResume.resumePoint(probe.value, definition)) {
            "Sequence '${sequenceRef.name}' cannot resume after ${probe.value}: the next value lies outside " +
                "its MINVALUE/MAXVALUE range and it does not cycle."
        }
        val quoted = SqlIdentifiers.quoteIdentifier(sequenceRef.name, DatabaseDialect.ORACLE)
        return listOf("ALTER SEQUENCE $quoted RESTART START WITH $next")
    }

    private fun mysql(
        sequenceRef: SequenceObjectRef,
        probe: SequenceCurrentValueProbeResult.Read,
    ): List<String> {
        val nameLiteral = SqlIdentifiers.quoteStringLiteral(sequenceRef.name, DatabaseDialect.MYSQL)
        val managedByList = MysqlSequenceSupportNaming.SUPPORTED_MANAGED_BY
            .joinToString(", ") { SqlIdentifiers.quoteStringLiteral(it, DatabaseDialect.MYSQL) }
        val formatVersionList = MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS
            .joinToString(", ") { SqlIdentifiers.quoteStringLiteral(it, DatabaseDialect.MYSQL) }
        val supportTable = MysqlSequenceSupportNaming.SUPPORT_TABLE
        return listOf(
            "UPDATE `$supportTable` SET `next_value` = ${probe.value} " +
                "WHERE `name` = $nameLiteral " +
                "AND `managed_by` IN ($managedByList) " +
                "AND `format_version` IN ($formatVersionList);",
        )
    }

    private fun sqlite(
        sequenceRef: SequenceObjectRef,
        probe: SequenceCurrentValueProbeResult.Read,
    ): List<String> {
        val nameLiteral = SqlIdentifiers.quoteStringLiteral(sequenceRef.name, DatabaseDialect.SQLITE)
        return listOf(
            "UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} " +
                "WHERE \"name\" = $nameLiteral;",
        )
    }
}
