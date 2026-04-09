package dev.dmigrate.core.data

import io.kotest.matchers.shouldBe
import io.kotest.matchers.throwable.shouldHaveMessage
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.core.spec.style.FunSpec

class ImportSchemaMismatchExceptionTest : FunSpec({

    test("carries message") {
        val ex = ImportSchemaMismatchException("column 'userId' has no exact match")
        ex shouldHaveMessage "column 'userId' has no exact match"
        ex.cause shouldBe null
    }

    test("chains cause") {
        val root = IllegalArgumentException("bad value")
        val ex = ImportSchemaMismatchException("row 42", cause = root)
        ex.cause shouldBe root
    }

    test("is a RuntimeException") {
        val ex = ImportSchemaMismatchException("x")
        ex.shouldBeInstanceOf<RuntimeException>()
    }
})
