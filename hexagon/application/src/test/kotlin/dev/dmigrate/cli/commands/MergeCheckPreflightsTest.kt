package dev.dmigrate.cli.commands

import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.CheckPreflightStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class MergeCheckPreflightsTest : FunSpec({

    fun decl(
        operationId: String = "op",
        table: String = "users",
        constraintName: String = "chk_age",
        sqlHash: String = "h",
        status: CheckPreflightStatus = CheckPreflightStatus.PASSED,
        problem: String? = null,
    ) = CheckPreflightDeclaration(
        operationId = operationId,
        dialect = "postgresql",
        table = table,
        constraintName = constraintName,
        expression = "age >= 0",
        status = status,
        sqlHash = sqlHash,
        problem = problem,
    )

    test("both empty → empty") {
        mergeCheckPreflights(emptyList(), emptyList()).shouldBeEmpty()
    }

    test("up only → up unchanged") {
        val up = listOf(decl(operationId = "a"), decl(operationId = "b"))
        mergeCheckPreflights(up, emptyList()) shouldBe up
    }

    test("down only → down as-is") {
        val down = listOf(decl(operationId = "x"))
        mergeCheckPreflights(emptyList(), down) shouldBe down
    }

    test("identical bindingKeys → up wins, down dropped") {
        val u = decl(operationId = "a", status = CheckPreflightStatus.PASSED)
        val d = decl(operationId = "a", status = CheckPreflightStatus.FAILED) // same binding-key
        val merged = mergeCheckPreflights(listOf(u), listOf(d))
        merged shouldHaveSize 1
        merged.single().status shouldBe CheckPreflightStatus.PASSED
    }

    test("disjoint bindingKeys (different operationId) → both kept, up first") {
        val u = decl(operationId = "a")
        val d = decl(operationId = "b")
        val merged = mergeCheckPreflights(listOf(u), listOf(d))
        merged.map { it.operationId } shouldBe listOf("a", "b")
    }

    test("disjoint bindingKeys (different sqlHash) → both kept") {
        val u = decl(sqlHash = "h1")
        val d = decl(sqlHash = "h2")
        mergeCheckPreflights(listOf(u), listOf(d)) shouldHaveSize 2
    }

    test("disjoint bindingKeys (different table) → both kept") {
        val u = decl(table = "users")
        val d = decl(table = "orders")
        mergeCheckPreflights(listOf(u), listOf(d)) shouldHaveSize 2
    }

    test("partial overlap → up entries first, then down extras, ordering deterministic") {
        val u1 = decl(operationId = "a")
        val u2 = decl(operationId = "b")
        val d1 = decl(operationId = "a") // duplicate of u1
        val d2 = decl(operationId = "c") // extra
        val merged = mergeCheckPreflights(listOf(u1, u2), listOf(d1, d2))
        merged.map { it.operationId } shouldBe listOf("a", "b", "c")
    }

    test("duplicates within down itself are deduped by the seen-set") {
        val u = decl(operationId = "a")
        val d1 = decl(operationId = "b")
        val d2 = decl(operationId = "b") // duplicate within down
        val merged = mergeCheckPreflights(listOf(u), listOf(d1, d2))
        merged.map { it.operationId } shouldBe listOf("a", "b")
    }
})
