package dev.dmigrate.server.core.ai

import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

/**
 * Init-block coverage for [AiToolOutcome] and [AiToolAcquireOutcome].
 * Both sealed types carry per-subtype validation (`require(...)`-throw)
 * that the "data carrier"-shaped kover excludes used to silence. We
 * pin each branch here so a regression to the validation contract is
 * caught at module-local test time, not in downstream MCP integration
 * tests.
 */
class AiToolOutcomeTest : FunSpec({

    val scope = AiToolScope(
        tenantId = TenantId("t-1"),
        callerId = PrincipalId("p-1"),
        toolName = "ai.test.tool",
        approvalKey = "ak-1",
    )
    val claim = AiToolClaimId("claim-1")
    val now = Instant.parse("2026-05-10T12:00:00Z")
    val later = now.plusSeconds(60)
    val fp64 = "a".repeat(64)
    val fp64b = "b".repeat(64)

    // ── AiToolOutcome.Pending ──

    test("Pending requires attemptCount >= 1") {
        AiToolOutcome.Pending(scope, fp64, claim, later, attemptCount = 1)
        AiToolOutcome.Pending(scope, fp64, claim, later, attemptCount = 5)
        shouldThrow<IllegalArgumentException> {
            AiToolOutcome.Pending(scope, fp64, claim, later, attemptCount = 0)
        }.message shouldContain "attemptCount must be >= 1"
        shouldThrow<IllegalArgumentException> {
            AiToolOutcome.Pending(scope, fp64, claim, later, attemptCount = -1)
        }
    }

    // ── AiToolOutcome.Succeeded ──

    fun succeeded(
        resultRef: String = "ar-1",
        outputFingerprint: String = fp64,
        providerName: String = "anthropic",
        model: String = "claude",
        providerRequestId: String? = "req-1",
        promptFingerprint: String? = null,
        modelVersion: String? = null,
    ) = AiToolOutcome.Succeeded(
        scope = scope,
        payloadFingerprint = fp64,
        resultRef = resultRef,
        outputFingerprint = outputFingerprint,
        providerName = providerName,
        model = model,
        providerRequestId = providerRequestId,
        committedAt = now,
        promptFingerprint = promptFingerprint,
        modelVersion = modelVersion,
    )

    test("Succeeded requires resultRef non-blank") {
        succeeded() // baseline ok
        shouldThrow<IllegalArgumentException> { succeeded(resultRef = "") }.message shouldContain "resultRef"
        shouldThrow<IllegalArgumentException> { succeeded(resultRef = "   ") }
    }

    test("Succeeded requires 64-char hex outputFingerprint") {
        shouldThrow<IllegalArgumentException> {
            succeeded(outputFingerprint = "short")
        }.message shouldContain "outputFingerprint"
        shouldThrow<IllegalArgumentException> { succeeded(outputFingerprint = "z".repeat(63)) }
    }

    test("Succeeded requires non-blank providerName / model") {
        shouldThrow<IllegalArgumentException> { succeeded(providerName = "") }
        shouldThrow<IllegalArgumentException> { succeeded(model = " ") }
    }

    test("Succeeded providerRequestId is non-blank-or-null") {
        succeeded(providerRequestId = null)        // ok
        succeeded(providerRequestId = "req-x")     // ok
        shouldThrow<IllegalArgumentException> { succeeded(providerRequestId = "") }
    }

    test("Succeeded promptFingerprint must be null or 64-char hex") {
        succeeded(promptFingerprint = null)         // ok
        succeeded(promptFingerprint = fp64b)        // ok
        shouldThrow<IllegalArgumentException> { succeeded(promptFingerprint = "abc") }
    }

    test("Succeeded modelVersion is non-blank-or-null") {
        succeeded(modelVersion = null)              // ok
        succeeded(modelVersion = "v3")              // ok
        shouldThrow<IllegalArgumentException> { succeeded(modelVersion = "") }
    }

    // ── AiToolOutcome.FailedTerminal ──

    fun failedTerminal(
        scrubbedMessage: String = "scrubbed",
        providerName: String? = null,
        model: String? = null,
        modelVersion: String? = null,
        providerRequestId: String? = null,
        promptFingerprint: String? = null,
    ) = AiToolOutcome.FailedTerminal(
        scope = scope,
        payloadFingerprint = fp64,
        toolErrorCode = ToolErrorCode.INTERNAL_AGENT_ERROR,
        scrubbedMessage = scrubbedMessage,
        committedAt = now,
        providerName = providerName,
        model = model,
        modelVersion = modelVersion,
        providerRequestId = providerRequestId,
        promptFingerprint = promptFingerprint,
    )

    test("FailedTerminal requires scrubbedMessage non-blank") {
        failedTerminal() // ok
        shouldThrow<IllegalArgumentException> { failedTerminal(scrubbedMessage = "") }
        shouldThrow<IllegalArgumentException> { failedTerminal(scrubbedMessage = "   ") }
    }

    test("FailedTerminal optional fields are non-blank-or-null") {
        failedTerminal(providerName = "x", model = "y", modelVersion = "v", providerRequestId = "r", promptFingerprint = fp64)
        shouldThrow<IllegalArgumentException> { failedTerminal(providerName = "") }
        shouldThrow<IllegalArgumentException> { failedTerminal(model = "") }
        shouldThrow<IllegalArgumentException> { failedTerminal(modelVersion = "") }
        shouldThrow<IllegalArgumentException> { failedTerminal(providerRequestId = "") }
    }

    test("FailedTerminal promptFingerprint must be null or 64-char hex") {
        failedTerminal(promptFingerprint = null)
        failedTerminal(promptFingerprint = fp64)
        shouldThrow<IllegalArgumentException> { failedTerminal(promptFingerprint = "short") }
    }

    // ── AiToolOutcome.FailedRetryable ──

    fun failedRetryable(
        scrubbedMessage: String = "transient",
        attemptCount: Int = 1,
        providerName: String? = null,
        model: String? = null,
        modelVersion: String? = null,
        providerRequestId: String? = null,
        promptFingerprint: String? = null,
        approvalRequestId: String? = null,
        correlationKey: String? = null,
    ) = AiToolOutcome.FailedRetryable(
        scope = scope,
        payloadFingerprint = fp64,
        toolErrorCode = ToolErrorCode.OPERATION_TIMEOUT,
        scrubbedMessage = scrubbedMessage,
        attemptCount = attemptCount,
        lastAttemptAt = now,
        providerName = providerName,
        model = model,
        modelVersion = modelVersion,
        providerRequestId = providerRequestId,
        promptFingerprint = promptFingerprint,
        approvalRequestId = approvalRequestId,
        correlationKey = correlationKey,
    )

    test("FailedRetryable requires scrubbedMessage non-blank") {
        failedRetryable()
        shouldThrow<IllegalArgumentException> { failedRetryable(scrubbedMessage = "") }
    }

    test("FailedRetryable requires attemptCount >= 1") {
        failedRetryable(attemptCount = 1)
        failedRetryable(attemptCount = 99)
        shouldThrow<IllegalArgumentException> { failedRetryable(attemptCount = 0) }
    }

    test("FailedRetryable optional nullable strings are non-blank-or-null") {
        failedRetryable(providerName = "x", model = "y", modelVersion = "v", providerRequestId = "r", promptFingerprint = fp64)
        shouldThrow<IllegalArgumentException> { failedRetryable(providerName = "") }
        shouldThrow<IllegalArgumentException> { failedRetryable(model = "") }
        shouldThrow<IllegalArgumentException> { failedRetryable(modelVersion = "") }
        shouldThrow<IllegalArgumentException> { failedRetryable(providerRequestId = "") }
    }

    test("FailedRetryable approvalRequestId / correlationKey are non-blank-or-null") {
        failedRetryable(approvalRequestId = null, correlationKey = null)
        failedRetryable(approvalRequestId = "ar-1", correlationKey = "ck-1")
        shouldThrow<IllegalArgumentException> { failedRetryable(approvalRequestId = "") }
        shouldThrow<IllegalArgumentException> { failedRetryable(correlationKey = "") }
    }

    test("FailedRetryable promptFingerprint must be null or 64-char hex") {
        failedRetryable(promptFingerprint = null)
        failedRetryable(promptFingerprint = fp64)
        shouldThrow<IllegalArgumentException> { failedRetryable(promptFingerprint = "x") }
    }

    // ── AiToolAcquireOutcome.Acquired ──

    test("Acquired requires attemptCount >= 1") {
        AiToolAcquireOutcome.Acquired(scope, claim, later, attemptCount = 1)
        AiToolAcquireOutcome.Acquired(scope, claim, later, attemptCount = 7)
        shouldThrow<IllegalArgumentException> {
            AiToolAcquireOutcome.Acquired(scope, claim, later, attemptCount = 0)
        }
    }

    // ── AiToolAcquireOutcome.Existing ──

    test("Existing requires terminal Succeeded or FailedTerminal — Pending or Retryable rejected") {
        val good = AiToolAcquireOutcome.Existing(scope, succeeded())
        good.outcome shouldBe succeeded()
        AiToolAcquireOutcome.Existing(scope, failedTerminal())
        shouldThrow<IllegalArgumentException> {
            AiToolAcquireOutcome.Existing(
                scope,
                AiToolOutcome.Pending(scope, fp64, claim, later, attemptCount = 1),
            )
        }.message shouldContain "Existing outcome must be Succeeded or FailedTerminal"
        shouldThrow<IllegalArgumentException> {
            AiToolAcquireOutcome.Existing(scope, failedRetryable())
        }
    }

    test("Existing requires the wrapped outcome's scope to match") {
        val otherScope = scope.copy(toolName = "ai.other")
        shouldThrow<IllegalArgumentException> {
            AiToolAcquireOutcome.Existing(otherScope, succeeded()) // succeeded uses original scope
        }.message shouldContain "outcome scope must match"
    }

    // ── AiToolAcquireOutcome.ExistingRetryable ──

    test("ExistingRetryable requires scope match") {
        AiToolAcquireOutcome.ExistingRetryable(scope, failedRetryable())
        val otherScope = scope.copy(toolName = "ai.other")
        shouldThrow<IllegalArgumentException> {
            AiToolAcquireOutcome.ExistingRetryable(otherScope, failedRetryable())
        }
    }

    // ── AiToolAcquireOutcome.InProgress / Conflict ──

    test("InProgress has no init validation — constructs fine") {
        val inP = AiToolAcquireOutcome.InProgress(scope, later)
        inP.scope shouldBe scope
    }

    test("Conflict requires existingFingerprint non-blank") {
        AiToolAcquireOutcome.Conflict(scope, "fp-1")
        shouldThrow<IllegalArgumentException> {
            AiToolAcquireOutcome.Conflict(scope, "")
        }
    }

    // ── AiToolScope / AiToolClaimId ──

    test("AiToolScope requires non-blank toolName + approvalKey") {
        AiToolScope(TenantId("t"), PrincipalId("p"), "tn", "ak")
        shouldThrow<IllegalArgumentException> { AiToolScope(TenantId("t"), PrincipalId("p"), "", "ak") }
        shouldThrow<IllegalArgumentException> { AiToolScope(TenantId("t"), PrincipalId("p"), "tn", "") }
    }

    test("AiToolClaimId requires non-blank value") {
        AiToolClaimId("c-1")
        shouldThrow<IllegalArgumentException> { AiToolClaimId("") }
        shouldThrow<IllegalArgumentException> { AiToolClaimId("   ") }
    }

    // ── AiWireArtifactKind / AiIntent constants surface ──

    test("AiWireArtifactKind.ALL contains every named constant") {
        AiWireArtifactKind.ALL shouldBe setOf(
            AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
            AiWireArtifactKind.PROCEDURE_TRANSFORM_OUTPUT,
            AiWireArtifactKind.TESTDATA_PLAN,
            AiWireArtifactKind.GENERATED_TESTDATA,
            AiWireArtifactKind.SEED_DATA_BUNDLE,
        )
    }

    test("AiIntent.ALL contains every named constant") {
        AiIntent.ALL shouldBe setOf(
            AiIntent.PROCEDURE_TRANSFORM_PLAN,
            AiIntent.PROCEDURE_TRANSFORM_EXECUTE,
            AiIntent.TESTDATA_PLAN,
            AiIntent.TESTDATA_EXECUTE,
        )
    }
})
