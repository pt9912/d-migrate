package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.seed.SeedLocale
import dev.dmigrate.core.seed.SeedPreflightException
import dev.dmigrate.core.seed.SeedUniquenessExhaustedException
import dev.dmigrate.core.seed.TableRowSeeder
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.SchemaCodec
import java.nio.file.Path
import kotlin.random.Random

/**
 * Kernlogik für `d-migrate data seed` P1
 * (ImpPlan-1.3.0-cli-data-seed-p1.md AP3). Alle externen Kollaborateure
 * sind konstruktorinjiziert (analog `DataImportRunner`), damit jeder
 * Zweig ohne echte Datenbank/CLI testbar ist.
 *
 * Exit-Codes (AE-6/AE-7, cli-spec §6-Konvention):
 * - 0 Erfolg
 * - 3 Preflight fehlgeschlagen ([SeedPreflightException]: echter FK-Zyklus,
 *   Geometry/FullText/Enum-ohne-Werte auf einer `NOT NULL`-Spalte,
 *   Zielspalte `NOT NULL` ohne Quelle, Ziel-Tabelle nicht öffenbar)
 * - 4 Verbindungsfehler
 * - 5 Schreibfehler ([SeedUniquenessExhaustedException] oder ein Fehler
 *   beim eigentlichen Schreiben/Committen/Abschließen einer Tabelle)
 * - 7 Konfigurationsfehler (unbekannte `--locale`, Schema nicht lesbar,
 *   `--target` nicht auflösbar)
 */
class DataSeedRunner(
    private val schemaCodec: SchemaCodec,
    private val targetResolver: (target: String?, configPath: Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val writerLookup: (DatabaseDialect) -> DataWriter,
    private val stdout: (String) -> Unit = ::println,
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    fun execute(request: DataSeedRequest): Int {
        val locale = SeedLocale.fromFlag(request.locale)
        if (locale == null) {
            stderr("Error: unbekannte --locale '${request.locale}' (unterstützt: en, de)")
            return EXIT_CONFIG_ERROR
        }

        val schema = readSchema(request) ?: return EXIT_CONFIG_ERROR
        val targetUrl = resolveTarget(request) ?: return EXIT_CONFIG_ERROR
        val connectionConfig = parseUrl(targetUrl) ?: return EXIT_CONFIG_ERROR

        val effectiveSeed = request.seed ?: Random.nextLong()
        stdout("Verwendeter Seed: $effectiveSeed")

        val rows = try {
            TableRowSeeder(Random(effectiveSeed), locale).seedAll(schema, request.count)
        } catch (e: SeedPreflightException) {
            stderr("Error: ${e.message}")
            return EXIT_PREFLIGHT_FAILED
        } catch (e: SeedUniquenessExhaustedException) {
            stderr("Error: ${e.message}")
            return EXIT_WRITE_FAILED
        }

        val pool = try {
            poolFactory(connectionConfig)
        } catch (e: Throwable) {
            stderr("Error: Failed to connect to database: ${e.message}")
            return EXIT_CONNECTION_ERROR
        }

        return try {
            writeAll(connectionConfig, pool, schema, rows)
        } finally {
            runCatching { pool.close() }
        }
    }

    private fun readSchema(request: DataSeedRequest): SchemaDefinition? = try {
        schemaCodec.read(request.schema)
    } catch (e: Exception) {
        stderr("Error: Schema konnte nicht gelesen werden: ${e.message}")
        null
    }

    private fun resolveTarget(request: DataSeedRequest): String? = try {
        targetResolver(request.target, request.cliConfigPath)
    } catch (e: Exception) {
        stderr("Error: ${e.message}")
        null
    }

    private fun parseUrl(targetUrl: String): ConnectionConfig? = try {
        urlParser(targetUrl)
    } catch (e: Exception) {
        stderr("Error: ${e.message}")
        null
    }

    private fun writeAll(
        connectionConfig: ConnectionConfig,
        pool: ConnectionPool,
        schema: SchemaDefinition,
        rows: Map<String, List<Map<String, Any?>>>,
    ): Int {
        val writer = writerLookup(connectionConfig.dialect)
        var totalRows = 0L
        for ((tableName, tableRows) in rows) {
            val exitCode = writeTable(writer, pool, schema, tableName, tableRows)
            if (exitCode != 0) return exitCode
            totalRows += tableRows.size
        }
        stdout("$totalRows Zeile(n) in ${rows.size} Tabelle(n) erzeugt.")
        return EXIT_SUCCESS
    }

    private fun writeTable(
        writer: DataWriter,
        pool: ConnectionPool,
        schema: SchemaDefinition,
        tableName: String,
        tableRows: List<Map<String, Any?>>,
    ): Int {
        val session = try {
            writer.openTable(pool, tableName, ImportOptions())
        } catch (e: Exception) {
            stderr("Error: Tabelle '$tableName' kann nicht geöffnet werden: ${e.message}")
            return EXIT_PREFLIGHT_FAILED
        }
        return session.use {
            val missing = missingRequiredTargetColumn(schema, tableName, session.targetColumns)
            if (missing != null) {
                stderr("Error: $missing")
                return@use EXIT_PREFLIGHT_FAILED
            }
            try {
                writeRows(session, tableName, tableRows)
                session.finishTable()
                EXIT_SUCCESS
            } catch (e: Exception) {
                stderr("Error: Schreiben in '$tableName' fehlgeschlagen: ${e.message}")
                EXIT_WRITE_FAILED
            }
        }
    }

    private fun writeRows(
        session: TableImportSession,
        tableName: String,
        tableRows: List<Map<String, Any?>>,
    ) {
        if (tableRows.isEmpty()) return
        val columns = session.targetColumns.map { ColumnDescriptor(it.name, it.nullable) }
        val values = tableRows.map { row -> session.targetColumns.map { column -> row[column.name] }.toTypedArray() }
        session.write(DataChunk(tableName, columns, values, chunkIndex = 0))
        session.commitChunk()
    }

    /** AE-3: Zielspalte, die im Quellschema fehlt und `NOT NULL` ohne Default ist, ist ein Preflight-Fehler. */
    private fun missingRequiredTargetColumn(
        schema: SchemaDefinition,
        tableName: String,
        targetColumns: List<TargetColumn>,
    ): String? {
        val sourceColumns = schema.tables[tableName]?.columns ?: emptyMap()
        val missingColumn = targetColumns.firstOrNull { it.name !in sourceColumns && !it.nullable }
        return missingColumn?.let {
            "Zielspalte '$tableName.${it.name}' ist NOT NULL, fehlt aber im Quellschema."
        }
    }

    companion object {
        private const val EXIT_SUCCESS = 0
        private const val EXIT_PREFLIGHT_FAILED = 3
        private const val EXIT_CONNECTION_ERROR = 4
        private const val EXIT_WRITE_FAILED = 5
        private const val EXIT_CONFIG_ERROR = 7
    }
}
