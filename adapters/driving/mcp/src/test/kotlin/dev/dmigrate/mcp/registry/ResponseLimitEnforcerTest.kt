package dev.dmigrate.mcp.registry

import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorEnvelope
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant

private val ACME_TENANT = TenantId("acme")
private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = ACME_TENANT,
    effectiveTenantId = ACME_TENANT,
    allowedTenantIds = setOf(ACME_TENANT),
    scopes = setOf("dmigrate:read", "dmigrate:artifact:upload"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private class EnforcerFixture(
    val sut: ResponseLimitEnforcer,
    val artifactStore: InMemoryArtifactStore,
    val contentStore: InMemoryArtifactContentStore,
)

private fun enforcer(limits: McpLimitsConfig = McpLimitsConfig()): EnforcerFixture {
    val artifactStore = InMemoryArtifactStore()
    val contentStore = InMemoryArtifactContentStore()
    val sink = ArtifactSink(artifactStore, contentStore, Clock.systemUTC())
    return EnforcerFixture(ResponseLimitEnforcer(limits, sink), artifactStore, contentStore)
}

class ResponseLimitEnforcerTest : FunSpec({

    context("enforceRequestSize") {
        test("small payload passes through for non-upload tools") {
            val sut = enforcer().sut
            sut.enforceRequestSize("schema_validate", JsonParser.parseString("""{"schema":{}}"""))
        }

        test("oversized request for a non-upload tool throws PAYLOAD_TOO_LARGE with the limit") {
            val sut = enforcer(McpLimitsConfig(maxNonUploadToolRequestBytes = 32)).sut
            val big = "x".repeat(64)
            val ex = shouldThrow<PayloadTooLargeException> {
                sut.enforceRequestSize(
                    "schema_validate",
                    JsonParser.parseString("""{"schema":"$big"}"""),
                )
            }
            ex.maxBytes shouldBe 32L
            ex.actualBytes shouldBe (
                """{"schema":"$big"}""".toByteArray(Charsets.UTF_8).size.toLong()
                )
        }

        test("artifact_upload uses the upload-specific (larger) limit") {
            // Same payload size that would fail for a read-only tool
            // succeeds for the upload tool because the cap is higher.
            val limits = McpLimitsConfig(
                maxNonUploadToolRequestBytes = 32,
                maxUploadToolRequestBytes = 4096,
            )
            val sut = enforcer(limits).sut
            val big = "x".repeat(64)
            sut.enforceRequestSize(
                "artifact_upload",
                JsonParser.parseString("""{"contentBase64":"$big"}"""),
            )
            // Same payload would fail for any other tool.
            shouldThrow<PayloadTooLargeException> {
                sut.enforceRequestSize(
                    "schema_validate",
                    JsonParser.parseString("""{"schema":"$big"}"""),
                )
            }
        }

        test("null arguments are treated as zero bytes") {
            val sut = enforcer(McpLimitsConfig(maxNonUploadToolRequestBytes = 1)).sut
            sut.enforceRequestSize("schema_validate", null)
        }
    }

    context("enforceResponseSize") {
        test("small response passes through unchanged") {
            val sut = enforcer().sut
            val original = ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(type = "text", text = """{"ok":true}""", mimeType = "application/json"),
                ),
            )
            val result = sut.enforceResponseSize("schema_validate", PRINCIPAL, original)
            result shouldBe original
        }

        test("oversized success response is moved to an artefact with a truncated envelope (non-schema-aware tool)") {
            // AP 6.23: schema-aware tools (schema_validate etc.) are
            // never wrapped by the generic envelope; this test uses a
            // tool that has no per-output schema (artifact_chunk_get).
            val limits = McpLimitsConfig(maxToolResponseBytes = 64)
            val f = enforcer(limits)
            val bigText = """{"data":"${"x".repeat(200)}"}"""
            val outcome = ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(type = "text", text = bigText, mimeType = "application/json"),
                ),
            )
            val replaced = f.sut.enforceResponseSize("artifact_chunk_get", PRINCIPAL, outcome)
            val text = (replaced as ToolCallOutcome.Success).content.single().text!!
            val json = JsonParser.parseString(text).asJsonObject
            json.get("truncated").asBoolean shouldBe true
            json.get("summary").asString shouldContain "exceeded"
            val artifactRef = json.get("artifactRef").asString
            artifactRef shouldContain "/artifacts/"
            val artifactId = artifactRef.substringAfterLast("/")
            val record = f.artifactStore.findById(ACME_TENANT, artifactId)!!
            val stored = f.contentStore.openRangeRead(artifactId, 0L, record.managedArtifact.sizeBytes)
                .use { stream -> stream.readBytes() }
            String(stored, Charsets.UTF_8) shouldBe bigText
        }

        test("AP 6.23: oversized response from a schema-aware tool surfaces INTERNAL_AGENT_ERROR") {
            // Plan §6.23: the generic {summary, artifactRef, truncated}
            // envelope does not match the per-tool output schemas of
            // schema_validate / schema_compare / schema_generate /
            // job_status_get. An oversize response from one of these
            // tools is a handler-side bug — the handler's own
            // truncated/artifactRef path should have downgraded it.
            val limits = McpLimitsConfig(maxToolResponseBytes = 32)
            val f = enforcer(limits)
            val bigText = """{"valid":true,"summary":"x","findings":[],"truncated":false,"junk":"${"x".repeat(200)}"}"""
            val outcome = ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(type = "text", text = bigText, mimeType = "application/json"),
                ),
            )
            for (tool in setOf("schema_validate", "schema_compare", "schema_generate", "job_status_get")) {
                shouldThrow<dev.dmigrate.server.application.error.InternalAgentErrorException> {
                    f.sut.enforceResponseSize(tool, PRINCIPAL, outcome)
                }
            }
        }

        test("error outcomes pass through without enforcement") {
            val sut = enforcer(McpLimitsConfig(maxToolResponseBytes = 16)).sut
            val envelope = ToolErrorEnvelope(
                code = ToolErrorCode.VALIDATION_ERROR,
                message = "x".repeat(200),
                details = emptyList(),
                requestId = null,
            )
            val original = ToolCallOutcome.Error(envelope)
            sut.enforceResponseSize("schema_validate", PRINCIPAL, original) shouldBe original
        }

        test("multi-content (or non-text) success outcomes pass through unchanged") {
            // Forward-compat hedge: every Phase-C handler today emits
            // a single-text frame, so this branch is dead in production.
            // We pin the pass-through anyway so a future multi-frame
            // handler doesn't trip the enforcer on a payload it can't
            // measure with a single byte count.
            val sut = enforcer(McpLimitsConfig(maxToolResponseBytes = 8)).sut
            val outcome = ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(type = "text", text = "first frame", mimeType = "application/json"),
                    ToolContent(type = "text", text = "second frame", mimeType = "application/json"),
                ),
            )
            sut.enforceResponseSize("schema_validate", PRINCIPAL, outcome) shouldBe outcome
        }

        test("response that doesn't even fit the spill cap surfaces INTERNAL_AGENT_ERROR (not PAYLOAD_TOO_LARGE)") {
            // The artefact spill itself can trip maxArtifactUploadBytes
            // when the response is *that* large. The client sent a
            // perfectly-sized request, so the typed envelope must
            // discriminate this from a request-side overflow:
            // INTERNAL_AGENT_ERROR signals "operator must raise the
            // spill cap" while PAYLOAD_TOO_LARGE would mislead the
            // client into thinking they have a request fix.
            val limits = McpLimitsConfig(
                maxToolResponseBytes = 16,
                maxArtifactUploadBytes = 32,
            )
            val sut = enforcer(limits).sut
            val outcome = ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(
                        type = "text",
                        text = """{"data":"${"x".repeat(200)}"}""",
                        mimeType = "application/json",
                    ),
                ),
            )
            val ex = shouldThrow<dev.dmigrate.server.application.error.InternalAgentErrorException> {
                // AP 6.23: use a non-schema-aware tool so the spill
                // path runs (schema-aware tools short-circuit to
                // InternalAgentError without a PayloadTooLarge cause).
                sut.enforceResponseSize("artifact_chunk_get", PRINCIPAL, outcome)
            }
            // Cause-chained for diagnostics (operator can grep the
            // actual byte mismatch out of the chain).
            ex.cause.shouldBeInstanceOf<PayloadTooLargeException>()
        }
    }

})
