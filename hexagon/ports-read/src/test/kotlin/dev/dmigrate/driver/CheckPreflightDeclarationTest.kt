package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CheckPreflightDeclarationTest : FunSpec({

    fun decl(
        operationId: String = "op-1",
        dialect: String = "postgresql",
        table: String = "users",
        constraintName: String = "chk_age",
        expression: String = "age >= 0",
        sqlHash: String = "deadbeef",
        status: CheckPreflightStatus = CheckPreflightStatus.NOT_RUN_FILE_TARGET,
    ) = CheckPreflightDeclaration(
        operationId = operationId,
        dialect = dialect,
        table = table,
        constraintName = constraintName,
        expression = expression,
        status = status,
        sqlHash = sqlHash,
    )

    test("bindingKey is stable for identical inputs") {
        decl().bindingKey shouldBe decl().bindingKey
    }

    test("bindingKey differs when operationId differs") {
        decl(operationId = "op-1").bindingKey shouldNotBe decl(operationId = "op-2").bindingKey
    }

    test("bindingKey differs when sqlHash differs (expression change between plan and execute)") {
        decl(sqlHash = "aaa").bindingKey shouldNotBe decl(sqlHash = "bbb").bindingKey
    }

    test("bindingKey differs when table differs") {
        decl(table = "users").bindingKey shouldNotBe decl(table = "orders").bindingKey
    }

    test("bindingKey differs when constraintName differs") {
        decl(constraintName = "chk_a").bindingKey shouldNotBe decl(constraintName = "chk_b").bindingKey
    }

    test("bindingKey differs when dialect differs (same op id on different adapters)") {
        decl(dialect = "postgresql").bindingKey shouldNotBe decl(dialect = "mysql").bindingKey
    }

    test("bindingKey companion function matches the property") {
        val d = decl()
        d.bindingKey shouldBe CheckPreflightDeclaration.bindingKey(
            operationId = d.operationId,
            dialect = d.dialect,
            table = d.table,
            constraintName = d.constraintName,
            sqlHash = d.sqlHash,
        )
    }

    test("bindingKey field separator (Unit Separator) does not collide with identifier characters") {
        // Tables / constraint names cannot contain the ASCII Unit
        // Separator (), so even tables named with embedded
        // identifier characters can't be aliased.
        val a = decl(table = "users", constraintName = "chk_a")
        val b = decl(table = "users_chk_a", constraintName = "")
        a.bindingKey shouldNotBe b.bindingKey
    }

    test("status field carries the full enum range") {
        CheckPreflightStatus.values().toSet() shouldBe setOf(
            CheckPreflightStatus.PASSED,
            CheckPreflightStatus.FAILED,
            CheckPreflightStatus.NOT_RUN_FILE_TARGET,
            CheckPreflightStatus.NOT_RUN_POLICY,
            CheckPreflightStatus.PROBE_RUNTIME_ERROR,
        )
    }

    test("DdlGenerationOptions.checkPreflights defaults to empty") {
        DdlGenerationOptions().checkPreflights shouldBe emptyList()
    }

    test("DdlGenerationOptions.checkPreflights can be populated") {
        val d = decl(status = CheckPreflightStatus.PASSED)
        DdlGenerationOptions(checkPreflights = listOf(d)).checkPreflights shouldBe listOf(d)
    }
})
