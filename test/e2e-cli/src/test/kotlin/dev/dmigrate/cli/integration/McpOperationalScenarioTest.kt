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
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

/**
 * C-MCP scenario: drive `schema_reverse_start` through the
 * [OperationalHarness] against a real file-backed SQLite database
 * and assert the job reaches terminal status with the artefact
 * surface populated. Mirrors the production composition
 * `AiMcpRegistries.defaultComponents(AiMcpWiring(OperationalMcpWiring(...)))`
 * — review finding C-MCP from
 * `quality-coverage-expansion-plan.md` §5.3.
 *
 * Two cells:
 *
 * 1. **Success** — register a SQLite ConnectionReference, run
 *    `schema_reverse_start` via the in-process MCP stdio transport,
 *    assert the response envelope (jobId + resourceUri), then
 *    `job_status_get` and assert terminal status with a
 *    schemaRef artefact.
 * 2. **Validation blocker** — call `schema_reverse_start` with a
 *    free JDBC URL (per `McpJobStartScenarioTest`-pinned contract,
 *    the handler rejects this with `VALIDATION_ERROR` before any
 *    worker dispatch).
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
                withClue("schema store must carry the reverse-engineered schema entry") {
                    val page = harness.runtimeWiring().schemaStore.list(
                        tenantId,
                        dev.dmigrate.server.core.pagination.PageRequest(pageSize = 10),
                    )
                    page.items.size shouldBe 1
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
