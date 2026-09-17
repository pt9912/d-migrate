package dev.dmigrate.mcp.registry

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.diff.migration.ReverseMarkerNormalizer
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.ResolvedConnection
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import dev.dmigrate.server.ports.memory.InMemoryDiffStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryProfileStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val TENANT = TenantId("acme")
private val OWNER = PrincipalId("alice")
private val EPOCH = Instant.parse("2026-01-01T00:00:00Z")

/** Status, Fund-Codes, die vollen Funde und die rohe Antwort einer Oberflaeche. */
private data class Result(val status: String, val codes: List<String>, val findings: JsonArray, val raw: String)

/**
 * **Eine** Semantik fuer `schema compare` in beiden MCP-Oberflaechen — das
 * Werkzeug `schema_compare` durch die echte Registry
 * (`McpRuntimeRegistries`), der Job `schema_compare_start` durch die echte
 * Fabrik (`McpCoreJobWorkerFactory`). Jeder Fall laeuft durch beide; die
 * CLI prueft dasselbe in `SchemaCompareCommandSemanticsTest`.
 *
 * - die Dialekt-Schreibweise roher Ausdruecke ist gleichgesetzt (ADR 0056,
 *   Slice-Befund M3: ein strikter Comparator an der Verdrahtung blieb gruen);
 * - die Reverse-Markierung zaehlt nicht (P11b: zwei Reverses verschiedener
 *   Dialekte ergaben `SCHEMA_NAME_CHANGED`);
 * - der Sequenzname zaehlt nicht, wo ein Server ihn vergibt (P6);
 *   `legacy_serial_syntax` zaehlt — die Mehrdeutigkeit loest die
 *   Reverse-Praeferenz, nicht der Vergleich;
 * - der Job veroeffentlicht dieselben Funde wie das Werkzeug.
 */
class SchemaCompareRuntimeSemanticsTest : FunSpec({

    val artifactStore = InMemoryArtifactStore()
    val contentStore = InMemoryArtifactContentStore()
    val schemaStore = InMemorySchemaStore()
    val clock = Clock.fixed(Instant.parse("2026-05-02T12:00:00Z"), ZoneOffset.UTC)
    val tool = McpRuntimeRegistries.defaultToolRegistry(
        McpRuntimeWiring(
            uploadSessionStore = InMemoryUploadSessionStore(),
            uploadSegmentStore = InMemoryUploadSegmentStore(),
            artifactStore = artifactStore,
            artifactContentStore = contentStore,
            schemaStore = schemaStore,
            jobStore = InMemoryJobStore(),
            quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
            limits = McpLimitsConfig(),
            clock = clock,
        ),
    ).findHandler("schema_compare")!!
    val jobs = McpCoreJobWorkerFactory(
        connectionStore = InMemoryConnectionReferenceStore(),
        connectionSecretResolver = object : ConnectionSecretResolver {
            override fun resolve(
                reference: ConnectionReference,
                principal: PrincipalContext,
            ): ResolvedConnection = error("connection resolver must not be used in this test")
        },
        artifactStore = artifactStore,
        artifactContentStore = contentStore,
        schemaStore = schemaStore,
        profileStore = InMemoryProfileStore(),
        diffStore = InMemoryDiffStore(),
        limits = McpLimitsConfig(),
        clock = clock,
    )

    fun ref(id: String) = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, id).render()

    fun stage(schemaId: String, json: String) {
        val artifactId = "art-$schemaId"
        val bytes = json.toByteArray(Charsets.UTF_8)
        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId, filename = "$artifactId.json", contentType = "application/json",
                    sizeBytes = bytes.size.toLong(), sha256 = "0".repeat(64),
                    createdAt = EPOCH, expiresAt = EPOCH.plusSeconds(86_400L * 365),
                ),
                kind = ArtifactKind.SCHEMA,
                tenantId = TENANT,
                ownerPrincipalId = OWNER,
                visibility = JobVisibility.TENANT,
                resourceUri = ServerResourceUri(TENANT, ResourceKind.ARTIFACTS, artifactId),
            ),
        )
        contentStore.write(artifactId, ByteArrayInputStream(bytes), bytes.size.toLong())
        schemaStore.save(
            SchemaIndexEntry(
                schemaId = schemaId, tenantId = TENANT,
                resourceUri = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, schemaId),
                artifactRef = artifactId, displayName = schemaId,
                createdAt = EPOCH, expiresAt = EPOCH.plusSeconds(86_400L * 365),
                format = "json",
            ),
        )
    }

    fun parse(payload: JsonObject) = Result(
        status = payload.get("status").asString,
        codes = payload.getAsJsonArray("findings").map { (it as JsonObject).get("code").asString },
        findings = payload.getAsJsonArray("findings"),
        raw = payload.toString(),
    )

    fun viaTool(left: String, right: String): Result {
        val outcome = tool.handle(
            ToolCallContext(
                "schema_compare",
                JsonParser.parseString("""{"left":{"schemaRef":"${ref(left)}"},"right":{"schemaRef":"${ref(right)}"}}""")
                    .asJsonObject,
                Fixtures.principalContext(),
            ),
        )
        val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
        return parse(JsonParser.parseString(text).asJsonObject)
    }

    fun jobRecord() = Fixtures.jobRecord("job-compare").copy(
        managedJob = ManagedJob(
            jobId = "job-compare", operation = SchemaCompareStartHandler.OPERATION, status = JobStatus.RUNNING,
            createdAt = EPOCH, updatedAt = EPOCH, expiresAt = EPOCH.plusSeconds(3600), createdBy = OWNER.value,
        ),
    )

    fun viaJob(left: String, right: String): Result {
        val request = JobStartRequest(
            toolName = SchemaCompareStartHandler.TOOL_NAME,
            tenantId = TENANT,
            callerId = OWNER,
            idempotencyKey = "idem-$left-$right",
            approvalToken = null,
            payload = JsonValue.Obj(
                linkedMapOf("sourceUri" to JsonValue.Str(ref(left)), "targetUri" to JsonValue.Str(ref(right))),
            ),
            refs = emptyList(),
            now = EPOCH,
            principalContext = Fixtures.principalContext(),
            jobBuilder = { _, _ -> jobRecord() },
        )
        val worker = jobs.create(jobRecord(), request)!!
        val outcome = worker.execute(jobRecord(), CancellationTokenSource.create().token)
        val artifactId = outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>().artifactRefs.single()
            .substringAfterLast('/')
        val record = artifactStore.findById(TENANT, artifactId)!!
        record.kind shouldBe ArtifactKind.DIFF
        val bytes = contentStore.openRangeRead(artifactId, 0, record.managedArtifact.sizeBytes).readAllBytes()
        return parse(JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)
    }

    /** Beide Oberflaechen, mit dem Namen der Oberflaeche im Fehlerfall. */
    fun bothSurfaces(left: String, right: String, check: (Result) -> Unit) {
        withClue("schema_compare") { check(viaTool(left, right)) }
        withClue("schema_compare_start") { check(viaJob(left, right)) }
    }

    fun schema(name: String, version: String, check: String, predicate: String, generation: String) =
        """{"name":"$name","version":"$version","tables":{"orders":{"columns":{""" +
            """"id":{"type":"biginteger","required":true,"generation":$generation},""" +
            """"quantity":{"type":"integer"},"status":{"type":"text","max_length":20}},""" +
            """"primary_key":["id"],""" +
            """"indices":[{"name":"ix_open","columns":["status"],"where":"$predicate"}],""" +
            """"constraints":[{"name":"ck_qty","type":"check","expression":"$check"}]}}}"""

    val identity = """{"type":"identity","mode":"by_default"}"""
    val pgCheck = "((quantity > 0) AND ((status)::text <> 'x'::text))"
    val pgPredicate = "((status)::text <> 'DONE'::text)"
    val reverse = ReverseScopeCodec.REVERSE_VERSION

    stage("pg-spelling", schema("shop", "1", pgCheck, pgPredicate, identity))
    stage("ms-spelling", schema("shop", "1", "[quantity]>(0) AND [status]<>'x'", "[status]<>'DONE'", identity))
    stage("changed", schema("shop", "1", pgCheck, "status <> 'VOID'", identity))

    val pgIdentity = """{"type":"identity","mode":"by_default","sequence_name":"public.orders_id_seq"}"""
    // Wie der MySQL-Reverse `AUTO_INCREMENT` auf `bigint` liest: ohne
    // Deklaration als `serial`, mit der Praeferenz `identity` ohne das Flag.
    val mySerial = """{"type":"identity","mode":"by_default","legacy_serial_syntax":true}"""
    val myIdentity = """{"type":"identity","mode":"by_default"}"""
    val myName = ReverseScopeCodec.mysqlName("shop")
    stage("pg-reverse", schema(ReverseScopeCodec.postgresName("shop", "public"), reverse, pgCheck, pgPredicate, pgIdentity))
    stage("my-reverse", schema(myName, reverse, pgCheck, pgPredicate, mySerial))
    stage("my-reverse-identity", schema(myName, reverse, pgCheck, pgPredicate, myIdentity))
    stage("pg-authored", schema("shop", "1", pgCheck, pgPredicate, pgIdentity))
    stage("my-authored", schema("shop", "1", pgCheck, pgPredicate, myIdentity))
    stage("broken-marker", schema(myName, "1", pgCheck, pgPredicate, mySerial))
    stage("renamed", schema("shop2", "2", pgCheck, pgPredicate, pgIdentity))

    context("die Faltung roher Ausdruecke ist verdrahtet") {

        test("a CHECK and an index predicate in another spelling, with PostgreSQL's casts, are identical") {
            bothSurfaces("pg-spelling", "ms-spelling") { result ->
                result.status shouldBe "identical"
                result.codes.shouldBeEmpty()
            }
        }

        test("control: a real change of the predicate is reported") {
            bothSurfaces("pg-spelling", "changed") { result ->
                result.status shouldBe "different"
                result.codes shouldContain "TABLE_INDEX_CHANGED"
            }
        }
    }

    context("Reverse-Markierung und Erzeugungs-Projektion") {

        test("two reverses from different dialects: no name change; the serial flag of MySQL's reverse is one") {
            bothSurfaces("pg-reverse", "my-reverse") { result ->
                result.codes shouldBe listOf("TABLE_COLUMN_GENERATION_CHANGED")
                result.status shouldBe "different"
            }
        }

        test("… and none once the MySQL reverse declared identity") {
            bothSurfaces("pg-reverse", "my-reverse-identity") { result ->
                result.codes.shouldBeEmpty()
                result.status shouldBe "identical"
            }
        }

        test("two hand-written schemas with the same identity difference stay strict") {
            bothSurfaces("pg-authored", "my-authored") { result ->
                result.codes shouldBe listOf("TABLE_COLUMN_GENERATION_CHANGED")
            }
        }

        test("a reverse against a hand-written schema: name and version are no subject, no placeholder leaks") {
            bothSurfaces("pg-reverse", "pg-authored") { result ->
                result.codes.shouldBeEmpty()
                result.status shouldBe "identical"
                result.raw shouldNotContain ReverseMarkerNormalizer.NORMALIZED_NAME
                result.raw shouldNotContain ReverseMarkerNormalizer.NORMALIZED_VERSION
            }
        }

        test("two hand-written schemas: name and version carry the values of both sides") {
            bothSurfaces("pg-authored", "renamed") { result ->
                val byCode = result.findings.map { it.asJsonObject }.associateBy { it.get("code").asString }
                result.codes shouldBe listOf("SCHEMA_NAME_CHANGED", "SCHEMA_VERSION_CHANGED")
                byCode.getValue("SCHEMA_NAME_CHANGED").getAsJsonObject("details").run {
                    get("before").asString shouldBe "shop"
                    get("after").asString shouldBe "shop2"
                }
                byCode.getValue("SCHEMA_VERSION_CHANGED").getAsJsonObject("details").run {
                    get("before").asString shouldBe "1"
                    get("after").asString shouldBe "2"
                }
            }
        }

        test("a half marker is rejected, not compared") {
            val failure = shouldThrow<ValidationErrorException> { viaTool("broken-marker", "pg-authored") }
            failure.violations.single().field shouldBe "left.schemaRef"
            shouldThrow<IllegalStateException> { viaJob("pg-authored", "broken-marker") }
        }
    }

    test("the job publishes W137 like the tool, and the status stays identical") {
        fun computed(expression: String) =
            """{"name":"shop","version":"1","tables":{"t":{"columns":{"id":{"type":"integer"},""" +
                """"total":{"type":"integer","generation":{"type":"computed","expression":"$expression","stored":true}}},""" +
                """"primary_key":["id"]}}}"""
        stage("computed-a", computed("id * 2"))
        stage("computed-b", computed("(id * 2)"))
        bothSurfaces("computed-a", "computed-b") { result ->
            result.status shouldBe "identical"
            result.codes shouldBe listOf("W137")
        }
    }
})
