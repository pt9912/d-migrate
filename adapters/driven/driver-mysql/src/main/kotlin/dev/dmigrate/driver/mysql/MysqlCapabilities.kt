package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.DialectReadCapabilityProvider
import dev.dmigrate.driver.EffectiveRoutineCapability
import dev.dmigrate.driver.MysqlServerVersion
import dev.dmigrate.driver.PreserveWindowIsolation
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.RoutineKindCapability
import dev.dmigrate.driver.SequenceCapability
import dev.dmigrate.driver.ServerVersion
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.TriggerCapability

/**
 * Was MySQL kann. Begruendung je Feld: die KDoc in [DialectCapabilities].
 */
object MysqlCapabilities : DialectReadCapabilityProvider {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    override fun capabilities(serverVersion: ServerVersion?): DialectCapabilities = DialectCapabilities(
        supportsComputedExpressionInPlace = true,
        supportsViews = true,
        supportsFunctions = true,
        supportsProcedures = true,
        supportsTriggers = true,
        supportsSequences = false,
        supportsCustomTypes = false,
        supportsPartitioning = true,
        supportsDisableFkChecks = true,
        supportsTriggerDisable = false,
        supportsTriggerStrict = false,
        supportsSchemaParameter = true,
        carriesFullTextConfiguration = false,
    )

        private val SEQUENCE = SequenceCapability(
            supportsNamedSequences = true,
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true,
            emitsCachePreallocationWarning = true,
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = false,
            // Atomic-Preserve Phase C.4: MySQL's lock strategy
            // (`SELECT … FOR UPDATE` on `dmg_sequences` +
            // `SET SESSION innodb_lock_wait_timeout`, §4.2). Several DDL
            // statements (`ALTER TABLE`, `CREATE INDEX`) issue implicit
            // commits on MySQL and therefore cannot live inside the
            // atomic transaction. The allowlist below covers only the
            // sequence-bearing DiffOperation kinds whose rendered SQL is
            // `INSERT`/`UPDATE` against the `dmg_sequences` helper table
            // — those run cleanly inside `START TRANSACTION` without
            // implicit commit. Phase D (2026-06-01) flips
            // `preserveAllCandidatesInOneWindow` to `true` after the
            // [MysqlAtomicPreserveCrossPlanDeadlockTest] verifies that
            // name-sorted `FOR UPDATE` acquisitions serialise without
            // ER_LOCK_DEADLOCK.
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
        private val ORACLE_MYSQL_ROUTINES = EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = false, minServerVersion = null),
            procedure = RoutineKindCapability(enabled = false, minServerVersion = null),
        )
        private val MARIADB_ROUTINES = EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true, minServerVersion = null),
            procedure = RoutineKindCapability(enabled = true, minServerVersion = null),
        )

    /**
     * `MYSQL` steht fuer zwei Server: Oracle MySQL kennt
     * `CREATE OR REPLACE FUNCTION`/`PROCEDURE` nicht, MariaDB schon. Ohne
     * gelesene Version gilt die konservative Lesart — der Renderer faellt
     * dann auf sein abgesichertes `DROP` + `CREATE` zurueck.
     */
    override fun routineCapability(serverVersion: ServerVersion?): EffectiveRoutineCapability.Valid =
        if ((serverVersion as? MysqlServerVersion)?.isMariaDb == true) MARIADB_ROUTINES else ORACLE_MYSQL_ROUTINES

    override fun defaultSpatialProfile(): SpatialProfile = SpatialProfile.NATIVE

    override fun allowedSpatialProfiles(): Set<SpatialProfile> =
        setOf(SpatialProfile.NATIVE, SpatialProfile.NONE)
}
