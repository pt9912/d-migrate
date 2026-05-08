package dev.dmigrate.server.application.error

import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail
import dev.dmigrate.server.core.principal.PrincipalId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class ErrorMapperTest : FunSpec({

    val mapper = DefaultErrorMapper()

    ApplicationExceptionFixtures.cases.forEach { case ->
        test("${case.code.name} maps with the expected details") {
            val env = mapper.map(case.exception)
            env.code shouldBe case.code
            env.details shouldContainExactly case.expectedDetails
        }
    }

    test("ForbiddenPrincipalException without reason omits the reason detail") {
        val env = mapper.map(ForbiddenPrincipalException(PrincipalId("alice")))
        env.details shouldContainExactly listOf(ToolErrorDetail("principalId", "alice"))
    }

    test("PolicyDeniedException without reason omits the reason detail") {
        val env = mapper.map(PolicyDeniedException("p1"))
        env.details shouldContainExactly listOf(ToolErrorDetail("policyName", "p1"))
    }

    test("unknown Throwable falls back to INTERNAL_AGENT_ERROR without cause data") {
        val env = mapper.map(IllegalArgumentException("sensitive internal state: secret-key=42"))
        env.code shouldBe ToolErrorCode.INTERNAL_AGENT_ERROR
        env.message shouldBe "Internal agent error"
        env.details shouldBe emptyList()
        env.message shouldNotContain "secret-key"
        env.message shouldNotContain "sensitive"
    }

    test("requestId is propagated to the envelope") {
        val env = mapper.map(AuthRequiredException(), requestId = "req-42")
        env.requestId shouldBe "req-42"
    }

    test("requestId is propagated on the INTERNAL_AGENT_ERROR fallback path too") {
        val env = mapper.map(RuntimeException("x"), requestId = "req-99")
        env.code shouldBe ToolErrorCode.INTERNAL_AGENT_ERROR
        env.requestId shouldBe "req-99"
    }

    test("ApplicationException is RuntimeException so it preserves cause for stack tracing") {
        val cause = IllegalStateException("internal")
        val ex = InternalAgentErrorException(cause)
        ex.shouldBeInstanceOf<RuntimeException>()
        ex.cause shouldBe cause
    }
})
