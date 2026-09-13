package dev.dmigrate.driver.mssql

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
 * Was SQL Server kann (2017+, ADR 0047). Begruendung je Feld: die KDoc in
 * [DialectCapabilities].
 *
 * Die Objekttyp-Flags sind Faehigkeiten des Servers; die Import-Modus-Flags
 * (FK-/Trigger-Disable) beschreiben dagegen den Werkzeug-Pfad, den d-migrate
 * fuer MSSQL nicht faehrt.
 */
object MssqlCapabilities : DialectReadCapabilityProvider {

    /**
     * SET-Optionen, die SQL Server fuer gefilterte Indizes verlangt (und die
     * `sqlcmd` nicht per Default setzt). Eigener Batch, damit sie fuer alle
     * folgenden Batches der Sitzung gelten.
     */
    private val SCRIPT_PREAMBLE = listOf(
        "SET ANSI_NULLS ON;",
        "SET ANSI_PADDING ON;",
        "SET ANSI_WARNINGS ON;",
        "SET ARITHABORT ON;",
        "SET CONCAT_NULL_YIELDS_NULL ON;",
        "SET NUMERIC_ROUNDABORT OFF;",
        "SET QUOTED_IDENTIFIER ON;",
    ).joinToString("\n")

    override val dialect: DatabaseDialect = DatabaseDialect.MSSQL

    override fun capabilities(serverVersion: ServerVersion?): DialectCapabilities = DialectCapabilities(
        supportsViews = true,
        supportsFunctions = true,
        supportsProcedures = true,
        supportsTriggers = true,
        supportsSequences = true,
        supportsCustomTypes = false,
        supportsPartitioning = true,
        supportsDisableFkChecks = false,
        supportsTriggerDisable = false,
        supportsTriggerStrict = false,
        supportsSchemaParameter = true,
        partitionChildrenAreTables = false,
        batchSeparator = "GO",
        scriptPreamble = SCRIPT_PREAMBLE,
        requiresPrimaryKeyForSkip = true,
        supportsIndexIncludeColumns = true,
        supportsClusteredIndexes = true,
        namesFullTextIndexes = false,
        carriesFullTextConfiguration = false,
        namesPartitions = false,
        supportsListPartitioning = false,
    )

        // Die Defaults spiegeln Renderer-Realitaet (KDoc oben): der MSSQL-
        // DDL-Generator rendert native Sequenzen (CREATE SEQUENCE … AS BIGINT
        // mit START/INCREMENT/MIN/MAX/CYCLE/CACHE, spec/ddl-generation-rules.md).
        //
        // `supportsCurrentValuePreserve` steht seit dem MSSQL-Sub-Slice 5d auf
        // `true`: `MssqlDiffSequenceOps.renderAlterSequenceCurrentValue` rendert
        // `ALTER SEQUENCE … RESTART WITH`, und `MssqlSequenceCurrentValueProbe`
        // liest den Laufzeitwert aus `sys.sequences`. Wie bei den anderen
        // Dialekten beschreibt die Faehigkeit, was der Renderer AUSDRUECKEN kann,
        // nicht welchen Pfad die Pipeline gerade nimmt — die Verdrahtung im
        // `SequencePreserveStage` folgt mit Sub-Slice 5e, zusammen mit dem
        // Gate-Fall und dem `RenameProjectionDialect`-Eintrag.
        //
        // Atomic-Preserve (2026-09-09): `sys.sp_getapplock` mit
        // `@LockOwner = 'Transaction'` ist das T-SQL-Gegenstueck zu PGs
        // `pg_advisory_xact_lock` — transaktionsgebunden, faellt mit Commit oder
        // Rollback von selbst weg. SQL Server fuehrt DDL transaktional, deshalb
        // liegen alle drei geschuetzten Operationen im atomaren Fenster.
        private val SEQUENCE = SequenceCapability(
            supportsNamedSequences = true,
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true,
            emitsCachePreallocationWarning = false,
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = false,
            preserveWindowIsolation = PreserveWindowIsolation.ATOMIC,
            preserveAllCandidatesInOneWindow = true,
            protectedSequenceOperations = setOf(
                ProtectedOperationId("CreateSequence"),
                ProtectedOperationId("AlterSequence"),
                ProtectedOperationId("RenameSequence"),
            ),
        )
    override fun sequenceCapability(): SequenceCapability = SEQUENCE
        // Drop+Create ist auch fuer T-SQL immer gueltig; ob der Renderer
        // `CREATE OR ALTER TRIGGER` (2016 SP1+) nutzt, entscheidet der
        // Trigger-Slice (docs/planning/in-progress/mssql-dialect-scoping.md, Slice 9).
        private val TRIGGER = TriggerCapability(enabled = false)
    override fun triggerCapability(): TriggerCapability = TRIGGER
        // Konservativ wie Oracle MySQL: Drop+Create-Fallback ist fuer T-SQL immer
        // gueltig; ob der Renderer `CREATE OR ALTER` (2016 SP1+) nutzt, entscheidet
        // der Routinen-Slice (docs/planning/in-progress/mssql-dialect-scoping.md,
        // Slice 9).
        private val ROUTINES = EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = false, minServerVersion = null),
            procedure = RoutineKindCapability(enabled = false, minServerVersion = null),
        )

    override fun routineCapability(serverVersion: ServerVersion?): EffectiveRoutineCapability.Valid = ROUTINES

    override fun defaultSpatialProfile(): SpatialProfile = SpatialProfile.NATIVE

    override fun allowedSpatialProfiles(): Set<SpatialProfile> =
        setOf(SpatialProfile.NATIVE, SpatialProfile.NONE)
}
