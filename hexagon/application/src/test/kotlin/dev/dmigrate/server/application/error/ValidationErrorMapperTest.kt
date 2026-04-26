package dev.dmigrate.server.application.error

import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ValidationErrorMapperTest : FunSpec({

    val mapper = DefaultErrorMapper()

    test("each violation becomes one ToolErrorDetail (field, reason)") {
        val ex = ValidationErrorException(
            listOf(
                ValidationViolation("email", "must not be blank"),
                ValidationViolation("age", "must be >= 0"),
            ),
        )
        val env = mapper.map(ex)
        env.code shouldBe ToolErrorCode.VALIDATION_ERROR
        env.details shouldContainExactly listOf(
            ToolErrorDetail("email", "must not be blank"),
            ToolErrorDetail("age", "must be >= 0"),
        )
    }

    test("violation insertion order is preserved") {
        val violations = (1..5).map { ValidationViolation("field-$it", "reason-$it") }
        val env = mapper.map(ValidationErrorException(violations))
        env.details shouldContainExactly violations.map { ToolErrorDetail(it.field, it.reason) }
    }

    test("empty violation list produces empty details") {
        val env = mapper.map(ValidationErrorException(emptyList()))
        env.code shouldBe ToolErrorCode.VALIDATION_ERROR
        env.details shouldBe emptyList()
    }

    test("default message reports the violation count") {
        val ex = ValidationErrorException(
            listOf(
                ValidationViolation("a", "x"),
                ValidationViolation("b", "y"),
                ValidationViolation("c", "z"),
            ),
        )
        ex.message shouldBe "Validation failed: 3 violation(s)"
    }

    test("duplicate field names in violations are kept as separate details") {
        val ex = ValidationErrorException(
            listOf(
                ValidationViolation("password", "too short"),
                ValidationViolation("password", "missing digit"),
            ),
        )
        val env = mapper.map(ex)
        env.details shouldContainExactly listOf(
            ToolErrorDetail("password", "too short"),
            ToolErrorDetail("password", "missing digit"),
        )
    }
})
