package dev.dmigrate.mcp.auth

import dev.dmigrate.server.core.principal.AuthSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking

class DisabledAuthValidatorTest : FunSpec({

    test("validate returns the anonymous-admin principal regardless of token") {
        val validator = DisabledAuthValidator()
        val result1 = runBlocking { validator.validate("any-token") }
        val result2 = runBlocking { validator.validate("totally-different-token") }
        result1.shouldBeInstanceOf<BearerValidationResult.Valid>()
        result2.shouldBeInstanceOf<BearerValidationResult.Valid>()
        result1.principal shouldBe DisabledAuthValidator.ANONYMOUS_PRINCIPAL
        result2.principal shouldBe DisabledAuthValidator.ANONYMOUS_PRINCIPAL
    }

    test("anonymous principal carries the dmigrate:admin scope and ANONYMOUS authSource") {
        val p = DisabledAuthValidator.ANONYMOUS_PRINCIPAL
        p.authSource shouldBe AuthSource.ANONYMOUS
        p.isAdmin shouldBe true
        p.scopes shouldBe setOf("dmigrate:admin")
        p.principalId.value shouldBe "anonymous"
    }
})
