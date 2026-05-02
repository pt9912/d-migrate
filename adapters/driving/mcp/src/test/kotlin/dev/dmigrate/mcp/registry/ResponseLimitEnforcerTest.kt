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

private fun enforcer(
    limits: McpLimitsConfig = McpLimitsConfig(),
): Triple<ResponseLimitEnforcer, InMemoryArtifactStore, InMemoryArtifactContentStore> {
    val artifactStore = InMemoryArtifactStore()
    val contentStore = InMemoryArtifactContentStore()
    val sink = ArtifactSink(artifactStore, contentStore, Clock.systemUTC())
    return Triple(ResponseLimitEnforcer(limits, sink), artifactStore, contentStore)
}

class ResponseLimitEnforcerTest : FunSpec({

    context("enforceRequestSize") {
        test("small payload passes through for non-upload tools") {
            val (sut, _, _) = enforcer()
            sut.enforceRequestSize("schema_validate", JsonParser.parseString("""{"schema":{}}"""))
        }

        test("oversized request for a non-upload tool throws PAYLOAD_TOO_LARGE with the limit") {
            val (sut, _, _) = enforcer(McpLimitsConfig(maxNonUploadToolRequestBytes = 32))
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
            val (sut, _, _) = enforcer(limits)
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
            val (sut, _, _) = enforcer(McpLimitsConfig(maxNonUploadToolRequestBytes = 1))
            sut.enforceRequestSize("schema_validate", null)
        }
    }

    context("enforceResponseSize") {
        test("small response passes through unchanged") {
            val (sut, _, _) = enforcer()
            val original = ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(type = "text", text = """{"ok":true}""", mimeType = "application/json"),
                ),
            )
            val result = sut.enforceResponseSize("schema_validate", PRINCIPAL, original)
            result shouldBe original
        }

        test("oversized success response is moved to an artefact with a truncated envelope") {
            val limits = McpLimitsConfig(maxToolResponseBytes = 64)
            val (sut, artifactStore, contentStore) = enforcer(limits)
            val bigText = """{"data":"${"x".repeat(200)}"}"""
            val outcome = ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(type = "text", text = bigText, mimeType = "application/json"),
                ),
            )
            val replaced = sut.enforceResponseSize("schema_validate", PRINCIPAL, outcome)
            val text = (replaced as ToolCallOutcome.Success).content.single().text!!
            val json = JsonParser.parseString(text).asJsonObject
            json.get("truncated").asBoolean shouldBe true
            json.get("summary").asString shouldContain "exceeded"
            val artifactRef = json.get("artifactRef").asString
            artifactRef shouldContain "/artifacts/"
            // The persisted artefact contains the original oversized JSON.
            val artifactId = artifactRef.substringAfterLast("/")
            val record = artifactStore.findById(ACME_TENANT, artifactId)!!
            val stored = contentStore.openRangeRead(artifactId, 0L, record.managedArtifact.sizeBytes)
                .use { stream -> stream.readBytes() }
            String(stored, Charsets.UTF_8) shouldBe bigText
        }

        test("error outcomes pass through without enforcement") {
            val (sut, _, _) = enforcer(McpLimitsConfig(maxToolResponseBytes = 16))
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
            // The enforcer can only measure single-text content
            // payloads. A multi-frame response is left untouched so
            // the dispatcher's downstream layers can see it as-is.
            val (sut, _, _) = enforcer(McpLimitsConfig(maxToolResponseBytes = 8))
            val outcome = ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(type = "text", text = "first frame", mimeType = "application/json"),
                    ToolContent(type = "text", text = "second frame", mimeType = "application/json"),
                ),
            )
            sut.enforceResponseSize("schema_validate", PRINCIPAL, outcome) shouldBe outcome
        }

        test("oversized response that would breach maxArtifactUploadBytes surfaces PAYLOAD_TOO_LARGE") {
            // The artefact-fallback can itself trip the upload-bytes
            // limit. Make sure the failure mode is the structured
            // typed exception, not a swallow.
            val limits = McpLimitsConfig(
                maxToolResponseBytes = 16,
                maxArtifactUploadBytes = 32,
            )
            val (sut, _, _) = enforcer(limits)
            val outcome = ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(
                        type = "text",
                        text = """{"data":"${"x".repeat(200)}"}""",
                        mimeType = "application/json",
                    ),
                ),
            )
            shouldThrow<PayloadTooLargeException> {
                sut.enforceResponseSize("schema_validate", PRINCIPAL, outcome)
            }
        }
    }

})
