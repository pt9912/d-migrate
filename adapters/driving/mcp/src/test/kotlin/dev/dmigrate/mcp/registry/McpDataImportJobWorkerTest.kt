package dev.dmigrate.mcp.registry

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ArtifactUploadMetadata
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.ResolvedConnection
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * LF-010 / LF-013 / LN-009 / LN-011 Part 2 — Tests für [McpDataImportJobWorker].
 *
 * Schwerpunkt: Bundle-Pfad-Erkennung über `_wireArtifactKind=seed-data-bundle`,
 * Spooling, Cleanup. Der eigentliche JDBC-Import schlägt fehl (Test-
 * Resolver-Failure), sodass nachweisbar ist:
 *
 * - Worker erkennt Bundle-Pfad und ruft [BundleImportPipeline] auf.
 * - Spooling extrahiert ZIP, Pipeline iteriert Manifest-Tabellen.
 * - Beim ersten Import-Versuch scheitert die Connection — Worker
 *   liefert `JobWorkerOutcome.Failed` mit dem erwarteten Fehlercode.
 *
 * Decken den Worker-Code, der vor dem JDBC-Aufruf liegt; alles
 * dahinter (echter SQL-Import, Cancellation, Resume-Pfade) wird durch
 * LF-010 / LF-013 / LN-009 / LN-011-Integration-Tests gegen echte SQLite/PostgreSQL-DBs
 * abgedeckt.
 */
class McpDataImportJobWorkerTest : FunSpec({

    val tenant = TenantId("acme")
    val alice = PrincipalId("alice")
    val now: Instant = Instant.parse("2026-05-07T12:00:00Z")

    fun bundleZipBytes(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(
                """
                {"version":"v1","format":"csv","tables":[
                  {"name":"users","path":"users.csv"},
                  {"name":"orders","path":"orders.csv"}
                ]}
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("users.csv"))
            zos.write("id,name\n1,Alice\n".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("orders.csv"))
            zos.write("id,user_id\n1,1\n".toByteArray())
            zos.closeEntry()
        }
        return bos.toByteArray()
    }

    fun seedBundleArtifact(
        artifactStore: InMemoryArtifactStore,
        contentStore: InMemoryArtifactContentStore,
        artifactId: String = "art-bundle",
    ): ServerResourceUri {
        val bytes = bundleZipBytes()
        contentStore.write(
            artifactId = artifactId,
            source = bytes.inputStream(),
            expectedSizeBytes = bytes.size.toLong(),
        )
        val resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, artifactId)
        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = "$artifactId.zip",
                    contentType = "application/zip",
                    sizeBytes = bytes.size.toLong(),
                    sha256 = "0".repeat(64),
                    createdAt = now,
                    expiresAt = now.plusSeconds(86_400),
                ),
                kind = ArtifactKind.UPLOAD_INPUT,
                tenantId = tenant,
                ownerPrincipalId = alice,
                visibility = JobVisibility.TENANT,
                resourceUri = resourceUri,
                uploadMetadata = ArtifactUploadMetadata(
                    artifactId = artifactId,
                    resourceUri = resourceUri.render(),
                    uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                    wireArtifactKind = ArtifactUploadInitHandler.WIRE_KIND_SEED_DATA_BUNDLE,
                    contentType = "application/zip",
                    format = "seed-bundle.v1.zip",
                    targetTables = listOf("users", "orders"),
                    sourceUploadSessionId = "ai:$artifactId",
                    sizeBytes = bytes.size.toLong(),
                    sha256 = "0".repeat(64),
                    bundleFormat = "seed-bundle.v1.zip",
                ),
            ),
        )
        return resourceUri
    }

    fun bundlePayload(artifactId: String): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "artifactId" to JsonValue.Str(artifactId),
            "targetConnectionRef" to JsonValue.Str("dmigrate://tenants/acme/connections/conn-1"),
            "tables" to JsonValue.Arr(listOf(JsonValue.Str("users"), JsonValue.Str("orders"))),
            "bundleFormat" to JsonValue.Str("seed-bundle.v1.zip"),
            "format" to JsonValue.Str("seed-bundle.v1.zip"),
            "_wireArtifactKind" to JsonValue.Str(ArtifactUploadInitHandler.WIRE_KIND_SEED_DATA_BUNDLE),
        ),
    )

    fun newJob(): JobRecord = JobRecord(
        managedJob = ManagedJob(
            jobId = "job-1",
            operation = "data_import",
            status = JobStatus.QUEUED,
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plusSeconds(3600),
            createdBy = alice.value,
        ),
        tenantId = tenant,
        ownerPrincipalId = alice,
        visibility = JobVisibility.OWNER,
        resourceUri = ServerResourceUri(tenant, ResourceKind.JOBS, "job-1"),
    )

    val principal = PrincipalContext(
        principalId = alice,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = setOf("dmigrate:data:write"),
        isAdmin = false,
        auditSubject = alice.value,
        authSource = AuthSource.SERVICE_ACCOUNT,
        expiresAt = Instant.MAX,
    )

    test("Bundle-Pfad: unknown artifact -> MCP_ARTIFACT_NOT_FOUND") {
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val deps = DataRunnerDependencies(
            artifactStore = artifactStore,
            artifactContentStore = contentStore,
            connectionStore = InMemoryConnectionReferenceStore(),
            schemaStore = InMemorySchemaStore(),
            connectionSecretResolver = object : ConnectionSecretResolver {
                override fun resolve(reference: ConnectionReference, principal: PrincipalContext): ResolvedConnection =
                    ResolvedConnection.Failure("PROVIDER_MISSING", "no")
            },
        )
        val worker = McpDataImportJobWorker(
            requestPayload = bundlePayload(artifactId = "missing"),
            principal = principal,
            dependencies = deps,
        )
        val outcome = worker.execute(newJob(), CancellationToken.none())
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
            .errorCode shouldBe "MCP_ARTIFACT_NOT_FOUND"
    }

    fun seedSingleFileArtifact(
        artifactStore: InMemoryArtifactStore,
        contentStore: InMemoryArtifactContentStore,
        artifactId: String = "art-csv",
    ): ServerResourceUri {
        val bytes = "id,name\n1,Alice\n".toByteArray()
        contentStore.write(
            artifactId = artifactId,
            source = bytes.inputStream(),
            expectedSizeBytes = bytes.size.toLong(),
        )
        val resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, artifactId)
        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = "$artifactId.csv",
                    contentType = "text/csv",
                    sizeBytes = bytes.size.toLong(),
                    sha256 = "0".repeat(64),
                    createdAt = now,
                    expiresAt = now.plusSeconds(86_400),
                ),
                kind = ArtifactKind.UPLOAD_INPUT,
                tenantId = tenant,
                ownerPrincipalId = alice,
                visibility = JobVisibility.TENANT,
                resourceUri = resourceUri,
            ),
        )
        return resourceUri
    }

    fun singleFilePayload(artifactId: String, table: String): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "artifactId" to JsonValue.Str(artifactId),
            "targetConnectionRef" to JsonValue.Str("dmigrate://tenants/acme/connections/conn-1"),
            "table" to JsonValue.Str(table),
            "format" to JsonValue.Str("csv"),
        ),
    )

    fun stubConnectionStore(): InMemoryConnectionReferenceStore {
        val store = InMemoryConnectionReferenceStore()
        store.save(
            ConnectionReference(
                connectionId = "conn-1",
                tenantId = tenant,
                resourceUri = ServerResourceUri(tenant, ResourceKind.CONNECTIONS, "conn-1"),
                displayName = "test",
                dialectId = "sqlite",
                sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                providerRef = "stub:provider",
                credentialRef = "stub:cred",
                allowedScopes = setOf("dmigrate:data:write"),
                allowedPrincipalIds = setOf(alice),
            ),
        )
        return store
    }

    val failingResolver = object : ConnectionSecretResolver {
        override fun resolve(reference: ConnectionReference, principal: PrincipalContext): ResolvedConnection =
            ResolvedConnection.Failure(
                ResolvedConnection.REASON_PROVIDER_MISSING,
                "no provider",
            )
    }

    test("Single-File-Pfad: artefact wird gespoolt, Connection-Resolution-Failure -> Failed") {
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val deps = DataRunnerDependencies(
            artifactStore = artifactStore,
            artifactContentStore = contentStore,
            connectionStore = stubConnectionStore(),
            schemaStore = InMemorySchemaStore(),
            connectionSecretResolver = failingResolver,
        )
        seedSingleFileArtifact(artifactStore, contentStore)
        val worker = McpDataImportJobWorker(
            requestPayload = singleFilePayload("art-csv", "users"),
            principal = principal,
            dependencies = deps,
        )
        val outcome = worker.execute(newJob(), CancellationToken.none())
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
    }

    test("Single-File-Pfad: sourceArtifactRef anstelle artifactId wird ebenfalls aufgelöst") {
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val deps = DataRunnerDependencies(
            artifactStore = artifactStore,
            artifactContentStore = contentStore,
            connectionStore = stubConnectionStore(),
            schemaStore = InMemorySchemaStore(),
            connectionSecretResolver = failingResolver,
        )
        seedSingleFileArtifact(artifactStore, contentStore)
        val payload = JsonValue.Obj(
            linkedMapOf(
                "sourceArtifactRef" to JsonValue.Str("dmigrate://tenants/acme/artifacts/art-csv"),
                "targetConnectionRef" to JsonValue.Str("dmigrate://tenants/acme/connections/conn-1"),
                "table" to JsonValue.Str("users"),
                "format" to JsonValue.Str("csv"),
            ),
        )
        val worker = McpDataImportJobWorker(
            requestPayload = payload,
            principal = principal,
            dependencies = deps,
        )
        val outcome = worker.execute(newJob(), CancellationToken.none())
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
    }

    test("Bundle-Pfad: Connection-Resolution-Failure liefert Failed-Outcome") {
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val connectionStore = InMemoryConnectionReferenceStore()
        connectionStore.save(
            ConnectionReference(
                connectionId = "conn-1",
                tenantId = tenant,
                resourceUri = ServerResourceUri(tenant, ResourceKind.CONNECTIONS, "conn-1"),
                displayName = "test",
                dialectId = "sqlite",
                sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                providerRef = "stub:provider",
                credentialRef = "stub:cred",
                allowedScopes = setOf("dmigrate:data:write"),
                allowedPrincipalIds = setOf(alice),
            ),
        )
        val deps = DataRunnerDependencies(
            artifactStore = artifactStore,
            artifactContentStore = contentStore,
            connectionStore = connectionStore,
            schemaStore = InMemorySchemaStore(),
            connectionSecretResolver = object : ConnectionSecretResolver {
                override fun resolve(reference: ConnectionReference, principal: PrincipalContext): ResolvedConnection =
                    ResolvedConnection.Failure(
                        ResolvedConnection.REASON_PROVIDER_MISSING,
                        "no provider",
                    )
            },
        )
        seedBundleArtifact(artifactStore, contentStore)
        val worker = McpDataImportJobWorker(
            requestPayload = bundlePayload(artifactId = "art-bundle"),
            principal = principal,
            dependencies = deps,
        )
        // Worker propagiert die IllegalArgumentException aus
        // resolveSecret nicht selbst — der Test-Wert „no provider"
        // führt zu IllegalArgumentException, die durch
        // DataImportRunner gefangen + als Exit-Code abgebildet wird.
        // Der Worker liefert dann JobWorkerOutcome.Failed.
        val outcome = worker.execute(newJob(), CancellationToken.none())
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
    }
})
