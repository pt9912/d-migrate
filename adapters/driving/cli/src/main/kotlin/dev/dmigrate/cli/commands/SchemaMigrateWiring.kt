package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.cliAuditRecorder
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.config.RoutineCapabilityConfigResolver
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.LogScrubber
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.format.overlay.MigrationOverlayJsonCodec
import dev.dmigrate.format.overlay.MigrationOverlayJsonDecodeException
import dev.dmigrate.text.icu.IcuUnicodeTextService
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.inputStream

internal data class SchemaMigrateOptions(
    val source: String,
    val target: String,
    val dialect: DatabaseDialect?,
    val spatialProfile: String?,
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
    val mssqlHashPartitions: String? = null,
    val lockTimeoutMs: Long?,
    val cliContext: CliContext,
    val configPath: Path?,
)

internal object SchemaMigrateWiring {

    fun execute(
        options: SchemaMigrateOptions,
        recorder: CliAuditRecorder = cliAuditRecorder(options.configPath),
    ): Int = recorder.record("schema.migrate", listOf(options.source, options.target)) {
        executeInternal(options)
    }

    /**
     * Service-Mode Sub-Slice E follow-up (2026-06-02): execute with
     * injectable shutdown-hook seam. Production calls the parameter-
     * less [execute] which delegates here; tests substitute
     * [registerShutdownHook] / [unregisterShutdownHook] / [stderr]
     * so they can drive the SIGINT path without poisoning the JVM-
     * wide hook list.
     *
     * Lifecycle:
     * 1. Create a [CancellationTokenSource] per call.
     * 2. Register a shutdown hook that flips the token on SIGINT
     *    (Ctrl-C) or SIGTERM. The hook emits an operator-visible
     *    stderr breadcrumb so the user knows the runner is unwinding.
     * 3. Pass the token to `SchemaMigrateRunner.execute(request, token)`;
     *    the runner already observes it at its own checkpoints, and
     *    `AtomicSequencePreserveExecutor` rolls back the open
     *    transaction at its three cancel checkpoints (Sub-Slice E,
     *    commit `7e6f39ae`).
     * 4. In `finally`, unregister the hook if it did not fire — keeps
     *    a long-lived JVM (tests, embedded scenarios) from
     *    accumulating stale hooks. Fired hooks self-cleanup on JVM
     *    exit; trying to remove them throws `IllegalStateException`
     *    which we swallow.
     */
    internal fun executeInternal(
        options: SchemaMigrateOptions,
        registerShutdownHook: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) },
        unregisterShutdownHook: (Thread) -> Unit = ::defaultUnregisterShutdownHook,
        stderr: (String) -> Unit = { System.err.println(it) },
    ): Int {
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
            spatialProfile = options.spatialProfile,
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
            mssqlHashPartitions = options.mssqlHashPartitions,
            lockTimeoutMillis = options.lockTimeoutMs,
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
            targetAwareComparator = { left, right, canonicalizeType ->
                SchemaComparator(canonicalizeType).compare(left, right)
            },
            rendererFor = MigrateRendererRegistry::forDialect,
            executor = SegmentAwareMigrationExecutor::executeWithDefaults,
            sqliteLiveCatalogProbe = SqliteLiveCatalogProbeRunner::probe,
            sqliteCastPreflightPlanner = SqliteCastPreflightProbeRunner::planNotRun,
            sqliteCastPreflightProbe = SqliteCastPreflightProbeRunner::probe,
            checkPreflightProbe = CheckPreflightProbeRunner::probe,
            mysqlSequenceCanonicityProbe = MysqlSequenceCanonicityProbeRunner::probe,
            urlScrubber = LogScrubber::maskUrl,
            renderReport = SchemaMigrateReportRenderer::render,
            printError = { msg, src -> formatter.printError(msg, src) },
        )
        val tokenSource = CancellationTokenSource.create()
        val hookFired = AtomicBoolean(false)
        val hookThread = Thread(
            {
                if (hookFired.compareAndSet(false, true)) {
                    tokenSource.cancel("operator-ctrl-c (SIGINT)")
                    stderr("\n[d-migrate] Cancellation requested; rolling back atomic operations and exiting...")
                }
            },
            "dmigrate-schema-migrate-cancel-hook",
        )
        registerShutdownHook(hookThread)
        try {
            return runner.execute(request, tokenSource.token)
        } finally {
            if (!hookFired.get()) {
                runCatching { unregisterShutdownHook(hookThread) }
            }
        }
    }

    private fun defaultUnregisterShutdownHook(thread: Thread) {
        try {
            Runtime.getRuntime().removeShutdownHook(thread)
        } catch (_: IllegalStateException) {
            // JVM is already in shutdown — the hook either fired or
            // is firing, nothing to remove.
        } catch (_: IllegalArgumentException) {
            // Hook was never actually registered (e.g. tests that
            // inject a no-op registerShutdownHook).
        }
    }

    private fun loadFromDb(
        op: CompareOperand.Database,
        cfgPath: Path?,
        validator: SchemaValidator,
    ): ResolvedSchemaOperand {
        val (url, config) = try {
            val resolvedUrl = NamedConnectionResolver(configPathFromCli = cfgPath).resolve(op.source)
            resolvedUrl to CredentialFilling(op.source).fill(resolvedUrl)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "Config resolution failed", e)
        }
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
