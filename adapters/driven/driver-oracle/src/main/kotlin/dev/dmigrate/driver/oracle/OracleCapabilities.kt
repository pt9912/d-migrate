package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.DialectReadCapabilityProvider
import dev.dmigrate.driver.EffectiveRoutineCapability
import dev.dmigrate.driver.OracleServerVersion
import dev.dmigrate.driver.PreserveWindowIsolation
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.RoutineKindCapability
import dev.dmigrate.driver.SequenceCapability
import dev.dmigrate.driver.ServerVersion
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.TriggerCapability

/**
 * Was Oracle kann (Inventar nach ADR 0052). Begruendung je Feld: die KDoc in
 * [DialectCapabilities].
 *
 * Zwei Entscheidungen, die nur hier stehen koennen:
 *
 * - `supportsCustomTypes` bleibt bewusst `false`: Oracle-Objekttypen
 *   (`CREATE TYPE`) bildet d-migrate nicht ab.
 * - `batchSeparator` bleibt `null`. `/` ist zwar die SQL*Plus/SQLcl-
 *   Konvention — aber es bedeutet etwas anderes als T-SQLs `GO`: `GO`
 *   beendet einen Batch, `/` fuehrt den Puffer **erneut** aus. Hinter einer
 *   mit `;` abgeschlossenen Anweisung laeuft sie damit zweimal; jedes
 *   `CREATE SEQUENCE` meldete beim zweiten Durchlauf `ORA-00955`, bei einem
 *   Datenskript waere es ein doppelter INSERT gewesen. `/` gehoert nur zu
 *   PL/SQL-Bloecken und dort **anstelle** des `;` — also an die einzelne
 *   Anweisung (`DdlStatement.scriptTerminator`), nicht an den Dialekt.
 */
object OracleCapabilities : DialectReadCapabilityProvider {

    override val dialect: DatabaseDialect = DatabaseDialect.ORACLE

    override fun capabilities(serverVersion: ServerVersion?): DialectCapabilities {
        val version = serverVersion as? OracleServerVersion
        return DialectCapabilities(
            supportsDropIfExists = version?.supportsDropIfExists ?: false,
            rendersViewRefreshSetting = true,
            supportsViews = true,
            supportsFunctions = true,
            supportsProcedures = true,
            supportsTriggers = true,
            supportsSequences = true,
            supportsCustomTypes = false,
            supportsPartitioning = true,
            supportsDisableFkChecks = true,
            supportsTriggerDisable = false,
            supportsTriggerStrict = false,
            supportsSchemaParameter = true,
            // Oracle-Partitionen brauchen wie bei MySQL die
            // `PARTITION (name)`-Klausel, sind keine eigenstaendig
            // adressierbaren Relationen.
            partitionChildrenAreTables = false,
            requiresPrimaryKeyForSkip = true,
            supportsIndexIncludeColumns = false,
            supportsClusteredIndexes = false,
            // Oracle-Text-Indizes (CONTEXT/CTXCAT) tragen anders als MSSQL
            // einen Namen.
            namesFullTextIndexes = true,
            namesIdentitySequences = false,
            supportsBitmapIndexes = true,
            carriesFullTextConfiguration = false,
            carriesPartialIndexPredicate = false,
            carriesPartitionLowerBounds = false,
            carriesPartitionHashModulus = false,
            separatesDateFromDateTime = false,
            batchSeparator = null,
        )
    }

        // Oracle-Sequenzen sind wie PG nativ (echte Laufzeit-Preallokation bei
        // CACHE, kein Metadata-Only-Emulation-Pfad wie MySQL/SQLite) -- deshalb
        // emitsCachePreallocationWarning=false. supportsOwnedBy=false: Oracle
        // kennt keine `SEQUENCE OWNED BY column`-Verknuepfung wie PG.
        // supportsCurrentValuePreserve steht auf true, seit Sub-Slice 5d den
        // Renderer gebaut hat: `OracleDiffSequenceOps
        // .renderAlterSequenceCurrentValue` drueckt den Preserve-Pfad als
        // `ALTER SEQUENCE ... RESTART START WITH n` aus, und die
        // preserve_current_value-Zeile in spec/neutral-model-spec.md
        // Abschnitt 9 fuehrt Oracle mit genau dieser Form.
        //
        // Der Ausfuehrungspfad ist gebaut und live abgenommen
        // (`OracleSequencePreserveIntegrationTest`): Probe, geschuetzte
        // Anweisungen und Restore laufen in EINEM Fenster unter `DBMS_LOCK`,
        // und der vorgefundene Stand ueberlebt.
        //
        // Was er NICHT ist, steht unten an `preserveWindowIsolation`: serialisiert
        // statt atomar. Dieser Absatz behauptete frueher das Gegenteil dessen, was
        // drei Zeilen tiefer konfiguriert ist — "der atomare Ausfuehrungspfad
        // fehlt weiterhin, deshalb bleiben die Atomic-Faehigkeiten false und
        // protectedSequenceOperations leer" —, und wer ihn las, hielt Oracle fuer
        // gesperrt.
        private val SEQUENCE = SequenceCapability(
            supportsNamedSequences = true,
            supportsStart = true,
            supportsMinMaxValue = true,
            supportsCycle = true,
            supportsCache = true,
            emitsCachePreallocationWarning = false,
            supportsCurrentValuePreserve = true,
            supportsOwnedBy = false,
            // Serialisiert, nicht atomar: Oracle committet jedes DDL implizit,
            // und sowohl die geschuetzten Operationen als auch der Restore sind
            // DDL. Die Sperre (`DBMS_LOCK`, session-gebunden) haelt trotzdem
            // durch — ein Fehlschlag laesst aber stehen, was bis dahin lief.
            preserveWindowIsolation = PreserveWindowIsolation.SERIALIZED,
            preserveAllCandidatesInOneWindow = true,
            protectedSequenceOperations = setOf(
                ProtectedOperationId("CreateSequence"),
                ProtectedOperationId("AlterSequence"),
                ProtectedOperationId("RenameSequence"),
            ),
        )
    override fun sequenceCapability(): SequenceCapability = SEQUENCE
        // Oracle unterstuetzt CREATE OR REPLACE TRIGGER nativ und unversioniert
        // (anders als PG erst ab 14, und anders als MySQL/SQLite gar nicht).
        private val TRIGGER = TriggerCapability(enabled = true)
    override fun triggerCapability(): TriggerCapability = TRIGGER
        // Oracle unterstuetzt CREATE OR REPLACE FUNCTION/PROCEDURE nativ (anders
        // als MySQL/MSSQL) -- kein Drop+Create-Fallback noetig.
        private val ROUTINES = EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true, minServerVersion = null),
            procedure = RoutineKindCapability(enabled = true, minServerVersion = null),
        )

    override fun routineCapability(serverVersion: ServerVersion?): EffectiveRoutineCapability.Valid = ROUTINES

    override fun defaultSpatialProfile(): SpatialProfile = SpatialProfile.NATIVE

    override fun allowedSpatialProfiles(): Set<SpatialProfile> =
        setOf(SpatialProfile.NATIVE, SpatialProfile.NONE)
}
