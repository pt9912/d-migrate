package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.format.report.ProfileReportWriter
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSource
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.profiling.service.ProfileDatabaseService
import dev.dmigrate.profiling.service.ProfileTableService
import dev.dmigrate.server.application.connection.ConnectionMaterializer
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.job.DataProfileJobWorker
import dev.dmigrate.server.application.job.JobArtifactPublisher
import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.job.JobWorkerFactory
import dev.dmigrate.server.application.job.SchemaCompareJobWorker
import dev.dmigrate.server.application.job.SchemaReverseJobWorker
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.ConnectionReferenceStore
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.DiffStore
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.ProfileStore
import dev.dmigrate.server.ports.ResolvedConnection
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Production worker factory for the 0.9.6 controlled read-side jobs.
 *
 * The Phase-E default [dev.dmigrate.server.application.job.PassthroughJobWorkerFactory]
 * only proves dispatch wiring. This factory binds the committed MCP job
 * request to the existing application runners/ports so `schema_reverse_start`,
 * `data_profile_start`, and `schema_compare_start` actually perform their
 * domain work and publish artifacts.
 */
class McpCoreJobWorkerFactory(
    private val connectionStore: ConnectionReferenceStore,
    private val connectionSecretResolver: ConnectionSecretResolver,
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
    private val schemaStore: SchemaStore,
    private val profileStore: ProfileStore,
    private val diffStore: DiffStore,
    private val limits: McpLimitsConfig,
    private val clock: Clock,
) : JobWorkerFactory {

    override fun create(record: JobRecord, request: JobStartRequest) = when (record.managedJob.operation) {
        SchemaReverseStartHandler.OPERATION -> reverseWorker(request)
        DataProfileStartHandler.OPERATION -> profileWorker(request)
        SchemaCompareStartHandler.OPERATION -> compareWorker(request)
        else -> null
    }

    private fun reverseWorker(request: JobStartRequest) =
        SchemaReverseJobWorker(
            connectionRef = request.requiredString("connectionId"),
            materializer = materializer(request),
            readSchema = { config, token ->
                HikariConnectionPoolFactory.create(config).use { pool ->
                    token.throwIfCancellationRequested()
                    DatabaseDriverRegistry.get(config.dialect).schemaReader()
                        .read(pool, SchemaReadOptions()).schema
                }
            },
            publisher = publisher(),
        )

    private fun profileWorker(request: JobStartRequest) =
        DataProfileJobWorker(
            connectionRef = request.requiredString("connectionId"),
            materializer = materializer(request),
            runProfile = { config, token ->
                HikariConnectionPoolFactory.create(config).use { pool ->
                    val adapters = profilingAdapters(config.dialect)
                    ProfileDatabaseService(adapters, ProfileTableService(adapters))
                        .profile(
                            pool = pool,
                            databaseProduct = config.dialect.name.lowercase(),
                            schema = request.optionalString("schema"),
                            tables = request.optionalStringList("includes"),
                            cancellationToken = token,
                        )
                }
            },
            publisher = publisher(),
        )

    private fun compareWorker(request: JobStartRequest) =
        SchemaCompareJobWorker(
            sourceRef = request.requiredString("sourceUri"),
            targetRef = request.requiredString("targetUri"),
            schemaLoader = { ref, tenant, token ->
                token.throwIfCancellationRequested()
                when (val uri = parseResourceUri(ref)) {
                    is ResourceUriParseResult.Valid -> when (uri.uri.kind) {
                        ResourceKind.SCHEMAS -> loadSchemaRef(ref, request.principal(), token)
                        ResourceKind.CONNECTIONS -> {
                            val config = materializer(request).materialize(ref, tenant)
                            HikariConnectionPoolFactory.create(config).use { pool ->
                                token.throwIfCancellationRequested()
                                DatabaseDriverRegistry.get(config.dialect).schemaReader()
                                    .read(pool, SchemaReadOptions()).schema
                            }
                        }
                        else -> error("schema_compare_start does not support ${uri.uri.kind.pathSegment} refs")
                    }
                    is ResourceUriParseResult.Invalid -> error("invalid schema_compare_start ref: ${uri.reason}")
                }
            },
            comparator = { left, right -> SchemaComparator().compare(left, right) },
            publisher = publisher(
                sourceRef = request.requiredString("sourceUri"),
                targetRef = request.requiredString("targetUri"),
            ),
        )

    private fun materializer(request: JobStartRequest): ConnectionMaterializer {
        val principal = request.principal()
        return ConnectionMaterializer { connectionRef, tenant ->
            val uri = when (val parsed = parseResourceUri(connectionRef)) {
                is ResourceUriParseResult.Valid -> parsed.uri
                is ResourceUriParseResult.Invalid -> error("invalid connectionRef: ${parsed.reason}")
            }
            require(uri.kind == ResourceKind.CONNECTIONS) {
                "expected connectionRef, got ${uri.kind.pathSegment}"
            }
            require(uri.tenantId == tenant) {
                "connectionRef tenant '${uri.tenantId.value}' is outside job tenant '${tenant.value}'"
            }
            val ref = connectionStore.findById(uri.tenantId, uri.id)
                ?: error("connectionRef not found: $connectionRef")
            require(ref.isReadableBy(principal, tenant)) {
                "principal '${principal.principalId.value}' is not allowed to use connection '${ref.connectionId}'"
            }
            when (val resolved = connectionSecretResolver.resolve(ref, principal)) {
                is ResolvedConnection.Success -> ConnectionUrlParser.parse(resolved.url)
                is ResolvedConnection.Failure -> error(
                    "connection '${ref.connectionId}' could not be materialized: ${resolved.reason}: ${resolved.detail}",
                )
            }
        }
    }

    private fun loadSchemaRef(
        ref: String,
        @Suppress("UNUSED_PARAMETER")
        principal: PrincipalContext,
        token: dev.dmigrate.core.cancel.CancellationToken,
    ): SchemaDefinition {
        val parsed = parseResourceUri(ref) as? ResourceUriParseResult.Valid
            ?: error("invalid schemaRef: $ref")
        val entry = schemaStore.findById(parsed.uri.tenantId, parsed.uri.id)
            ?: error("schemaRef not found: $ref")
        token.throwIfCancellationRequested()
        return SchemaContentLoader(artifactStore, artifactContentStore, limits)
            .load(SchemaSource.Reference(entry), entry.format ?: "json")
            .also { token.throwIfCancellationRequested() }
    }

    private fun profilingAdapters(dialect: DatabaseDialect): ProfilingAdapterSet = when (dialect) {
        DatabaseDialect.POSTGRESQL -> ProfilingAdapterSet(
            dev.dmigrate.driver.postgresql.profiling.PostgresSchemaIntrospectionAdapter(),
            dev.dmigrate.driver.postgresql.profiling.PostgresProfilingDataAdapter(),
            dev.dmigrate.driver.postgresql.profiling.PostgresLogicalTypeResolver(),
        )
        DatabaseDialect.MYSQL -> ProfilingAdapterSet(
            dev.dmigrate.driver.mysql.profiling.MysqlSchemaIntrospectionAdapter(),
            dev.dmigrate.driver.mysql.profiling.MysqlProfilingDataAdapter(),
            dev.dmigrate.driver.mysql.profiling.MysqlLogicalTypeResolver(),
        )
        DatabaseDialect.SQLITE -> ProfilingAdapterSet(
            dev.dmigrate.driver.sqlite.profiling.SqliteSchemaIntrospectionAdapter(),
            dev.dmigrate.driver.sqlite.profiling.SqliteProfilingDataAdapter(),
            dev.dmigrate.driver.sqlite.profiling.SqliteLogicalTypeResolver(),
        )
    }

    private fun publisher(
        sourceRef: String? = null,
        targetRef: String? = null,
    ): JobArtifactPublisher = McpJobArtifactPublisher(
        artifactStore = artifactStore,
        artifactContentStore = artifactContentStore,
        schemaStore = schemaStore,
        profileStore = profileStore,
        diffStore = diffStore,
        clock = clock,
        sourceRef = sourceRef,
        targetRef = targetRef,
    )

    private fun parseResourceUri(ref: String): ResourceUriParseResult = ServerResourceUri.parse(ref)

    private fun JobStartRequest.principal(): PrincipalContext =
        principalContext ?: error("JobStartRequest.principalContext is required for MCP workers")

    private fun JobStartRequest.requiredString(field: String): String =
        optionalString(field) ?: error("missing required payload field '$field'")

    private fun JobStartRequest.optionalString(field: String): String? =
        (payload.fields[field] as? JsonValue.Str)?.value

    private fun JobStartRequest.optionalStringList(field: String): List<String>? =
        (payload.fields[field] as? JsonValue.Arr)?.items
            ?.mapNotNull { (it as? JsonValue.Str)?.value }
            ?.takeIf { it.isNotEmpty() }
}

private class McpJobArtifactPublisher(
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
    private val schemaStore: SchemaStore,
    private val profileStore: ProfileStore,
    private val diffStore: DiffStore,
    private val clock: Clock,
    private val ttl: Duration = Duration.ofHours(24),
    private val sourceRef: String? = null,
    private val targetRef: String? = null,
) : JobArtifactPublisher {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun publish(job: JobRecord, payload: Any): String {
        val rendered = renderPayload(payload)
        val artifactId = "art-${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val bytes = rendered.bytes
        val write = artifactContentStore.write(artifactId, ByteArrayInputStream(bytes), bytes.size.toLong())
        val sha256 = when (write) {
            is WriteArtifactOutcome.Stored -> write.sha256
            is WriteArtifactOutcome.AlreadyExists -> write.existingSha256
            is WriteArtifactOutcome.SizeMismatch ->
                error("artifact size mismatch while publishing job '${job.managedJob.jobId}'")
            is WriteArtifactOutcome.Conflict ->
                error("artifact id conflict while publishing job '${job.managedJob.jobId}'")
        }

        val now = clock.instant()
        val artifactUri = ServerResourceUri(job.tenantId, ResourceKind.ARTIFACTS, artifactId)
        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = rendered.filename,
                    contentType = rendered.contentType,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha256,
                    createdAt = now,
                    expiresAt = now.plus(ttl),
                ),
                kind = rendered.kind,
                tenantId = job.tenantId,
                ownerPrincipalId = job.ownerPrincipalId,
                visibility = JobVisibility.OWNER,
                resourceUri = artifactUri,
                jobRef = job.resourceUri.render(),
            ),
        )
        indexPayload(job, rendered, artifactId, artifactUri, sha256, now)
        return artifactUri.render()
    }

    private fun renderPayload(payload: Any): RenderedArtifact = when (payload) {
        is SchemaDefinition -> RenderedArtifact(
            kind = ArtifactKind.SCHEMA,
            filename = "schema-${safeId()}.yaml",
            contentType = "application/x-yaml",
            bytes = ByteArrayOutputStream().also { SchemaFileResolver.codecForFormat("yaml").write(it, payload) }
                .toByteArray(),
        )
        is DatabaseProfile -> RenderedArtifact(
            kind = ArtifactKind.PROFILE,
            filename = "profile-${safeId()}.json",
            contentType = "application/json",
            bytes = ProfileReportWriter().renderJson(payload).toByteArray(Charsets.UTF_8),
        )
        is SchemaDiff -> RenderedArtifact(
            kind = ArtifactKind.DIFF,
            filename = "diff-${safeId()}.json",
            contentType = "application/json",
            bytes = gson.toJson(payload).toByteArray(Charsets.UTF_8),
        )
        else -> error("unsupported MCP job artifact payload type: ${payload::class.qualifiedName}")
    }

    private fun indexPayload(
        job: JobRecord,
        rendered: RenderedArtifact,
        artifactId: String,
        artifactUri: ServerResourceUri,
        sha256: String,
        now: java.time.Instant,
    ) {
        when (rendered.kind) {
            ArtifactKind.SCHEMA -> schemaStore.save(
                SchemaIndexEntry(
                    schemaId = "sch-${artifactId.removePrefix("art-")}",
                    tenantId = job.tenantId,
                    resourceUri = ServerResourceUri(job.tenantId, ResourceKind.SCHEMAS, "sch-${artifactId.removePrefix("art-")}"),
                    artifactRef = artifactId,
                    displayName = "Schema from ${job.managedJob.operation}",
                    createdAt = now,
                    expiresAt = now.plus(ttl),
                    jobRef = job.resourceUri.render(),
                    format = "yaml",
                    origin = job.managedJob.operation,
                    sizeBytes = rendered.bytes.size.toLong(),
                    hash = sha256,
                ),
            )
            ArtifactKind.PROFILE -> profileStore.save(
                ProfileIndexEntry(
                    profileId = "prof-${artifactId.removePrefix("art-")}",
                    tenantId = job.tenantId,
                    resourceUri = ServerResourceUri(job.tenantId, ResourceKind.PROFILES, "prof-${artifactId.removePrefix("art-")}"),
                    artifactRef = artifactId,
                    displayName = "Profile from ${job.managedJob.operation}",
                    createdAt = now,
                    expiresAt = now.plus(ttl),
                    jobRef = job.resourceUri.render(),
                ),
            )
            ArtifactKind.DIFF -> diffStore.save(
                DiffIndexEntry(
                    diffId = "diff-${artifactId.removePrefix("art-")}",
                    tenantId = job.tenantId,
                    resourceUri = ServerResourceUri(job.tenantId, ResourceKind.DIFFS, "diff-${artifactId.removePrefix("art-")}"),
                    artifactRef = artifactId,
                    sourceRef = sourceRef ?: "",
                    targetRef = targetRef ?: "",
                    displayName = "Diff from ${job.managedJob.operation}",
                    createdAt = now,
                    expiresAt = now.plus(ttl),
                    jobRef = job.resourceUri.render(),
                    statusSummary = "DIFF_PUBLISHED",
                ),
            )
            else -> Unit
        }
        require(artifactUri.kind == ResourceKind.ARTIFACTS)
    }

    private fun safeId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    private data class RenderedArtifact(
        val kind: ArtifactKind,
        val filename: String,
        val contentType: String,
        val bytes: ByteArray,
    )
}
