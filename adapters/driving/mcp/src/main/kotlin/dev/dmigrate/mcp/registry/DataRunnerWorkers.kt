package dev.dmigrate.mcp.registry

import dev.dmigrate.cli.commands.DataImportRequest
import dev.dmigrate.cli.commands.DataImportRunner
import dev.dmigrate.cli.commands.DataTransferRequest
import dev.dmigrate.cli.commands.DataTransferRunner
import dev.dmigrate.cli.commands.ImportExecutor
import dev.dmigrate.cli.commands.parseFilter
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.format.data.DefaultDataChunkReaderFactory
import dev.dmigrate.server.application.bootstrap.RuntimeBootstrap
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.ConnectionReferenceStore
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.ResolvedConnection
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.streaming.StreamingImporter
import dev.dmigrate.streaming.UnsupportedSeekableDataChunkReaderFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

data class DataRunnerDependencies(
    val artifactStore: ArtifactStore,
    val artifactContentStore: ArtifactContentStore,
    val connectionStore: ConnectionReferenceStore,
    val schemaStore: SchemaStore,
    val connectionSecretResolver: ConnectionSecretResolver,
    val tempDirectory: Path? = null,
)

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — rekursiver Cleanup eines Bundle-Extraktions-
 * Verzeichnisses. LF-010 / LF-013 / LN-009 / LN-011 fordert "Cleanup im finally-Pfad" für jeden
 * Bundle-Job, damit Disk-Space nicht zwischen Job-Runs leakt.
 *
 * Top-Level statt Worker-Member, weil Detekt sonst das
 * `TooManyFunctions`-Limit ausschlägt (Worker hat bereits viele
 * Pipeline-Schritte).
 */
internal fun recursivelyDeleteBundleDir(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
}

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — synchron extrahieren und Manifest-Konsistenz
 * gegen den Caller-supplied `tables`-Payload prüfen. `null` bei
 * Extraktionsfehler oder bei Tabellen-Drift.
 */
internal fun extractBundleArchive(
    bundleZip: Path,
    bundleRoot: Path,
    callerTables: List<String>,
): BundleExtractionOk? {
    val outcome = Files.newInputStream(bundleZip).use { stream ->
        dev.dmigrate.mcp.upload.bundle.BundleExtractor().extract(stream, bundleRoot)
    }
    val valid = outcome as? dev.dmigrate.mcp.upload.bundle.BundleExtractionOutcome.Valid ?: return null
    val callerNorm = callerTables.map { it.lowercase() }.toSortedSet()
    val manifestNorm = valid.manifest.tables.map { it.name.lowercase() }.toSortedSet()
    if (callerNorm != manifestNorm) return null
    return BundleExtractionOk(
        manifest = valid.manifest,
        extractedFiles = valid.extractedFiles,
        manifestFingerprint = valid.manifestFingerprint,
    )
}

internal data class BundleExtractionOk(
    val manifest: dev.dmigrate.server.core.upload.bundle.BundleManifest,
    val extractedFiles: Map<String, Path>,
    val manifestFingerprint: String,
)

internal class McpDataImportJobWorker(
    private val requestPayload: JsonValue.Obj,
    private val principal: PrincipalContext?,
    private val dependencies: DataRunnerDependencies,
) : JobWorker {

    override fun execute(job: JobRecord, token: dev.dmigrate.core.cancel.CancellationToken): JobWorkerOutcome {
        RuntimeBootstrap.initialize()
        token.throwIfCancellationRequested()
        val artifactId = artifactId(job)
        val artifact = dependencies.artifactStore.findById(job.tenantId, artifactId)
            ?: return JobWorkerOutcome.Failed("MCP_ARTIFACT_NOT_FOUND", "Artifact not found: $artifactId")
        return if (isBundleImport()) {
            executeBundleImport(job, artifactId, artifact.managedArtifact.sizeBytes, token)
        } else {
            executeSingleFileImport(job, artifactId, artifact.managedArtifact.sizeBytes, token)
        }
    }

    private fun executeSingleFileImport(
        job: JobRecord,
        artifactId: String,
        artifactSize: Long,
        token: dev.dmigrate.core.cancel.CancellationToken,
    ): JobWorkerOutcome {
        val source = spoolArtifact(artifactId, artifactSize, suffixFor(format()))
        val schema = spoolSchemaIfPresent(job)
        return try {
            token.throwIfCancellationRequested()
            val runner = buildRunner(job, schema)
            runRunnerWithMapping(runner, importRequest(source, schema?.path, table = string("table")), token)
        } finally {
            Files.deleteIfExists(source)
            schema?.path?.let { Files.deleteIfExists(it) }
        }
    }

    /**
     * LF-010 / LF-013 / LN-009 / LN-011 — Bundle-Import.
     *
     * LF-010 / LF-013 / LN-009 / LN-011 wortlaut: "Bundle-Extraktion erfolgt in ein job-lokales
     * Temp-Verzeichnis mit Cleanup im finally-Pfad." Pro Manifest-Eintrag
     * läuft der bestehende [DataImportRunner] mit der Bundle-Datei als
     * Source und dem Manifest-Tabellennamen als `table`. Eine
     * fehlgeschlagene Tabelle bricht den Job mit ihrem Exit-Code ab —
     * die nachfolgenden Tabellen werden nicht angefasst (LF-010 / LF-013 / LN-009 / LN-011
     * "stable VALIDATION_ERROR-Details ohne lokale Pfade" für
     * Inkonsistenzen).
     */
    private fun executeBundleImport(
        job: JobRecord,
        artifactId: String,
        artifactSize: Long,
        token: dev.dmigrate.core.cancel.CancellationToken,
    ): JobWorkerOutcome {
        val bundleZip = spoolArtifact(artifactId, artifactSize, ".zip")
        val bundleRoot = createBundleRoot()
        val schema = spoolSchemaIfPresent(job)
        return try {
            BundleImportPipeline().execute(
                bundleZip = bundleZip,
                bundleRoot = bundleRoot,
                callerTables = strings("tables").orEmpty(),
                cancellationToken = token,
                importTable = { sourcePath, table, format ->
                    val runner = buildRunner(job, schema)
                    val request = importRequest(
                        source = sourcePath,
                        schema = schema?.path,
                        table = table,
                        formatOverride = format,
                    )
                    runRunnerWithMapping(runner, request, token)
                },
            )
        } finally {
            Files.deleteIfExists(bundleZip)
            schema?.path?.let { Files.deleteIfExists(it) }
        }
    }

    private fun runRunnerWithMapping(
        runner: DataImportRunner,
        request: DataImportRequest,
        token: dev.dmigrate.core.cancel.CancellationToken,
    ): JobWorkerOutcome = when (val exit = runner.execute(request, token)) {
        0 -> JobWorkerOutcome.Succeeded()
        DataImportRunner.CANCELLED_EXIT_CODE -> JobWorkerOutcome.Cancelled("job cancelled")
        else -> JobWorkerOutcome.Failed("MCP_DATA_IMPORT_FAILED", "data import runner exited with $exit", exit)
    }

    private fun buildRunner(
        job: JobRecord,
        schema: Spool?,
    ): DataImportRunner = DataImportRunner(
        targetResolver = { target, _ -> resolveConnection(target, job) },
        urlParser = ConnectionUrlParser::parse,
        poolFactory = HikariConnectionPoolFactory::create,
        writerLookup = { dialect -> DatabaseDriverRegistry.get(dialect).dataWriter() },
        schemaPreflight = { schemaPath, input, importFormat ->
            val schemaFormat = schema?.format ?: schemaPath.toString().substringAfterLast('.', "json")
            SchemaRefImportPreflightAdapter.prepare(schemaPath, schemaFormat, input, importFormat)
        },
        schemaTargetValidator = SchemaRefImportPreflightAdapter::validateTargetTable,
        importExecutor = buildImportExecutor(),
        stderr = { },
    )

    private fun buildImportExecutor(): ImportExecutor = ImportExecutor { ctx, opts, resume, callbacks ->
        val writerLookup = { dialect: dev.dmigrate.driver.DatabaseDialect ->
            DatabaseDriverRegistry.get(dialect).dataWriter()
        }
        StreamingImporter(
            readerFactory = DefaultDataChunkReaderFactory(),
            seekableReaderFactory = UnsupportedSeekableDataChunkReaderFactory(
                reason = "MCP imports do not currently expose Parquet; use the CLI for Parquet imports."
            ),
            writerLookup = writerLookup,
            onTableOpened = callbacks.onTableOpened,
        ).import(
            pool = ctx.pool,
            input = ctx.input,
            format = opts.format,
            options = opts.options,
            readOptions = opts.readOptions,
            config = opts.config,
            progressReporter = callbacks.progressReporter,
            operationId = resume.operationId,
            resuming = resume.resuming,
            skippedTables = resume.skippedTables,
            resumeStateByTable = resume.resumeStateByTable,
            onChunkCommitted = callbacks.onChunkCommitted,
            onTableCompleted = callbacks.onTableCompleted,
            cancellationToken = ctx.cancellationToken,
        )
    }

    private fun importRequest(
        source: Path,
        schema: Path?,
        table: String?,
        formatOverride: String? = null,
    ): DataImportRequest =
        DataImportRequest(
            target = string("targetConnectionRef"),
            source = source.toString(),
            format = formatOverride ?: format(),
            schema = schema,
            table = table,
            tables = if (table == null) strings("tables") else null,
            onError = string("onError") ?: "abort",
            onConflict = string("onConflict"),
            triggerMode = string("triggerMode") ?: "fire",
            truncate = bool("truncate") ?: false,
            disableFkChecks = bool("disableFkChecks") ?: false,
            reseedSequences = bool("reseedSequences") ?: true,
            encoding = string("encoding"),
            csvNoHeader = bool("csvNoHeader") ?: false,
            csvNullString = string("csvNullString") ?: "",
            chunkSize = long("chunkSize")?.toInt() ?: 10_000,
            cliConfigPath = null,
            quiet = true,
            noProgress = true,
        )

    private fun isBundleImport(): Boolean =
        string("_wireArtifactKind") ==
            ArtifactUploadInitHandler.WIRE_KIND_SEED_DATA_BUNDLE

    private fun createBundleRoot(): Path {
        val baseDir = dependencies.tempDirectory ?: Files.createTempDirectory("mcp-import-bundle-").parent
        return Files.createTempDirectory(baseDir, "mcp-import-bundle-")
    }

    private fun spoolArtifact(artifactId: String, size: Long, suffix: String): Path {
        val target = tempFile("mcp-import-artifact-", suffix)
        dependencies.artifactContentStore.openRangeRead(artifactId, 0L, size).use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    private fun spoolSchemaIfPresent(job: JobRecord): Spool? {
        val ref = string("schemaRef") ?: return null
        val uri = parseRef(ref, ResourceKind.SCHEMAS, job)
            ?: return null
        val entry = dependencies.schemaStore.findById(job.tenantId, uri.id)
            ?: return null
        val artifact = dependencies.artifactStore.findById(job.tenantId, entry.artifactRef)
            ?: return null
        val format = entry.format ?: "json"
        val target = tempFile("mcp-import-schema-", ".$format")
        dependencies.artifactContentStore
            .openRangeRead(entry.artifactRef, 0L, artifact.managedArtifact.sizeBytes)
            .use { input -> Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING) }
        return Spool(target, format)
    }

    private fun format(): String = string("format") ?: "csv"

    private fun artifactId(job: JobRecord): String {
        string("artifactId")?.let { return it }
        val ref = string("sourceArtifactRef") ?: error("missing artifact id")
        return parseRef(ref, ResourceKind.ARTIFACTS, job)?.id ?: error("invalid artifact ref")
    }

    private data class Spool(val path: Path, val format: String)

    private fun string(name: String): String? = (requestPayload.fields[name] as? JsonValue.Str)?.value
    private fun bool(name: String): Boolean? = (requestPayload.fields[name] as? JsonValue.Bool)?.value
    private fun long(name: String): Long? = (requestPayload.fields[name] as? JsonValue.Num)?.value
    private fun strings(name: String): List<String>? =
        (requestPayload.fields[name] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value }

    private fun resolveConnection(ref: String?, job: JobRecord): String {
        require(!ref.isNullOrBlank()) { "missing connectionRef" }
        val uri = parseRef(ref, ResourceKind.CONNECTIONS, job)
            ?: throw IllegalArgumentException("invalid connectionRef")
        val reference = dependencies.connectionStore.findById(job.tenantId, uri.id)
            ?: throw IllegalArgumentException("connection not found: ${uri.id}")
        return resolveSecret(reference, job)
    }

    private fun resolveSecret(reference: ConnectionReference, job: JobRecord): String =
        when (val resolved = dependencies.connectionSecretResolver.resolve(reference, principal ?: minimalPrincipal(job))) {
            is ResolvedConnection.Success -> resolved.url
            is ResolvedConnection.Failure -> throw IllegalArgumentException(resolved.detail)
        }

    private fun parseRef(ref: String, expected: ResourceKind, job: JobRecord): ServerResourceUri? =
        when (val parsed = ServerResourceUri.parse(ref)) {
            is ResourceUriParseResult.Valid ->
                parsed.uri.takeIf { it.kind == expected && it.tenantId == job.tenantId }
            is ResourceUriParseResult.Invalid -> null
        }

    private fun tempFile(prefix: String, suffix: String): Path =
        dependencies.tempDirectory?.let { Files.createTempFile(it, prefix, suffix) }
            ?: Files.createTempFile(prefix, suffix)

    private fun suffixFor(format: String): String = ".$format"

    private fun minimalPrincipal(job: JobRecord): PrincipalContext =
        PrincipalContext(
            principalId = job.ownerPrincipalId,
            homeTenantId = job.tenantId,
            effectiveTenantId = job.tenantId,
            allowedTenantIds = setOf(job.tenantId),
            auditSubject = job.ownerPrincipalId.value,
            authSource = AuthSource.SERVICE_ACCOUNT,
            expiresAt = Instant.MAX,
        )

}

internal class McpDataTransferJobWorker(
    private val requestPayload: JsonValue.Obj,
    private val principal: PrincipalContext?,
    private val dependencies: DataRunnerDependencies,
) : JobWorker {

    override fun execute(job: JobRecord, token: dev.dmigrate.core.cancel.CancellationToken): JobWorkerOutcome {
        RuntimeBootstrap.initialize()
        val runner = DataTransferRunner(
            sourceResolver = { source, _ -> resolveConnection(source, job) },
            targetResolver = { target, _ -> resolveConnection(target, job) },
            urlParser = ConnectionUrlParser::parse,
            poolFactory = HikariConnectionPoolFactory::create,
            driverLookup = { dialect -> DatabaseDriverRegistry.get(dialect) },
            printError = { _, _ -> },
            stderr = { },
        )
        val parsedFilter = string("filter")?.let { parseFilter(it) }
        val request = DataTransferRequest(
            source = string("sourceConnectionRef") ?: "",
            target = string("targetConnectionRef") ?: "",
            tables = strings("tables"),
            filter = parsedFilter,
            sinceColumn = string("sinceColumn"),
            since = string("since"),
            onConflict = string("onConflict") ?: "abort",
            triggerMode = string("triggerMode") ?: "fire",
            truncate = bool("truncate") ?: false,
            chunkSize = long("chunkSize")?.toInt() ?: 10_000,
            cliConfigPath = null,
            quiet = true,
            noProgress = true,
        )
        return when (val exit = runner.execute(request, token)) {
            0 -> JobWorkerOutcome.Succeeded()
            DataTransferRunner.CANCELLED_EXIT_CODE -> JobWorkerOutcome.Cancelled("job cancelled")
            else -> JobWorkerOutcome.Failed("MCP_DATA_TRANSFER_FAILED", "data transfer runner exited with $exit", exit)
        }
    }

    private fun string(name: String): String? = (requestPayload.fields[name] as? JsonValue.Str)?.value
    private fun bool(name: String): Boolean? = (requestPayload.fields[name] as? JsonValue.Bool)?.value
    private fun long(name: String): Long? = (requestPayload.fields[name] as? JsonValue.Num)?.value
    private fun strings(name: String): List<String>? =
        (requestPayload.fields[name] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Str)?.value }

    private fun resolveConnection(ref: String, job: JobRecord): String {
        val uri = when (val parsed = ServerResourceUri.parse(ref)) {
            is ResourceUriParseResult.Valid -> parsed.uri
            is ResourceUriParseResult.Invalid -> throw IllegalArgumentException("invalid connectionRef: ${parsed.reason}")
        }
        require(uri.kind == ResourceKind.CONNECTIONS && uri.tenantId == job.tenantId) {
            "connectionRef is outside the job tenant"
        }
        val reference = dependencies.connectionStore.findById(job.tenantId, uri.id)
            ?: throw IllegalArgumentException("connection not found: ${uri.id}")
        return when (val resolved = dependencies.connectionSecretResolver.resolve(reference, principal ?: minimalPrincipal(job))) {
            is ResolvedConnection.Success -> resolved.url
            is ResolvedConnection.Failure -> throw IllegalArgumentException(resolved.detail)
        }
    }

    private fun minimalPrincipal(job: JobRecord): PrincipalContext =
        PrincipalContext(
            principalId = job.ownerPrincipalId,
            homeTenantId = job.tenantId,
            effectiveTenantId = job.tenantId,
            allowedTenantIds = setOf(job.tenantId),
            auditSubject = job.ownerPrincipalId.value,
            authSource = AuthSource.SERVICE_ACCOUNT,
            expiresAt = Instant.MAX,
        )
}
