package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.DialectCapabilityProvider
import dev.dmigrate.driver.ServerVersion

/**
 * Was SQL Server kann (2017+, ADR 0047). Begruendung je Feld: die KDoc in
 * [DialectCapabilities].
 *
 * Die Objekttyp-Flags sind Faehigkeiten des Servers; die Import-Modus-Flags
 * (FK-/Trigger-Disable) beschreiben dagegen den Werkzeug-Pfad, den d-migrate
 * fuer MSSQL nicht faehrt.
 */
object MssqlCapabilities : DialectCapabilityProvider {

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
}
