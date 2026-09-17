package dev.dmigrate.mcp.registry

import dev.dmigrate.cli.commands.CompareSide
import dev.dmigrate.cli.commands.SchemaCompareSemantics
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.ReversePreferences
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.mcp.schema.SchemaContentLoader
import dev.dmigrate.mcp.schema.SchemaSource
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.service.ProfileDatabaseService
import dev.dmigrate.profiling.service.ProfileTableService
import dev.dmigrate.server.application.connection.ConnectionMaterializer
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.job.DataProfileJobWorker
import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.job.JobWorkerFactory
import dev.dmigrate.server.application.job.SchemaCompareJobWorker
import dev.dmigrate.server.application.job.SchemaReverseJobWorker
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.ConnectionReferenceStore
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.DiffStore
import dev.dmigrate.server.ports.ProfileStore
import dev.dmigrate.server.ports.ResolvedConnection
import dev.dmigrate.server.ports.SchemaStore
import java.time.Clock

/**
 * Production worker factory for the 0.9.6 controlled read-side jobs.
 *
 * The LF-012 / LN-011 / LN-017 / LN-027 default [dev.dmigrate.server.application.job.PassthroughJobWorkerFactory]
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
    /**
     * Die deklarierten Reverse-Praeferenzen des Servers (Block `reverse:` seiner
     * Konfiguration, `spec/dialect-preference-mechanism.md`) — fuer jeden
     * Lesezugriff auf eine Verbindung: `schema_reverse_start` und
     * `schema_compare_start` mit einem Verbindungs-Verweis. Ohne Deklaration
     * bleibt jeder Reverse unveraendert. `mcp serve` loest sie einmal beim
     * Start auf; lesbar, damit die Verdrahtung beider Serve-Zweige pruefbar
     * bleibt.
     */
    val reversePreferences: ReversePreferences = ReversePreferences(),
) : JobWorkerFactory {

    /** Der Vergleich von `schema_compare_start` — derselbe wie in CLI und `schema_compare`. */
    private val compareSchemas: (CompareSide, CompareSide) -> SchemaDiff = SchemaCompareSemantics::compare

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
                        .read(pool, readOptions(config.dialect)).schema
                }
            },
            publisher = artifacts.schemas(),
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
            publisher = artifacts.profiles(),
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
                                    .read(pool, readOptions(config.dialect)).schema
                            }
                        }
                        else -> error("schema_compare_start does not support ${uri.uri.kind.pathSegment} refs")
                    }
                    is ResourceUriParseResult.Invalid -> error("invalid schema_compare_start ref: ${uri.reason}")
                }
            },
            // Dieselbe Semantik und dieselben Funde wie das Werkzeug
            // `schema_compare` (SchemaCompareSemantics, SchemaCompareOutcome).
            comparator = { left, right -> SchemaCompareOutcome.ofSchemas(left, right, compareSchemas) },
            publisher = artifacts.comparisons(
                sourceRef = request.requiredString("sourceUri"),
                targetRef = request.requiredString("targetUri"),
            ),
        )

    private fun readOptions(dialect: DatabaseDialect): SchemaReadOptions =
        reversePreferences.applyTo(SchemaReadOptions(), dialect)

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
        DatabaseDialect.MSSQL -> ProfilingAdapterSet(
            dev.dmigrate.driver.mssql.profiling.MssqlSchemaIntrospectionAdapter(),
            dev.dmigrate.driver.mssql.profiling.MssqlProfilingDataAdapter(),
            dev.dmigrate.driver.mssql.profiling.MssqlLogicalTypeResolver(),
        )
        DatabaseDialect.ORACLE -> ProfilingAdapterSet(
            dev.dmigrate.driver.oracle.profiling.OracleSchemaIntrospectionAdapter(),
            dev.dmigrate.driver.oracle.profiling.OracleProfilingDataAdapter(),
            dev.dmigrate.driver.oracle.profiling.OracleLogicalTypeResolver(),
        )
    }

    private val artifacts = McpJobArtifacts(
        artifactStore = artifactStore,
        artifactContentStore = artifactContentStore,
        schemaStore = schemaStore,
        profileStore = profileStore,
        diffStore = diffStore,
        clock = clock,
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
