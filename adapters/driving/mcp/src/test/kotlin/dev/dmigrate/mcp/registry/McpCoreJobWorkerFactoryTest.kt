package dev.dmigrate.mcp.registry

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.job.DataProfileJobWorker
import dev.dmigrate.server.application.job.JobArtifactPublisher
import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.job.SchemaReverseJobWorker
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import dev.dmigrate.server.ports.memory.InMemoryDiffStore
import dev.dmigrate.server.ports.memory.InMemoryProfileStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val TENANT = TenantId("acme")
private val PRINCIPAL = PrincipalId("alice")
private val NOW: Instant = Instant.parse("2026-05-05T12:00:00Z")
private const val SCHEMA_JSON = """{"name":"orders","version":"1.0","tables":{}}"""

class McpCoreJobWorkerFactoryTest : FunSpec({

    test("FileBackedApprovalGrantStore persists token-bound grants without raw tokens") {
        val file = Files.createTempFile("approval-grants", ".json")
        val grant = approvalGrant(tokenFingerprint = "fp-token")

        FileBackedApprovalGrantStore(file).save(grant)
        val reloaded = FileBackedApprovalGrantStore(file)

        reloaded.findByTokenFingerprint(TENANT, "fp-token") shouldBe grant
        Files.readString(file).contains("raw-secret-token") shouldBe false
        reloaded.deleteExpired(NOW.plusSeconds(10)) shouldBe 1
        reloaded.findByTokenFingerprint(TENANT, "fp-token").shouldBeNull()
    }

    test("FileBackedApprovalGrantStore supports YAML, replacement, missing lookup and no-op expiry cleanup") {
        val file = Files.createTempFile("approval-grants", ".yaml")
        val store = FileBackedApprovalGrantStore(file)
        store.findByTokenFingerprint(TENANT, "missing").shouldBeNull()

        store.save(approvalGrant(tokenFingerprint = "same-token", scopes = setOf("data.read")))
        store.save(approvalGrant(tokenFingerprint = "same-token", scopes = setOf("data.read", "schema.read")))

        val reloaded = FileBackedApprovalGrantStore(file)
        val grant = reloaded.findByTokenFingerprint(TENANT, "same-token").shouldNotBeNull()
        grant.issuedScopes shouldBe setOf("data.read", "schema.read")
        reloaded.deleteExpired(NOW.minusSeconds(1)) shouldBe 0
    }

    test("McpCoreJobWorkerFactory publishes schema_compare_start diff artifact from schema refs") {
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val schemaStore = InMemorySchemaStore()
        val diffStore = InMemoryDiffStore()
        saveSchema(artifactStore, contentStore, schemaStore, "s1", "art-s1")
        saveSchema(artifactStore, contentStore, schemaStore, "s2", "art-s2")

        val factory = McpCoreJobWorkerFactory(
            connectionStore = InMemoryConnectionReferenceStore(),
            connectionSecretResolver = unusedConnectionSecretResolver(),
            artifactStore = artifactStore,
            artifactContentStore = contentStore,
            schemaStore = schemaStore,
            profileStore = InMemoryProfileStore(),
            diffStore = diffStore,
            limits = McpLimitsConfig(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

        val worker = factory.create(compareJobRecord(), compareRequest()).shouldNotBeNull()
        val outcome = worker.execute(compareJobRecord(), CancellationTokenSource.create().token)

        val success = outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>()
        success.artifactRefs.shouldHaveSize(1)
        success.artifactRefs.first() shouldStartWith "dmigrate://tenants/acme/artifacts/art-"
        diffStore.list(TENANT, dev.dmigrate.server.core.pagination.PageRequest(pageSize = 10)).items
            .shouldHaveSize(1)
    }

    test("McpCoreJobWorkerFactory creates read-side connection workers") {
        val factory = McpCoreJobWorkerFactory(
            connectionStore = InMemoryConnectionReferenceStore(),
            connectionSecretResolver = unusedConnectionSecretResolver(),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            profileStore = InMemoryProfileStore(),
            diffStore = InMemoryDiffStore(),
            limits = McpLimitsConfig(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

        factory.create(
            operationRecord("job-reverse", SchemaReverseStartHandler.OPERATION),
            connectionRequest(SchemaReverseStartHandler.TOOL_NAME),
        ).shouldBeInstanceOf<SchemaReverseJobWorker>()

        factory.create(
            operationRecord("job-profile", DataProfileStartHandler.OPERATION),
            connectionRequest(DataProfileStartHandler.TOOL_NAME),
        ).shouldBeInstanceOf<DataProfileJobWorker>()
    }

    test("McpCoreJobWorkerFactory connection workers fail before DB access when connection ref is missing") {
        val factory = McpCoreJobWorkerFactory(
            connectionStore = InMemoryConnectionReferenceStore(),
            connectionSecretResolver = unusedConnectionSecretResolver(),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            profileStore = InMemoryProfileStore(),
            diffStore = InMemoryDiffStore(),
            limits = McpLimitsConfig(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )
        val token = CancellationTokenSource.create().token

        val reverse = factory.create(
            operationRecord("job-reverse", SchemaReverseStartHandler.OPERATION),
            connectionRequest(SchemaReverseStartHandler.TOOL_NAME),
        ).shouldNotBeNull()
        shouldThrow<IllegalStateException> {
            reverse.execute(operationRecord("job-reverse", SchemaReverseStartHandler.OPERATION), token)
        }.message shouldBe "connectionRef not found: dmigrate://tenants/acme/connections/c1"

        val profile = factory.create(
            operationRecord("job-profile", DataProfileStartHandler.OPERATION),
            connectionRequest(DataProfileStartHandler.TOOL_NAME),
        ).shouldNotBeNull()
        shouldThrow<IllegalStateException> {
            profile.execute(operationRecord("job-profile", DataProfileStartHandler.OPERATION), token)
        }.message shouldBe "connectionRef not found: dmigrate://tenants/acme/connections/c1"
    }

    test("McpCoreJobWorkerFactory compare worker rejects unsupported refs") {
        val factory = McpCoreJobWorkerFactory(
            connectionStore = InMemoryConnectionReferenceStore(),
            connectionSecretResolver = unusedConnectionSecretResolver(),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            profileStore = InMemoryProfileStore(),
            diffStore = InMemoryDiffStore(),
            limits = McpLimitsConfig(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )
        val worker = factory.create(
            operationRecord("job-compare", SchemaCompareStartHandler.OPERATION),
            compareRequest(sourceUri = "dmigrate://tenants/acme/jobs/not-a-schema"),
        ).shouldNotBeNull()

        shouldThrow<IllegalStateException> {
            worker.execute(operationRecord("job-compare", SchemaCompareStartHandler.OPERATION), CancellationTokenSource.create().token)
        }.message shouldBe "schema_compare_start does not support jobs refs"
    }

    test("Mcp job artifact publisher indexes schema and profile artifacts") {
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val schemaStore = InMemorySchemaStore()
        val profileStore = InMemoryProfileStore()
        val publisher = mcpJobArtifactPublisher(
            artifactStore = artifactStore,
            contentStore = contentStore,
            schemaStore = schemaStore,
            profileStore = profileStore,
            diffStore = InMemoryDiffStore(),
        )
        val job = operationRecord("job-publish", SchemaReverseStartHandler.OPERATION)

        val schemaRef = publisher.publish(job, SchemaDefinition(name = "orders", version = "1.0"))
        val profileRef = publisher.publish(job, DatabaseProfile(databaseProduct = "postgresql", tables = emptyList()))

        schemaRef shouldStartWith "dmigrate://tenants/acme/artifacts/art-"
        profileRef shouldStartWith "dmigrate://tenants/acme/artifacts/art-"
        artifactStore.list(TENANT, PageRequest(pageSize = 10)).items.shouldHaveSize(2)
        schemaStore.list(TENANT, PageRequest(pageSize = 10)).items.single().run {
            displayName shouldBe "Schema from ${SchemaReverseStartHandler.OPERATION}"
            format shouldBe "yaml"
            jobRef shouldBe job.resourceUri.render()
        }
        profileStore.list(TENANT, PageRequest(pageSize = 10)).items.single().run {
            displayName shouldBe "Profile from ${SchemaReverseStartHandler.OPERATION}"
            jobRef shouldBe job.resourceUri.render()
        }
    }

    test("Mcp job artifact publisher rejects unsupported payloads") {
        val publisher = mcpJobArtifactPublisher(
            artifactStore = InMemoryArtifactStore(),
            contentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            profileStore = InMemoryProfileStore(),
            diffStore = InMemoryDiffStore(),
        )

        shouldThrow<IllegalStateException> {
            publisher.publish(operationRecord("job-bad-payload", SchemaReverseStartHandler.OPERATION), "unsupported")
        }.message shouldBe "unsupported MCP job artifact payload type: kotlin.String"
    }

    test("McpCoreJobWorkerFactory returns null for operations it does not own") {
        val factory = McpCoreJobWorkerFactory(
            connectionStore = InMemoryConnectionReferenceStore(),
            connectionSecretResolver = unusedConnectionSecretResolver(),
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            profileStore = InMemoryProfileStore(),
            diffStore = InMemoryDiffStore(),
            limits = McpLimitsConfig(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )
        factory.create(Fixtures.jobRecord("job-unknown"), compareRequest()).shouldBeNull()
    }
})

private fun approvalGrant(
    tokenFingerprint: String,
    scopes: Set<String> = setOf("data.read"),
): ApprovalGrant =
    ApprovalGrant(
        approvalRequestId = "appr-1",
        correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
        correlationKey = "idem-1",
        approvalTokenFingerprint = tokenFingerprint,
        toolName = "schema_reverse_start",
        tenantId = TENANT,
        callerId = PRINCIPAL,
        payloadFingerprint = "payload-fp",
        issuerFingerprint = "issuer-1",
        issuedScopes = scopes,
        grantSource = "test",
        expiresAt = NOW.plusSeconds(5),
    )

private fun saveSchema(
    artifactStore: InMemoryArtifactStore,
    contentStore: InMemoryArtifactContentStore,
    schemaStore: InMemorySchemaStore,
    schemaId: String,
    artifactId: String,
) {
    val bytes = SCHEMA_JSON.toByteArray(Charsets.UTF_8)
    contentStore.write(artifactId, ByteArrayInputStream(bytes), bytes.size.toLong())
    artifactStore.save(
        ArtifactRecord(
            managedArtifact = ManagedArtifact(
                artifactId = artifactId,
                filename = "$artifactId.json",
                contentType = "application/json",
                sizeBytes = bytes.size.toLong(),
                sha256 = "0".repeat(64),
                createdAt = NOW,
                expiresAt = NOW.plusSeconds(3600),
            ),
            kind = ArtifactKind.SCHEMA,
            tenantId = TENANT,
            ownerPrincipalId = PRINCIPAL,
            visibility = dev.dmigrate.server.core.job.JobVisibility.OWNER,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.ARTIFACTS, artifactId),
        ),
    )
    schemaStore.save(
        SchemaIndexEntry(
            schemaId = schemaId,
            tenantId = TENANT,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, schemaId),
            artifactRef = artifactId,
            displayName = schemaId,
            createdAt = NOW,
            expiresAt = NOW.plusSeconds(3600),
            format = "json",
        ),
    )
}

private fun compareJobRecord() =
    operationRecord("job-compare", SchemaCompareStartHandler.OPERATION)

private fun operationRecord(jobId: String, operation: String) =
    Fixtures.jobRecord(jobId).copy(
        managedJob = ManagedJob(
            jobId = jobId,
            operation = operation,
            status = JobStatus.RUNNING,
            createdAt = NOW,
            updatedAt = NOW,
            expiresAt = NOW.plusSeconds(3600),
            createdBy = PRINCIPAL.value,
        ),
    )

private fun compareRequest(
    sourceUri: String = "dmigrate://tenants/acme/schemas/s1",
    targetUri: String = "dmigrate://tenants/acme/schemas/s2",
) =
    JobStartRequest(
        toolName = SchemaCompareStartHandler.TOOL_NAME,
        tenantId = TENANT,
        callerId = PRINCIPAL,
        idempotencyKey = "idem-compare",
        approvalToken = null,
        payload = JsonValue.Obj(
            linkedMapOf(
                "sourceUri" to JsonValue.Str(sourceUri),
                "targetUri" to JsonValue.Str(targetUri),
            ),
        ),
        refs = emptyList(),
        now = NOW,
        principalContext = Fixtures.principalContext(),
        jobBuilder = { _, _ -> compareJobRecord() },
    )

private fun connectionRequest(toolName: String) =
    JobStartRequest(
        toolName = toolName,
        tenantId = TENANT,
        callerId = PRINCIPAL,
        idempotencyKey = "idem-$toolName",
        approvalToken = null,
        payload = JsonValue.Obj(
            linkedMapOf(
                "connectionId" to JsonValue.Str("dmigrate://tenants/acme/connections/c1"),
            ),
        ),
        refs = emptyList(),
        now = NOW,
        principalContext = Fixtures.principalContext(),
        jobBuilder = { _, _ -> operationRecord("job-$toolName", toolName) },
    )

private fun unusedConnectionSecretResolver() = object : ConnectionSecretResolver {
    override fun resolve(
        reference: dev.dmigrate.server.core.connection.ConnectionReference,
        principal: dev.dmigrate.server.core.principal.PrincipalContext,
    ): dev.dmigrate.server.ports.ResolvedConnection =
        error("connection resolver must not be used in this test")
}

private fun mcpJobArtifactPublisher(
    artifactStore: InMemoryArtifactStore,
    contentStore: InMemoryArtifactContentStore,
    schemaStore: InMemorySchemaStore,
    profileStore: InMemoryProfileStore,
    diffStore: InMemoryDiffStore,
): JobArtifactPublisher {
    val constructor = Class.forName("dev.dmigrate.mcp.registry.McpJobArtifactPublisher")
        .declaredConstructors
        .first { it.parameterCount == 9 }
    constructor.isAccessible = true
    return constructor.newInstance(
        artifactStore,
        contentStore,
        schemaStore,
        profileStore,
        diffStore,
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofHours(24),
        null,
        null,
    ) as JobArtifactPublisher
}
