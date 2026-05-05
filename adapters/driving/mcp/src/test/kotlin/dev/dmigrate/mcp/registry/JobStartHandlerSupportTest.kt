package dev.dmigrate.mcp.registry

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.job.JobStartHandlerOutcome
import dev.dmigrate.server.application.job.JobStartInputValidation
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class JobStartHandlerSupportTest : FunSpec({

    val tenant = TenantId("acme")
    val now = Instant.parse("2026-05-05T12:00:00Z")
    val gson = Gson()

    test("toJsonValue: null und JsonNull -> JsonValue.Null") {
        JobStartHandlerSupport.toJsonValue(null) shouldBe JsonValue.Null
        JobStartHandlerSupport.toJsonValue(JsonNull.INSTANCE) shouldBe JsonValue.Null
    }

    test("toJsonValue: Primitives") {
        JobStartHandlerSupport.toJsonValue(gson.toJsonTree(true)) shouldBe JsonValue.Bool(true)
        JobStartHandlerSupport.toJsonValue(gson.toJsonTree(42)) shouldBe JsonValue.Num(42)
        JobStartHandlerSupport.toJsonValue(gson.toJsonTree("hello")) shouldBe JsonValue.Str("hello")
    }

    test("toJsonValue: Array bewahrt Reihenfolge") {
        val arr = gson.toJsonTree(listOf("a", "b", "c"))
        val v = JobStartHandlerSupport.toJsonValue(arr) as JsonValue.Arr
        v.items shouldBe listOf(JsonValue.Str("a"), JsonValue.Str("b"), JsonValue.Str("c"))
    }

    test("toJsonValue: Object rekursiv") {
        val obj = JsonObject().apply {
            addProperty("connectionId", "c1")
            addProperty("idempotencyKey", "k1")
            add("nested", JsonObject().apply { addProperty("x", 7) })
        }
        val v = JobStartHandlerSupport.toJsonValue(obj) as JsonValue.Obj
        v.fields["connectionId"] shouldBe JsonValue.Str("c1")
        v.fields["idempotencyKey"] shouldBe JsonValue.Str("k1")
        (v.fields["nested"] as JsonValue.Obj).fields["x"] shouldBe JsonValue.Num(7)
    }

    test("toJsonValueObj: Convenience-Wrapper liefert Obj") {
        val obj = JsonObject().apply { addProperty("x", "y") }
        val v = JobStartHandlerSupport.toJsonValueObj(obj)
        v.fields["x"] shouldBe JsonValue.Str("y")
    }

    test("optStringArray: null wenn Feld fehlt") {
        JobStartHandlerSupport.optStringArray(JsonObject(), "includes") shouldBe null
    }

    test("optStringArray: liest Array") {
        val obj = gson.toJsonTree(mapOf("includes" to listOf("a", "b"))).asJsonObject
        JobStartHandlerSupport.optStringArray(obj, "includes") shouldBe listOf("a", "b")
    }

    test("optStringArray: nicht-Array -> ValidationErrorException") {
        val obj = JsonObject().apply { addProperty("includes", "not-array") }
        shouldThrow<ValidationErrorException> {
            JobStartHandlerSupport.optStringArray(obj, "includes")
        }
    }

    test("optStringArray: Array mit nicht-string-item -> ValidationErrorException") {
        val obj = gson.toJsonTree(mapOf("includes" to listOf(1, 2))).asJsonObject
        shouldThrow<ValidationErrorException> {
            JobStartHandlerSupport.optStringArray(obj, "includes")
        }
    }

    test("jobResourceUri rendert mit Tenant + JobId") {
        val uri = JobStartHandlerSupport.jobResourceUri(tenant, "j1")
        uri shouldBe "dmigrate://tenants/acme/jobs/j1"
    }

    test("toToolCallOutcome: Started -> Success-Envelope mit jobId/resourceUri/executionMeta") {
        val outcome = JobStartHandlerOutcome.Started(
            jobId = "j-1",
            record = mockJobRecord("j-1"),
            cancellationSource = dev.dmigrate.core.cancel.CancellationTokenSource.create(),
        )
        val result = JobStartHandlerSupport.toToolCallOutcome(outcome, tenant, "req-x")
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val text = result.content.first().text!!
        text shouldContain "\"jobId\":\"j-1\""
        text shouldContain "\"resourceUri\":\"dmigrate://tenants/acme/jobs/j-1\""
        text shouldContain "\"requestId\":\"req-x\""
    }

    test("toToolCallOutcome: AlreadyStarted -> Success mit demselben Shape") {
        val result = JobStartHandlerSupport.toToolCallOutcome(
            JobStartHandlerOutcome.AlreadyStarted("j-existing"), tenant, "req-y",
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
        result.content.first().text!! shouldContain "\"jobId\":\"j-existing\""
    }

    test("toToolCallOutcome: PolicyRequired -> Error-Envelope POLICY_REQUIRED mit Challenge") {
        val outcome = JobStartHandlerOutcome.PolicyRequired(
            approvalRequestId = "req-1",
            correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
            correlationKey = "k1",
            requiredScopes = setOf("data.read", "schema.write"),
            reasons = listOf("sensitivity:pii"),
        )
        val result = JobStartHandlerSupport.toToolCallOutcome(outcome, tenant, "req-z")
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.POLICY_REQUIRED
        val keyed = result.envelope.details.associate { it.key to it.value }
        keyed["approvalRequestId"] shouldBe "req-1"
        keyed["correlationKind"] shouldBe "IDEMPOTENCY_KEY"
        keyed["correlationKey"] shouldBe "k1"
        keyed["requiredScopes"] shouldBe "data.read,schema.write"
        keyed["reasons"] shouldBe "sensitivity:pii"
        result.envelope.requestId shouldBe "req-z"
    }

    test("toToolCallOutcome: PolicyDenied -> wirft PolicyDeniedException") {
        shouldThrow<PolicyDeniedException> {
            JobStartHandlerSupport.toToolCallOutcome(
                JobStartHandlerOutcome.PolicyDenied("policy:tool-blocked", now), tenant, "r",
            )
        }
    }

    test("toToolCallOutcome: IdempotencyConflict -> wirft IdempotencyConflictException") {
        shouldThrow<IdempotencyConflictException> {
            JobStartHandlerSupport.toToolCallOutcome(
                JobStartHandlerOutcome.IdempotencyConflict("fp-existing"), tenant, "r",
            )
        }
    }

    test("toToolCallOutcome: ValidationError(IdempotencyKeyMissing) -> ValidationErrorException(idempotencyKey)") {
        val ex = shouldThrow<ValidationErrorException> {
            JobStartHandlerSupport.toToolCallOutcome(
                JobStartHandlerOutcome.ValidationError(JobStartInputValidation.Invalid.IdempotencyKeyMissing),
                tenant, "r",
            )
        }
        ex.violations.first().field shouldBe "idempotencyKey"
    }

    test("toToolCallOutcome: ValidationError(FreeJdbcUrl) -> ValidationErrorException mit Feldname") {
        val ex = shouldThrow<ValidationErrorException> {
            JobStartHandlerSupport.toToolCallOutcome(
                JobStartHandlerOutcome.ValidationError(
                    JobStartInputValidation.Invalid.FreeJdbcUrl("connectionId"),
                ), tenant, "r",
            )
        }
        ex.violations.first().field shouldBe "connectionId"
        ex.violations.first().reason shouldContain "free JDBC"
    }

    test("toToolCallOutcome: ValidationError(InvalidRefSyntax/WrongRefKind/TenantPrefixMismatch)") {
        // InvalidRefSyntax
        shouldThrow<ValidationErrorException> {
            JobStartHandlerSupport.toToolCallOutcome(
                JobStartHandlerOutcome.ValidationError(
                    JobStartInputValidation.Invalid.InvalidRefSyntax("connectionId", "x", "bad"),
                ), tenant, "r",
            )
        }
        // WrongRefKind
        shouldThrow<ValidationErrorException> {
            JobStartHandlerSupport.toToolCallOutcome(
                JobStartHandlerOutcome.ValidationError(
                    JobStartInputValidation.Invalid.WrongRefKind(
                        "connectionId", ResourceKind.CONNECTIONS, ResourceKind.JOBS,
                    ),
                ), tenant, "r",
            )
        }
        // TenantPrefixMismatch
        shouldThrow<ValidationErrorException> {
            JobStartHandlerSupport.toToolCallOutcome(
                JobStartHandlerOutcome.ValidationError(
                    JobStartInputValidation.Invalid.TenantPrefixMismatch(
                        "connectionId", tenant, TenantId("other"),
                    ),
                ), tenant, "r",
            )
        }
    }

    test("toToolCallOutcome: Pending -> Error-Envelope OPERATION_TIMEOUT") {
        val outcome = JobStartHandlerOutcome.Pending(now)
        val result = JobStartHandlerSupport.toToolCallOutcome(outcome, tenant, "r")
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.OPERATION_TIMEOUT
    }

    test("toToolCallOutcome: Failed mit policy:-Reason -> POLICY_DENIED-Envelope (Review-Fix #6)") {
        val outcome = JobStartHandlerOutcome.Failed("policy:not-awaiting", now)
        val result = JobStartHandlerSupport.toToolCallOutcome(outcome, tenant, "r")
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.POLICY_DENIED
        val keyed = result.envelope.details.associate { it.key to it.value }
        keyed["reason"] shouldBe "policy:not-awaiting"
    }

    test("toToolCallOutcome: Failed mit validation:-Reason -> VALIDATION_ERROR-Envelope") {
        val outcome = JobStartHandlerOutcome.Failed("validation:bad-payload", now)
        val result = JobStartHandlerSupport.toToolCallOutcome(outcome, tenant, "r")
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.VALIDATION_ERROR
    }

    test("toToolCallOutcome: Failed mit unbekanntem Reason-Praefix -> INTERNAL_AGENT_ERROR (catch-all)") {
        val outcome = JobStartHandlerOutcome.Failed("unknown:reason-x", now)
        val result = JobStartHandlerSupport.toToolCallOutcome(outcome, tenant, "r")
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.INTERNAL_AGENT_ERROR
    }

    test("toToolCallOutcome: Failed mit Hyphen-Separator policy-denied -> POLICY_DENIED (Re-Review B1)") {
        // Bestands-Code emittiert auch "policy-denied", "validation-error"
        // statt der Phase-E-`:`-Konvention. Alle Schreibweisen muessen auf
        // den korrekten Wire-Code mappen, sonst wuerden legitime
        // Validierungs-/Policy-Fehler hinter INTERNAL_AGENT_ERROR
        // versteckt.
        val outcome = JobStartHandlerOutcome.Failed("policy-denied", now)
        val result = JobStartHandlerSupport.toToolCallOutcome(outcome, tenant, "r")
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.POLICY_DENIED
    }

    test("toToolCallOutcome: Failed mit validation-error / validation_failed -> VALIDATION_ERROR") {
        listOf("validation-error", "validation_failed", "validation:bad-payload").forEach { reason ->
            val outcome = JobStartHandlerOutcome.Failed(reason, now)
            val result = JobStartHandlerSupport.toToolCallOutcome(outcome, tenant, "r")
            result.shouldBeInstanceOf<ToolCallOutcome.Error>()
            result.envelope.code shouldBe ToolErrorCode.VALIDATION_ERROR
        }
    }

    test("toToolCallOutcome: RateLimited -> RATE_LIMITED-Envelope mit retryAfter/current/limit") {
        val outcome = JobStartHandlerOutcome.RateLimited(
            retryAfter = java.time.Duration.ofSeconds(45),
            current = 3L,
            limit = 3L,
        )
        val result = JobStartHandlerSupport.toToolCallOutcome(outcome, tenant, "req-rl")
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.RATE_LIMITED
        val keyed = result.envelope.details.associate { it.key to it.value }
        keyed["retryAfter"] shouldBe "45"
        keyed["current"] shouldBe "3"
        keyed["limit"] shouldBe "3"
    }
})

private fun mockJobRecord(jobId: String): dev.dmigrate.server.core.job.JobRecord =
    dev.dmigrate.server.ports.contract.Fixtures.jobRecord(jobId)
