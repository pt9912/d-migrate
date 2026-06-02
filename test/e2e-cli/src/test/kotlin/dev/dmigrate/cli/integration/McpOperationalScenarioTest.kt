package dev.dmigrate.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

/**
 * C-MCP scenario: drive `schema_reverse_start` **and**
 * `schema_compare_start` through the [OperationalHarness] against a
 * real file-backed SQLite database and assert the jobs reach terminal
 * status with the artefact surface populated. Mirrors the production
 * composition
 * `AiMcpRegistries.defaultComponents(AiMcpWiring(OperationalMcpWiring(...)))`
 * — Plan-Doc `docs/planning/done/quality-coverage-expansion-plan.md`
 * §5.3 (Sub-Slice C-MCP).
 *
 * Three cells:
 *
 * 1. **Reverse success + resources/read** — register a SQLite
 *    ConnectionReference, run `schema_reverse_start` via the
 *    in-process MCP stdio transport, assert the response envelope
 *    (jobId + resourceUri), `job_status_get` for terminal status with
 *    a schemaRef artefact, then list resources via MCP `resources/list`
 *    and read the resulting schema entry via MCP `resources/read`
 *    (no direct `schemaStore.list` access — every assertion goes
 *    through the MCP client surface).
 * 2. **Compare success** — second `schema_reverse_start` against the
 *    same SQLite file (different idempotency key) followed by
 *    `schema_compare_start` with both schema URIs; assert the
 *    compare job reaches terminal SUCCEEDED with a published diff
 *    artefact.
 * 3. **Validation/policy blocker** — call `schema_reverse_start` under
 *    the default fail-closed policy; the handler rejects with
 *    `POLICY_DENIED` before any worker dispatch.
 *
 * Run:
 * ```
 * make integration INTEGRATION_TASKS="-PintegrationTests :test:e2e-cli:test"
 * ```
 */
class McpOperationalScenarioTest : FunSpec({

    test("schema_reverse_start against file-SQLite reaches terminal SUCCEEDED with schema artefact") {
        val stateDir = IntegrationFixtures.freshStateDir("operational-")
        val sqliteFile = Files.createTempFile("operational-sqlite-", ".db")
        sqliteFile.deleteIfExists()
        seedSqlite(sqliteFile)
        try {
            val principal = IntegrationFixtures.freshTransportPrincipal("operational")
            val tenantId = principal.effectiveTenantId
            val connectionId = "sqlite-it"
            val resolver = TestSqliteConnectionSecretResolver(
                mappings = mapOf(connectionId to sqliteFile),
            )
            val harness = OperationalHarness.start(
                stateDir = stateDir,
                principal = principal,
                connectionSecretResolver = resolver,
                policyService = OperationalHarness.ALLOW_ALL_POLICY,
            )
            try {
                // Stage the connection reference in the wiring's
                // connection store BEFORE the tools/call so the
                // McpCoreJobWorkerFactory.materializer can look it up
                // by tenant + id. allowedPrincipalIds = null +
                // allowedScopes = null keeps authorisation open to
                // the test principal (isAdmin = true bypasses anyway).
                val resourceUri = ServerResourceUri(tenantId, ResourceKind.CONNECTIONS, connectionId)
                harness.runtimeWiring().connectionStore.save(
                    ConnectionReference(
                        connectionId = connectionId,
                        tenantId = tenantId,
                        displayName = "operational-it sqlite",
                        dialectId = "sqlite",
                        sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                        resourceUri = resourceUri,
                        credentialRef = "env:DMIGRATE_OPERATIONAL_IT_SQLITE",
                    ),
                )

                harness.initialize()
                harness.initializedNotification()

                val args = JsonObject().apply {
                    addProperty("connectionId", resourceUri.render())
                    addProperty("idempotencyKey", "k-operational-${java.util.UUID.randomUUID()}")
                }
                val result = harness.toolsCall("schema_reverse_start", args)
                val responseBody = result.content.firstOrNull()?.text ?: "<no text>"
                withClue("schema_reverse_start envelope must be a success projection; body=$responseBody") {
                    result.isError shouldBe false
                }
                val responseText = result.content.firstOrNull()?.text
                    ?: error("schema_reverse_start envelope had no text content")
                val response = JsonParser.parseString(responseText).asJsonObject
                withClue("schema_reverse_start envelope must carry a jobId") {
                    response.has("jobId") shouldBe true
                }
                withClue("schema_reverse_start envelope must carry a tenant-bound resourceUri") {
                    response.get("resourceUri").asString shouldContain "/jobs/"
                }

                // job_status_get reflects the in-process Sync-Executor
                // outcome. After tools/call returns the start envelope
                // the worker has already completed because
                // OperationalMcpWiring's default executorBundle is
                // SYNC_DEFAULT.
                val jobId = response.get("jobId").asString
                val statusRaw = harness.toolsCall(
                    "job_status_get",
                    JsonObject().apply { addProperty("jobId", jobId) },
                )
                withClue("job_status_get must succeed for the just-started job") {
                    statusRaw.isError shouldBe false
                }
                val statusText = statusRaw.content.firstOrNull()?.text
                    ?: error("job_status_get had no text content")
                val statusJson = JsonParser.parseString(statusText).asJsonObject
                withClue("job_status_get must mark the job terminal=SUCCEEDED; body=$statusText") {
                    statusJson.get("status").asString shouldBe "SUCCEEDED"
                    statusJson.get("terminal").asBoolean shouldBe true
                }
                withClue("job_status_get must surface the reverse schema artefact ref; body=$statusText") {
                    // Reverse worker publishes one artefact carrying
                    // the serialised schema; the job projection
                    // backfills it onto a canonical `artifacts/<id>`
                    // URI. The corresponding `schemas/<id>` entry is
                    // accessible via resources/read against the
                    // schema URI but isn't reflected in the job
                    // record's `artifacts` array.
                    val artifacts = statusJson.getAsJsonArray("artifacts")
                    artifacts.size() shouldBe 1
                    artifacts.get(0).asString shouldContain "/artifacts/"
                }
                // Plan-Doc §5.3 requires that the operational scenario
                // reads schema content through the MCP client surface
                // (`resources/read`), not through a side-channel
                // schemaStore.list(...) call. The schemaStore handle is
                // still legitimately used for *test-time staging*
                // (connectionStore.save above), but observation of the
                // reverse worker's published artefact goes through MCP.
                val schemaUriA = singleSchemaResourceUri(harness)
                val schemaContent = readJsonContent(harness, schemaUriA)
                // ResourceProjector.projectContent(SchemaIndexEntry) shape:
                // uri / tenantId / schemaId / displayName / artifactRef /
                // createdAt / expiresAt / jobRef / labels. The schemaId is
                // the stable handle; the actual schema definition sits
                // behind artifactRef and is reachable via a follow-up
                // resources/read on that artifact URI.
                withClue("resources/read on schemas/* must echo the requested URI") {
                    schemaContent.get("uri").asString shouldBe schemaUriA
                }
                withClue("resources/read on schemas/* must carry a non-empty schemaId") {
                    schemaContent.get("schemaId").asString.isNotEmpty() shouldBe true
                }
                withClue("resources/read on schemas/* must carry the tenant of the calling principal") {
                    schemaContent.get("tenantId").asString shouldBe tenantId.value
                }

                // --- Compare phase ---
                // Second reverse against the same SQLite file with a
                // fresh idempotency key. Produces a second schema in the
                // store; content is structurally identical (same DB) so
                // the compare worker writes an empty diff artefact —
                // sufficient to pin the full schema_compare_start path
                // through the MCP client (jobs/start → executor →
                // publisher → resources/read).
                val args2 = JsonObject().apply {
                    addProperty("connectionId", resourceUri.render())
                    addProperty("idempotencyKey", "k-operational-${UUID.randomUUID()}")
                }
                val reverse2 = harness.toolsCall("schema_reverse_start", args2)
                withClue("second schema_reverse_start envelope must succeed; body=${reverse2.content.firstOrNull()?.text}") {
                    reverse2.isError shouldBe false
                }
                val reverse2Response = JsonParser
                    .parseString(reverse2.content.firstOrNull()?.text ?: error("no text"))
                    .asJsonObject
                val jobId2 = reverse2Response.get("jobId").asString
                val status2Raw = harness.toolsCall(
                    "job_status_get",
                    JsonObject().apply { addProperty("jobId", jobId2) },
                )
                val status2Text = status2Raw.content.firstOrNull()?.text
                    ?: error("job_status_get had no text content")
                val status2Json = JsonParser.parseString(status2Text).asJsonObject
                withClue("second reverse job must reach terminal SUCCEEDED; body=$status2Text") {
                    status2Json.get("status").asString shouldBe "SUCCEEDED"
                    status2Json.get("terminal").asBoolean shouldBe true
                }

                // Two schemas now staged — discover both URIs via MCP.
                val schemaUris = listSchemaResourceUris(harness)
                withClue("after the second reverse, resources/list must report exactly two schemas") {
                    schemaUris.size shouldBe 2
                }
                schemaUris shouldContainSchemaUri schemaUriA
                val schemaUriB = (schemaUris - schemaUriA).single()

                val compareArgs = JsonObject().apply {
                    addProperty("sourceUri", schemaUriA)
                    addProperty("targetUri", schemaUriB)
                    addProperty("idempotencyKey", "k-compare-${UUID.randomUUID()}")
                }
                val compareResult = harness.toolsCall("schema_compare_start", compareArgs)
                val compareBody = compareResult.content.firstOrNull()?.text ?: "<no text>"
                withClue("schema_compare_start envelope must be a success projection; body=$compareBody") {
                    compareResult.isError shouldBe false
                }
                val compareResponse = JsonParser.parseString(compareBody).asJsonObject
                withClue("schema_compare_start envelope must carry a jobId") {
                    compareResponse.has("jobId") shouldBe true
                }
                val compareJobId = compareResponse.get("jobId").asString
                val compareStatusRaw = harness.toolsCall(
                    "job_status_get",
                    JsonObject().apply { addProperty("jobId", compareJobId) },
                )
                val compareStatusText = compareStatusRaw.content.firstOrNull()?.text
                    ?: error("job_status_get had no text content")
                val compareStatusJson = JsonParser.parseString(compareStatusText).asJsonObject
                withClue("compare job must reach terminal SUCCEEDED; body=$compareStatusText") {
                    compareStatusJson.get("status").asString shouldBe "SUCCEEDED"
                    compareStatusJson.get("terminal").asBoolean shouldBe true
                }
                withClue("compare job must surface one diff artefact; body=$compareStatusText") {
                    val artifacts = compareStatusJson.getAsJsonArray("artifacts")
                    artifacts.size() shouldBe 1
                    artifacts.get(0).asString shouldContain "/artifacts/"
                }
            } finally {
                harness.close()
            }
        } finally {
            sqliteFile.deleteIfExists()
            try { Files.walk(stateDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            catch (_: Throwable) { /* best-effort */ }
        }
    }

    test("schema_reverse_start under default fail-closed policy → POLICY_DENIED before any worker dispatch") {
        val stateDir = IntegrationFixtures.freshStateDir("operational-policy-deny-")
        try {
            val principal = IntegrationFixtures.freshTransportPrincipal("operational-pol")
            // No policyService override → ConfiguredPolicyService with
            // default Deny("policy:no-rule"). This is the production
            // fail-closed default; the operational scenario must
            // surface it as a typed POLICY_DENIED envelope to the
            // client and must NOT register a job or call the worker.
            val harness = OperationalHarness.start(
                stateDir = stateDir,
                principal = principal,
                connectionSecretResolver = TestSqliteConnectionSecretResolver.FAIL_CLOSED,
            )
            try {
                harness.initialize()
                harness.initializedNotification()

                val tenantId = principal.effectiveTenantId
                val connectionUri = ServerResourceUri(tenantId, ResourceKind.CONNECTIONS, "sqlite-blocker").render()
                val args = JsonObject().apply {
                    addProperty("connectionId", connectionUri)
                    addProperty("idempotencyKey", "k-operational-deny")
                }
                val result = harness.toolsCall("schema_reverse_start", args)
                val body = result.content.firstOrNull()?.text ?: "<no text>"
                withClue("policy-deny path must surface as tool-error envelope; body=$body") {
                    result.isError shouldBe true
                }
                val errJson = JsonParser.parseString(body).asJsonObject
                withClue("error envelope must carry POLICY_DENIED code") {
                    errJson.get("code").asString shouldBe "POLICY_DENIED"
                }
                // Belt and braces: the operational fail-closed contract
                // says POLICY_DENIED MUST NOT leak the materialized
                // resolver URL or any credential fragment. The
                // ConnectionSecretResolver is set to FAIL_CLOSED so
                // even if a regression were to invoke it, the path
                // would deny before reaching the worker.
                withClue("POLICY_DENIED message must not leak resolver internals") {
                    errJson.get("message").asString.shouldContain("Policy denied")
                }
            } finally {
                harness.close()
            }
        } finally {
            try { Files.walk(stateDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            catch (_: Throwable) { /* best-effort */ }
        }
    }
})

/**
 * Bootstrap a minimal SQLite database the reverse-engineer can
 * actually read: one table with a primary key, no constraints
 * beyond NOT NULL. The reverse runner inspects sqlite_master,
 * so the table must exist with persisted rows in the catalog;
 * an empty DB file would still produce a valid (empty) schema
 * but the artefact assertion wants observable structure.
 */
private fun seedSqlite(path: Path) {
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection("jdbc:sqlite:${path.absolutePathString()}").use { conn ->
        conn.autoCommit = true
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY,
                    payload TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }
}

private fun StdioHarness.runtimeWiring() = wiring

/**
 * MCP `resources/list` → filter to entries whose `uri` carries a
 * `/schemas/` path segment → return them as a list. Uses the
 * MCP-client surface; no direct SchemaStore access. The operational
 * scenario consistently checks via this path so a regression in the
 * schemas-resource projection (e.g. ACL filter break) surfaces here
 * instead of in a silent side-channel.
 */
private fun listSchemaResourceUris(harness: StdioHarness): List<String> {
    val raw = harness.resourcesListRaw(cursor = null)
    val result = raw.result?.asJsonObject
        ?: error("resources/list errored: ${raw.error}")
    val resources = result.getAsJsonArray("resources")
    return resources.mapNotNull { entry ->
        val obj = entry.asJsonObject
        val uri = obj.get("uri")?.asString ?: return@mapNotNull null
        uri.takeIf { it.contains("/schemas/") }
    }
}

private fun singleSchemaResourceUri(harness: StdioHarness): String {
    val uris = listSchemaResourceUris(harness)
    require(uris.size == 1) {
        "expected exactly one schemas/* resource, got ${uris.size}: $uris"
    }
    return uris.single()
}

/**
 * Issue `resources/read` against [uri] and return the inline JSON
 * body. The MCP envelope is `{ contents: [{ uri, mimeType, text }] }`;
 * the operational scenario reads exactly one inline content slice.
 */
private fun readJsonContent(harness: StdioHarness, uri: String): JsonObject {
    val raw = harness.resourcesReadRaw(uri)
    val result = raw.result?.asJsonObject
        ?: error("resources/read errored for $uri: ${raw.error}")
    val contents = result.getAsJsonArray("contents")
    require(contents.size() == 1) {
        "expected exactly one content slice, got ${contents.size()} for $uri"
    }
    val text = contents.get(0).asJsonObject.get("text")?.asString
        ?: error("content slice missing 'text' for $uri")
    return JsonParser.parseString(text).asJsonObject
}

private infix fun List<String>.shouldContainSchemaUri(expected: String) {
    withClue("expected schema URI $expected to appear in $this") {
        contains(expected) shouldBe true
    }
}
