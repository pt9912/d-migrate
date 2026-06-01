package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlSequenceSupportNaming
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.SqlIdentifiers

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
    ): List<String> = when (dialect) {
        DatabaseDialect.POSTGRESQL -> postgres(sequenceRef, probe)
        DatabaseDialect.MYSQL -> mysql(sequenceRef, probe)
        DatabaseDialect.SQLITE -> sqlite(sequenceRef, probe)
    }

    private fun postgres(
        sequenceRef: SequenceObjectRef,
        probe: SequenceCurrentValueProbeResult.Read,
    ): List<String> {
        val literal = SqlIdentifiers.quoteStringLiteral(sequenceRef.name)
        val isCalled = requireNotNull(probe.isCalled) {
            "PG atomic-preserve restore requires isCalled on probe " +
                "(sequence=${sequenceRef.name})"
        }
        return listOf("SELECT setval($literal, ${probe.value}, $isCalled);")
    }

    private fun mysql(
        sequenceRef: SequenceObjectRef,
        probe: SequenceCurrentValueProbeResult.Read,
    ): List<String> {
        val nameLiteral = SqlIdentifiers.quoteStringLiteral(sequenceRef.name)
        val managedByList = MysqlSequenceSupportNaming.SUPPORTED_MANAGED_BY
            .joinToString(", ") { SqlIdentifiers.quoteStringLiteral(it) }
        val formatVersionList = MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS
            .joinToString(", ") { SqlIdentifiers.quoteStringLiteral(it) }
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
        val nameLiteral = SqlIdentifiers.quoteStringLiteral(sequenceRef.name)
        return listOf(
            "UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} " +
                "WHERE \"name\" = $nameLiteral;",
        )
    }
}
