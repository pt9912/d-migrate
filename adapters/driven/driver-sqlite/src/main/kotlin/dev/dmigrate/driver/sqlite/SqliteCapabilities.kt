package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.DialectReadCapabilityProvider
import dev.dmigrate.driver.EffectiveRoutineCapability
import dev.dmigrate.driver.PreserveWindowIsolation
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.RoutineKindCapability
import dev.dmigrate.driver.SequenceCapability
import dev.dmigrate.driver.ServerVersion
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.TriggerCapability

/**
 * Was SQLite kann. Begruendung je Feld: die KDoc in [DialectCapabilities].
 */
object SqliteCapabilities : DialectReadCapabilityProvider {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    override fun capabilities(serverVersion: ServerVersion?): DialectCapabilities = DialectCapabilities(
        supportsComputedExpressionInPlace = true,
        supportsViews = true,
        supportsFunctions = false,
        supportsProcedures = false,
        supportsTriggers = true,
        supportsSequences = false,
        supportsCustomTypes = false,
        supportsPartitioning = false,
        namesSingleColumnConstraints = false,
        supportsDisableFkChecks = true,
        supportsTriggerDisable = false,
        supportsTriggerStrict = false,
        supportsSchemaParameter = false,
        carriesFullTextConfiguration = false,
    )

        private val SEQUENCE = SequenceCapability(
            supportsNamedSequences = true,
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true,
            emitsCachePreallocationWarning = true,
            // 0.9.7 SQLite preserve-current-value E.3-Folge-Slice: the renderer
            // (`SqliteDiffSequenceOps.renderAlterSequenceCurrentValue`),
            // the probe (`SqliteSequenceCurrentValueProbe`), and the
            // SequencePreserveStage allowlist now form a complete loop.
            // Activation is gated by `--sqlite-named-sequences helper_table`;
            // the stage emits SEQUENCE_PRESERVE_OPT_IN_REQUIRED when the
            // operator hasn't opted in. The capability flag describes what
            // the dialect's renderer can express, not which CLI mode is
            // currently selected.
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = false,
            // Atomic-Preserve Phase C.4: SQLite's `BEGIN IMMEDIATE` plus
            // `PRAGMA busy_timeout` strategy (§4.3). RESERVED-lock
            // semantics block every concurrent writer for the entire
            // transaction window, so the allowlist intentionally stays
            // tight: only the three sequence-bearing kinds whose SQL is
            // `INSERT`/`UPDATE` against the `dmg_sequences` helper table.
            // Anything broader (table rebuilds, ALTER TABLE … RENAME)
            // would extend the RESERVED window and is excluded by
            // omission, surfacing as `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`
            // when Stage classifies a candidate of that kind.
            // Phase D (2026-06-01) flips `preserveAllCandidatesInOneWindow`
            // to `true`; for SQLite the cross-plan deadlock-diamond is
            // impossible by construction (the RESERVED lock is database-
            // wide, not per-row), so parallel runs serialise rather than
            // deadlock — see [SqliteAtomicPreserveCrossPlanDeadlockTest].
            preserveWindowIsolation = PreserveWindowIsolation.ATOMIC,
            preserveAllCandidatesInOneWindow = true,
            protectedSequenceOperations = setOf(
                ProtectedOperationId("CreateSequence"),
                ProtectedOperationId("AlterSequence"),
                ProtectedOperationId("RenameSequence"),
            ),
        )
    override fun sequenceCapability(): SequenceCapability = SEQUENCE
        private val TRIGGER = TriggerCapability(enabled = false)
    override fun triggerCapability(): TriggerCapability = TRIGGER
        private val ROUTINES = EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = false, minServerVersion = null),
            procedure = RoutineKindCapability(enabled = false, minServerVersion = null),
        )

    override fun routineCapability(serverVersion: ServerVersion?): EffectiveRoutineCapability.Valid = ROUTINES

    override fun defaultSpatialProfile(): SpatialProfile = SpatialProfile.NONE

    override fun allowedSpatialProfiles(): Set<SpatialProfile> =
        setOf(SpatialProfile.SPATIALITE, SpatialProfile.NONE)
}
