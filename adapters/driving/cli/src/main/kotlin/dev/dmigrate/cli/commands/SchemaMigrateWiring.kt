package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.config.RoutineCapabilityConfigResolver
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.LogScrubber
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.format.overlay.MigrationOverlayJsonCodec
import dev.dmigrate.format.overlay.MigrationOverlayJsonDecodeException
import dev.dmigrate.text.icu.IcuUnicodeTextService
import java.nio.file.Path
import kotlin.io.path.inputStream

internal data class SchemaMigrateOptions(
    val source: String,
    val target: String,
    val dialect: DatabaseDialect?,
    val output: Path?,
    val rollbackOutput: Path?,
    val report: Path?,
    val planArtefact: Path?,
    val reportFormat: String,
    val planOnly: Boolean,
    val allowDestructive: Boolean,
    val allowExtensionInstall: Boolean,
    val migrationOverlays: List<Path>,
    val renameTableFlags: List<String>,
    val renameColumnFlags: List<String>,
    val generateRollback: Boolean,
    val execute: Boolean,
    val dryRun: Boolean,
    val debugBody: Boolean,
    val routineCapabilityFlags: List<String>,
    val strictGapOperations: Boolean,
    val sqliteNamedSequences: String?,
    val cliContext: CliContext,
    val configPath: Path?,
)

internal object SchemaMigrateWiring {

    fun execute(options: SchemaMigrateOptions): Int {
        val formatter = OutputFormatter(options.cliContext, IcuUnicodeTextService())
        val validator = SchemaValidator()
        val loadedMigrationOverlays = loadMigrationOverlays(options.migrationOverlays)
        val routineCapabilityResolver = RoutineCapabilityConfigResolver(
            cliFlagValues = options.routineCapabilityFlags,
            configPathFromCli = options.configPath,
        )
        val request = SchemaMigrateRequest(
            source = options.source,
            target = options.target,
            dialect = options.dialect,
            output = options.output,
            report = options.report,
            rollbackOutput = options.rollbackOutput,
            planArtefact = options.planArtefact,
            reportFormat = options.reportFormat,
            planOnly = options.planOnly,
            allowDestructive = options.allowDestructive,
            allowExtensionInstall = options.allowExtensionInstall,
            generateRollback = options.generateRollback,
            execute = options.execute,
            dryRun = options.dryRun,
            cliConfigPath = options.configPath,
            migrationOverlays = loadedMigrationOverlays.documents,
            migrationOverlayLoadFailures = loadedMigrationOverlays.failures,
            renameTableFlags = options.renameTableFlags,
            renameColumnFlags = options.renameColumnFlags,
            debugBody = options.debugBody,
            routineCapabilityResolver = routineCapabilityResolver::resolve,
            strictGapOperations = options.strictGapOperations,
            sqliteNamedSequences = options.sqliteNamedSequences,
        )
        val runner = SchemaMigrateRunner(
            fileLoader = { op ->
                val schema = SchemaFileResolver.codecForPath(op.path).read(op.path)
                ResolvedSchemaOperand(
                    reference = op.path.toString(),
                    schema = schema,
                    validation = validator.validate(schema),
                )
            },
            dbLoader = { op, cfgPath -> loadFromDb(op, cfgPath, validator) },
            comparator = { left, right -> SchemaComparator().compare(left, right) },
            rendererFor = MigrateRendererRegistry::forDialect,
            executor = { target, configPath, segments, lockTimeoutMillis ->
                SegmentAwareMigrationExecutor.execute(
                    target = target,
                    configPath = configPath,
                    segments = segments,
                    lockTimeoutMillis = lockTimeoutMillis,
                )
            },
            sqliteLiveCatalogProbe = SqliteLiveCatalogProbeRunner::probe,
            sqliteCastPreflightPlanner = SqliteCastPreflightProbeRunner::planNotRun,
            sqliteCastPreflightProbe = SqliteCastPreflightProbeRunner::probe,
            checkPreflightProbe = CheckPreflightProbeRunner::probe,
            mysqlSequenceCanonicityProbe = MysqlSequenceCanonicityProbeRunner::probe,
            urlScrubber = LogScrubber::maskUrl,
            renderReport = SchemaMigrateReportRenderer::render,
            printError = { msg, src -> formatter.printError(msg, src) },
        )
        return runner.execute(request)
    }

    private fun loadFromDb(
        op: CompareOperand.Database,
        cfgPath: Path?,
        validator: SchemaValidator,
    ): ResolvedSchemaOperand {
        val url = try {
            NamedConnectionResolver(configPathFromCli = cfgPath).resolve(op.source)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "Config resolution failed", e)
        }
        val config = ConnectionUrlParser.parse(url)
        val userRef = if (op.source.contains("://")) LogScrubber.maskUrl(url) else op.source
        val pool = HikariConnectionPoolFactory.create(config)
        return pool.use { p ->
            val result = DatabaseDriverRegistry.get(config.dialect).schemaReader()
                .read(p, SchemaReadOptions())
            ResolvedSchemaOperand(
                reference = userRef,
                schema = result.schema,
                validation = validator.validate(result.schema),
                notes = result.notes,
                skippedObjects = result.skippedObjects,
                dialect = config.dialect,
                mysqlServerVersion = result.mysqlServerVersion,
            )
        }
    }

    private fun loadMigrationOverlays(paths: List<Path>): LoadedMigrationOverlays {
        val codec = MigrationOverlayJsonCodec()
        val documents = mutableListOf<MigrationOverlayDocument>()
        val failures = mutableListOf<MigrationOverlayLoadFailure>()
        paths.forEach { path ->
            try {
                path.inputStream().use { input ->
                    documents += MigrationOverlayDocument(
                        source = path.toString(),
                        overlay = codec.read(input),
                    )
                }
            } catch (e: MigrationOverlayJsonDecodeException) {
                failures += MigrationOverlayLoadFailure(
                    source = path.toString(),
                    diagnosticCode = e.code,
                )
            } catch (_: Exception) {
                failures += MigrationOverlayLoadFailure(
                    source = path.toString(),
                    diagnosticCode = MigrationOverlayDiagnostics.FIELD_TYPE_MISMATCH,
                )
            }
        }
        return LoadedMigrationOverlays(documents, failures)
    }

    private data class LoadedMigrationOverlays(
        val documents: List<MigrationOverlayDocument>,
        val failures: List<MigrationOverlayLoadFailure>,
    )
}
