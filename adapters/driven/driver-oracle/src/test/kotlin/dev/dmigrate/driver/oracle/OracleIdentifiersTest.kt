package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class OracleIdentifiersTest : FunSpec({

    test("currentSchema reads SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')") {
        val session = mockk<JdbcOperations> {
            every { querySingle(match { it.contains("SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')") }) } returns
                mapOf("schema_name" to "SALES")
        }
        OracleIdentifiers.currentSchema(session) shouldBe "SALES"
    }

    test("currentSchema fails loud when the probe yields nothing") {
        val session = mockk<JdbcOperations> {
            every { querySingle(any()) } returns null
        }
        shouldThrow<IllegalStateException> {
            OracleIdentifiers.currentSchema(session)
        }.message shouldBe "could not resolve current Oracle schema via SYS_CONTEXT"
    }

    test("quote double-quotes identifiers") {
        OracleIdentifiers.quote("users") shouldBe "\"users\""
    }
})
