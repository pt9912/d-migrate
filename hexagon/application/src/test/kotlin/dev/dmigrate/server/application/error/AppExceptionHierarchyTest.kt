package dev.dmigrate.server.application.error

import dev.dmigrate.server.core.error.ToolErrorCode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pins the §6.7 invariant: every [ToolErrorCode] has exactly one
 * [ApplicationException] subtype, and that subtype's `code` matches.
 * Adding a new code without a subtype, or vice versa, fails this test.
 */
class AppExceptionHierarchyTest : FunSpec({

    val cases = ApplicationExceptionFixtures.cases

    test("every ToolErrorCode is represented exactly once") {
        cases.size shouldBe ToolErrorCode.entries.size
        val codes = cases.map { it.code }.toSet()
        codes shouldBe ToolErrorCode.entries.toSet()
    }

    test("each subtype's code matches its fixture key") {
        cases.forEach { case ->
            case.exception.code shouldBe case.code
        }
    }

    test("each subtype is an ApplicationException and a RuntimeException") {
        cases.forEach { case ->
            case.exception.shouldBeInstanceOf<ApplicationException>()
            case.exception.shouldBeInstanceOf<RuntimeException>()
        }
    }

    test("each subtype carries a non-blank default message") {
        cases.forEach { case ->
            (case.exception.message?.isNotBlank() ?: false) shouldBe true
        }
    }
})
