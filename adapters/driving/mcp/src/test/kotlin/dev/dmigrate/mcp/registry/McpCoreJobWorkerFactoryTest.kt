package dev.dmigrate.mcp.registry

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.AutoIncrementSyntaxReverse
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.ReversePreferences
import dev.dmigrate.driver.SqliteAutoincrementReverse
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.job.DataProfileJobWorker
import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.job.SchemaReverseJobWorker
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
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
import dev.dmigrate.server.ports.ResolvedConnection
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
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Clock
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

    test("McpCoreJobWorkerFactory publishes the schema_compare_start artifact (kind COMPARE) from schema refs") {
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
        artifactStore.findById(TENANT, success.artifactRefs.first().substringAfterLast('/'))!!.kind shouldBe
            ArtifactKind.COMPARE
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

    test("McpCoreJobWorkerFactory profile worker reaches the connection attempt for oracle") {
        // Bis zum Profiling-Modul wies ein Kommando-Gate oracle hier ab,
        // BEVOR ein Pool entstand. Jetzt muss der Worker bis zum
        // Verbindungsaufbau kommen -- der Host ist unerreichbar, der Fehler
        // also ein Verbindungs- und kein Kommando-Grenz-Fehler.
        val connectionStore = InMemoryConnectionReferenceStore()
        connectionStore.save(
            ConnectionReference(
                connectionId = "c1",
                tenantId = TENANT,
                resourceUri = ServerResourceUri(TENANT, ResourceKind.CONNECTIONS, "c1"),
                displayName = "oracle-test",
                dialectId = "oracle",
                sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                providerRef = "stub:provider",
                credentialRef = "stub:cred",
                allowedScopes = setOf("dmigrate:data:read"),
                allowedPrincipalIds = setOf(PRINCIPAL),
            ),
        )
        val resolver = object : ConnectionSecretResolver {
            override fun resolve(
                reference: dev.dmigrate.server.core.connection.ConnectionReference,
                principal: dev.dmigrate.server.core.principal.PrincipalContext,
            ): ResolvedConnection = ResolvedConnection.Success("oracle://unreachable-host/orclpdb1")
        }
        val factory = McpCoreJobWorkerFactory(
            connectionStore = connectionStore,
            connectionSecretResolver = resolver,
            artifactStore = InMemoryArtifactStore(),
            artifactContentStore = InMemoryArtifactContentStore(),
            schemaStore = InMemorySchemaStore(),
            profileStore = InMemoryProfileStore(),
            diffStore = InMemoryDiffStore(),
            limits = McpLimitsConfig(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )
        val token = CancellationTokenSource.create().token

        val profile = factory.create(
            operationRecord("job-profile", DataProfileStartHandler.OPERATION),
            connectionRequest(DataProfileStartHandler.TOOL_NAME),
        ).shouldNotBeNull()

        val failure = shouldThrow<Throwable> {
            profile.execute(operationRecord("job-profile", DataProfileStartHandler.OPERATION), token)
        }
        withClue("Fehler: ${failure::class.simpleName}: ${failure.message}") {
            failure.message.orEmpty() shouldNotContain "does not support dialect oracle"
        }
    }

    test("the reverse worker reads with the server's declared reverse preferences") {
        // `mcp serve` reicht den `reverse:`-Block seiner Konfiguration herein;
        // ohne ihn bleibt jeder Reverse unveraendert. Gemessen an SQLite, dem
        // einzigen Dialekt ohne Server: 64-Bit-Breite, einmal mit `serial`
        // (Default), einmal mit `identity`.
        val sqlite = SqliteJobFixture()
        try {
            sqlite.reverse(ReversePreferences()).schema shouldContain "identifier"
            sqlite.reverse(sqlite.width64).schema shouldContain "legacy_serial_syntax: true"
            val declared = sqlite.reverse(sqlite.width64Identity).schema
            withClue(declared) {
                declared shouldContain "type: identity"
                declared shouldNotContain "legacy_serial_syntax"
            }
        } finally {
            sqlite.close()
        }
    }

    test("the reverse job publishes the read report after the schema — the preference is not silent") {
        // spec/dialect-preference-mechanism.md, „Nicht stumm": wer ueber den
        // Server `identity` erklaert, findet die Bestaetigung im Report.
        val sqlite = SqliteJobFixture()
        try {
            val plain = sqlite.reverse(ReversePreferences())
            plain.kinds shouldBe listOf(ArtifactKind.SCHEMA, ArtifactKind.REVERSE_REPORT)
            plain.report shouldContain "kind: connection"
            plain.report shouldContain "value: \"dmigrate://tenants/acme/connections/c1\""
            plain.report shouldContain "code: R202"
            plain.report shouldNotContain "R205"
            val declared = sqlite.reverse(sqlite.width64Identity)
            withClue(declared.report) {
                declared.report shouldContain "code: R204"
                declared.report shouldContain "code: R205"
                declared.report shouldContain "per declared preference (reverse.sqlite.autoincrement_syntax: identity)"
            }
        } finally {
            sqlite.close()
        }
    }

    test("a compare job on connections publishes one read report per connection side, source first") {
        val sqlite = SqliteJobFixture()
        try {
            val run = sqlite.compare(
                sqlite.width64Identity,
                sourceUri = "dmigrate://tenants/acme/connections/c1",
                targetUri = "dmigrate://tenants/acme/connections/c1",
            )
            run.kinds shouldBe listOf(ArtifactKind.COMPARE, ArtifactKind.REVERSE_REPORT, ArtifactKind.REVERSE_REPORT)
            run.contents.drop(1).forEach { report ->
                withClue(report) {
                    report shouldContain "value: \"dmigrate://tenants/acme/connections/c1\""
                    report shouldContain "code: R205"
                }
            }
            // Ein gespeichertes Schema hat keinen Reverse-Report.
            saveSchema(sqlite.artifactStore, sqlite.contentStore, sqlite.schemaStore, "s1", "art-s1")
            val mixed = sqlite.compare(
                ReversePreferences(),
                sourceUri = "dmigrate://tenants/acme/schemas/s1",
                targetUri = "dmigrate://tenants/acme/connections/c1",
            )
            mixed.kinds shouldBe listOf(ArtifactKind.COMPARE, ArtifactKind.REVERSE_REPORT)
            mixed.contents[1] shouldContain "code: R202"
        } finally {
            sqlite.close()
        }
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
        val artifacts = jobArtifacts(
            artifactStore = artifactStore,
            contentStore = contentStore,
            schemaStore = schemaStore,
            profileStore = profileStore,
            diffStore = InMemoryDiffStore(),
        )
        val job = operationRecord("job-publish", SchemaReverseStartHandler.OPERATION)

        val schemaRef = artifacts.schemas().publish(job, SchemaDefinition(name = "orders", version = "1.0"))
        val profileRef = artifacts.profiles()
            .publish(job, DatabaseProfile(databaseProduct = "postgresql", tables = emptyList()))

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

    test("the compare publisher writes the COMPARE form and indexes it as a diff with both refs") {
        // Welche Nutzlast welcher Publisher nimmt, prueft der Compiler
        // (JobArtifactPublisher<P>); eine Laufzeit-Verzweigung nach dem Typ
        // gibt es nicht mehr.
        val artifactStore = InMemoryArtifactStore()
        val contentStore = InMemoryArtifactContentStore()
        val diffStore = InMemoryDiffStore()
        val artifacts = jobArtifacts(
            artifactStore = artifactStore,
            contentStore = contentStore,
            schemaStore = InMemorySchemaStore(),
            profileStore = InMemoryProfileStore(),
            diffStore = diffStore,
        )
        val job = compareJobRecord()
        val finding = mapOf("severity" to "info", "code" to "TABLE_ADDED", "path" to "tables.t", "message" to "m")
        val ref = artifacts.comparisons("dmigrate://tenants/acme/schemas/s1", "dmigrate://tenants/acme/schemas/s2")
            .publish(job, SchemaCompareOutcome(identical = false, findings = listOf(finding)))

        val record = artifactStore.findById(TENANT, ref.substringAfterLast('/')).shouldNotBeNull()
        record.kind shouldBe ArtifactKind.COMPARE
        record.managedArtifact.contentType shouldBe "application/json"
        val content = contentStore.openRangeRead(record.managedArtifact.artifactId, 0, record.managedArtifact.sizeBytes)
            .readAllBytes().toString(Charsets.UTF_8)
        val json = com.google.gson.JsonParser.parseString(content).asJsonObject
        json.keySet() shouldBe setOf("status", "summary", "findings")
        json.get("status").asString shouldBe "different"
        json.getAsJsonArray("findings").single().asJsonObject.get("code").asString shouldBe "TABLE_ADDED"
        diffStore.list(TENANT, PageRequest(pageSize = 10)).items.single().run {
            artifactRef shouldBe record.managedArtifact.artifactId
            sourceRef shouldBe "dmigrate://tenants/acme/schemas/s1"
            targetRef shouldBe "dmigrate://tenants/acme/schemas/s2"
            jobRef shouldBe job.resourceUri.render()
        }
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

private fun jobArtifacts(
    artifactStore: InMemoryArtifactStore,
    contentStore: InMemoryArtifactContentStore,
    schemaStore: InMemorySchemaStore,
    profileStore: InMemoryProfileStore,
    diffStore: InMemoryDiffStore,
) = McpJobArtifacts(
    artifactStore = artifactStore,
    artifactContentStore = contentStore,
    schemaStore = schemaStore,
    profileStore = profileStore,
    diffStore = diffStore,
    clock = Clock.fixed(NOW, ZoneOffset.UTC),
)

/** Eine SQLite-Datei mit einer AUTOINCREMENT-Tabelle hinter der Verbindung `c1`, gelesen ueber die echte Fabrik. */
private class SqliteJobFixture {
    val db: java.nio.file.Path = Files.createTempFile("mcp-reverse-preferences-", ".sqlite")
    val artifactStore = InMemoryArtifactStore()
    val contentStore = InMemoryArtifactContentStore()
    val schemaStore = InMemorySchemaStore()
    val width64 = ReversePreferences(sqliteAutoincrement = SqliteAutoincrementReverse.BIGINTEGER_IDENTITY)
    val width64Identity = width64.copy(
        autoIncrementSyntax = mapOf(DatabaseDialect.SQLITE to AutoIncrementSyntaxReverse.IDENTITY),
    )
    private val connectionStore = InMemoryConnectionReferenceStore()

    class Run(val kinds: List<ArtifactKind>, val contents: List<String>) {
        val schema: String get() = contents[0]
        val report: String get() = contents[1]
    }

    init {
        DatabaseDriverRegistry.loadAll()
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)") }
        }
        connectionStore.save(
            ConnectionReference(
                connectionId = "c1",
                tenantId = TENANT,
                resourceUri = ServerResourceUri(TENANT, ResourceKind.CONNECTIONS, "c1"),
                displayName = "sqlite-test",
                dialectId = "sqlite",
                sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                credentialRef = "stub:cred",
                allowedPrincipalIds = setOf(PRINCIPAL),
            ),
        )
    }

    fun reverse(preferences: ReversePreferences): Run {
        val record = operationRecord("job-reverse", SchemaReverseStartHandler.OPERATION)
        return run(preferences, record, connectionRequest(SchemaReverseStartHandler.TOOL_NAME))
    }

    fun compare(preferences: ReversePreferences, sourceUri: String, targetUri: String): Run =
        run(preferences, compareJobRecord(), compareRequest(sourceUri, targetUri))

    private fun run(
        preferences: ReversePreferences,
        record: dev.dmigrate.server.core.job.JobRecord,
        request: JobStartRequest,
    ): Run {
        val factory = McpCoreJobWorkerFactory(
            connectionStore = connectionStore,
            connectionSecretResolver = object : ConnectionSecretResolver {
                override fun resolve(
                    reference: ConnectionReference,
                    principal: dev.dmigrate.server.core.principal.PrincipalContext,
                ): ResolvedConnection = ResolvedConnection.Success("sqlite:$db")
            },
            artifactStore = artifactStore,
            artifactContentStore = contentStore,
            schemaStore = schemaStore,
            profileStore = InMemoryProfileStore(),
            diffStore = InMemoryDiffStore(),
            limits = McpLimitsConfig(),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            reversePreferences = preferences,
        )
        val outcome = factory.create(record, request).shouldNotBeNull()
            .execute(record, CancellationTokenSource.create().token)
        val refs = outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>().artifactRefs
        val records = refs.map { artifactStore.findById(TENANT, it.substringAfterLast('/')).shouldNotBeNull() }
        return Run(
            kinds = records.map { it.kind },
            contents = records.map {
                contentStore.openRangeRead(it.managedArtifact.artifactId, 0, it.managedArtifact.sizeBytes)
                    .readAllBytes().toString(Charsets.UTF_8)
            },
        )
    }

    fun close() {
        Files.deleteIfExists(db)
    }
}
