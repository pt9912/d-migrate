package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.DialectReadCapabilityProvider
import dev.dmigrate.driver.EffectiveRoutineCapability
import dev.dmigrate.driver.MeasuredServerVersions
import dev.dmigrate.driver.PostgresServerVersion
import dev.dmigrate.driver.PreserveWindowIsolation
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.RoutineKindCapability
import dev.dmigrate.driver.SequenceCapability
import dev.dmigrate.driver.ServerVersion
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.TriggerCapability

/**
 * Was PostgreSQL kann — die Antwort liegt beim Dialekt, nicht in einer
 * geteilten Tabelle.
 *
 * Warum eine Faehigkeit steht, wie sie steht, begruendet die KDoc des
 * jeweiligen Feldes in [DialectCapabilities]: dort stehen alle fuenf
 * Antworten nebeneinander, und die Begruendungen sind vergleichend. Hier
 * stehen nur die Werte.
 */
object PostgresCapabilities : DialectReadCapabilityProvider {

    /** `VIRTUAL` gibt es ab dieser Hauptversion; darunter ist es ein Syntaxfehler. */
    const val VIRTUAL_COMPUTED_SINCE_MAJOR: Int = 18

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun capabilities(serverVersion: ServerVersion?): DialectCapabilities {
        val version = serverVersion as? PostgresServerVersion
        return DialectCapabilities(
            supportsComputedExpressionInPlace = version?.supportsSetExpression ?: false,
            supportsVirtualComputedColumns =
                (version?.major ?: MeasuredServerVersions.POSTGRESQL.major) >= VIRTUAL_COMPUTED_SINCE_MAJOR,
            supportsRawTextSandbox = true,
            supportsViews = true,
            supportsFunctions = true,
            supportsProcedures = true,
            supportsTriggers = true,
            supportsSequences = true,
            supportsCustomTypes = true,
            supportsPartitioning = true,
            supportsDisableFkChecks = false,
            supportsTriggerDisable = true,
            supportsTriggerStrict = true,
            supportsSchemaParameter = true,
            partitionChildrenAreTables = true,
            supportsIndexIncludeColumns = true,
            // Der Reverse liest den system-vergebenen Sequenznamen einer
            // IDENTITY-Spalte schema-qualifiziert zurueck; gerendert wird
            // er von keinem Dialekt, ein Soll-Schema kann ihn also nicht
            // tragen.
            namesIdentitySequences = false,
            // `SERIAL` und `GENERATED ... AS IDENTITY` sind in PostgreSQL
            // zwei verschiedene Dinge, nicht zwei Schreibweisen desselben.
            rendersAutoIncrementAsIdentity = false,
        )
    }

        // Atomic-Preserve Phase A (2026-05-31): every dialect declares the
        // three new capability fields explicitly even though the defaults
        // all evaluate to `false` / `emptySet()` today. Forcing the
        // declaration makes the per-dialect matrix legible at a glance and
        // pins the contract that Phases B/C will flip; an implicit
        // data-class-default would let a future dialect-renderer commit
        // appear to support the atomic path without anyone updating the
        // capability table.
        private val SEQUENCE = SequenceCapability(
            supportsNamedSequences = true,
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true,
            emitsCachePreallocationWarning = false,
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = true,
            // Atomic-Preserve Phase C.4: PG's lock strategy is
            // `pg_advisory_xact_lock(hashtext(...))` plus `SET LOCAL
            // lock_timeout` (§4.1 Korrektur). The
            // `PostgresAtomicSequencePreserveExecutor` is wired into the
            // SchemaMigrate pipeline via `AtomicSequencePreserveRunner`
            // and `AtomicSequencePreserveDispatcher`; capability flip is
            // safe even before C.1 because Stage does not yet read the
            // flag (master-grün invariant). Phase D (2026-06-01) flips
            // `preserveAllCandidatesInOneWindow` to `true` after the
            // [PostgresAtomicPreserveCrossPlanDeadlockTest] proves that
            // the name-sorted advisory-lock acquisition closes the
            // diamond between parallel runs. The protected-operation
            // allowlist mirrors today's Stage candidates (CreateSequence
            // / AlterSequence / RenameSequence); PG executes all three
            // without implicit commit, so all three are inside the
            // atomic window.
            preserveWindowIsolation = PreserveWindowIsolation.ATOMIC,
            preserveAllCandidatesInOneWindow = true,
            protectedSequenceOperations = setOf(
                ProtectedOperationId("CreateSequence"),
                ProtectedOperationId("AlterSequence"),
                ProtectedOperationId("RenameSequence"),
            ),
        )
    override fun sequenceCapability(): SequenceCapability = SEQUENCE
        private val TRIGGER = TriggerCapability(enabled = true, minPostgresMajorVersion = 14)
    override fun triggerCapability(): TriggerCapability = TRIGGER
        private val ROUTINES = EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true, minServerVersion = null),
            procedure = RoutineKindCapability(enabled = true, minServerVersion = null),
        )

    override fun routineCapability(serverVersion: ServerVersion?): EffectiveRoutineCapability.Valid = ROUTINES

    override fun defaultSpatialProfile(): SpatialProfile = SpatialProfile.POSTGIS

    override fun allowedSpatialProfiles(): Set<SpatialProfile> =
        setOf(SpatialProfile.POSTGIS, SpatialProfile.NONE)
}
