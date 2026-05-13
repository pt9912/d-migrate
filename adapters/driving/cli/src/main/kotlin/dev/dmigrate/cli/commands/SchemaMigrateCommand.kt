package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.config.NamedConnectionResolver
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
import dev.dmigrate.format.overlay.MigrationOverlayJsonDecodeException
import dev.dmigrate.format.overlay.MigrationOverlayJsonCodec
import dev.dmigrate.text.icu.IcuUnicodeTextService
import kotlin.io.path.inputStream

class SchemaMigrateCommand : CliktCommand(name = "migrate") {
    override fun help(context: Context) =
        "Plan and optionally execute a migration from current to desired schema"

    val source by option("--source", help = "Soll-Schema (file)").required()
    val target by option("--target", help = "Ist-state operand: file:<current.yaml> or db:<url-or-alias>")
        .required()
    val dialectFlag by option("--dialect", help = "Target dialect (required for file targets)")
        .choice(
            "postgresql" to DatabaseDialect.POSTGRESQL,
            "mysql" to DatabaseDialect.MYSQL,
            "sqlite" to DatabaseDialect.SQLITE,
        )
    val output by option("--output", help = "Up-SQL output file").path()
    val rollbackOutput by option("--rollback-output", help = "Down-SQL output file").path()
    val report by option("--report", help = "Report output file (required with --execute)").path()
    val reportFormat by option("--report-format", help = "Report format")
        .choice("json", "yaml").default("json")
    val planOnly by option("--plan-only", help = "Only render the plan / report; no SQL output").flag()
    val allowDestructive by option("--allow-destructive", help = "Permit destructive Up operations").flag()
    val allowExtensionInstall by option(
        "--allow-extension-install",
        help = "Permit PostgreSQL CREATE EXTENSION prerequisites for extension-dependent migrations",
    ).flag()
    val migrationOverlays by option(
        "--migration-overlay",
        help = "Versioned migration overlay JSON file (repeatable)",
    ).path(mustExist = true, canBeDir = false, mustBeReadable = true).multiple()
    val generateRollback by option("--generate-rollback", help = "Render Down-SQL alongside Up").flag()
    val execute by option("--execute", help = "Execute Up-SQL against --target (DB only)").flag()
    val dryRun by option("--dry-run", help = "Plan/SQL only, do not execute").flag()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        val formatter = OutputFormatter(ctx, IcuUnicodeTextService())
        val validator = SchemaValidator()
        val loadedMigrationOverlays = loadMigrationOverlays(migrationOverlays)

        val request = SchemaMigrateRequest(
            source = source,
            target = target,
            dialect = dialectFlag,
            output = output,
            report = report,
            rollbackOutput = rollbackOutput,
            reportFormat = reportFormat,
            planOnly = planOnly,
            allowDestructive = allowDestructive,
            allowExtensionInstall = allowExtensionInstall,
            generateRollback = generateRollback,
            execute = execute,
            dryRun = dryRun,
            cliConfigPath = root?.config,
            migrationOverlays = loadedMigrationOverlays.documents,
            migrationOverlayLoadFailures = loadedMigrationOverlays.failures,
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
            executor = JdbcMigrationExecutor::execute,
            sqliteLiveCatalogProbe = SqliteLiveCatalogProbeRunner::probe,
            sqliteCastPreflightProbe = SqliteCastPreflightProbeRunner::probe,
            urlScrubber = LogScrubber::maskUrl,
            renderReport = SchemaMigrateReportRenderer::render,
            printError = { msg, src -> formatter.printError(msg, src) },
        )
        val exitCode = runner.execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }

    private fun loadFromDb(
        op: CompareOperand.Database,
        cfgPath: java.nio.file.Path?,
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
            )
        }
    }

    private fun loadMigrationOverlays(paths: List<java.nio.file.Path>): LoadedMigrationOverlays {
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
