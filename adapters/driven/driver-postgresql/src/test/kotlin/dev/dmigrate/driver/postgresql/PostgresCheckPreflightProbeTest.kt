package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.CheckPreflightStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

class PostgresCheckPreflightProbeTest : FunSpec({

    val planner = DiffPlanner()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun diffWith(constraints: List<ConstraintDefinition>): dev.dmigrate.core.diff.migration.DiffResult {
        val diff = SchemaDiff(
            tablesChanged = constraints.map {
                TableDiff(name = "users", constraintsAdded = listOf(it))
            },
        )
        return planner.plan(emptySchema(), emptySchema(), diff)
    }

    fun connectionReturning(counts: Map<String, Long>): Connection {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } answers {
            val sql = firstArg<String>()
            val count = counts.entries.firstOrNull { (key, _) -> key in sql }?.value ?: 0L
            val rs = mockk<ResultSet>(relaxed = true)
            every { rs.next() } returnsMany listOf(true, false)
            every { rs.getLong(1) } returns count
            rs
        }
        return conn
    }

    fun connectionThrowing(message: String): Connection {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } throws SQLException(message)
        return conn
    }

    val ageCheck = ConstraintDefinition(
        name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0",
    )
    val emailCheck = ConstraintDefinition(
        name = "chk_email_lc", type = ConstraintType.CHECK, expression = "email = lower(email)",
    )

    test("no CHECK Add ops in the diff → empty declarations") {
        PostgresCheckPreflightProbe.probe(connectionReturning(emptyMap()), diffWith(emptyList())).shouldBeEmpty()
    }

    test("CHECK with zero violations → status PASSED, no failingRows") {
        val decls = PostgresCheckPreflightProbe.probe(connectionReturning(mapOf("age >= 0" to 0L)), diffWith(listOf(ageCheck)))
        decls shouldHaveSize 1
        val d = decls.single()
        d.status shouldBe CheckPreflightStatus.PASSED
        d.failingRows shouldBe null
        d.constraintName shouldBe "chk_age"
        d.table shouldBe "users"
        d.dialect shouldBe "postgresql"
    }

    test("CHECK with non-zero violations → status FAILED, failingRows populated") {
        val decls = PostgresCheckPreflightProbe.probe(connectionReturning(mapOf("age >= 0" to 12L)), diffWith(listOf(ageCheck)))
        decls.single().status shouldBe CheckPreflightStatus.FAILED
        decls.single().failingRows shouldBe 12L
    }

    test("multiple CHECK Adds produce one declaration each, status independent") {
        val decls = PostgresCheckPreflightProbe.probe(
            connectionReturning(mapOf("age >= 0" to 0L, "email = lower(email)" to 4L)),
            diffWith(listOf(ageCheck, emailCheck)),
        )
        decls shouldHaveSize 2
        decls.single { it.constraintName == "chk_age" }.status shouldBe CheckPreflightStatus.PASSED
        decls.single { it.constraintName == "chk_email_lc" }.status shouldBe CheckPreflightStatus.FAILED
        decls.single { it.constraintName == "chk_email_lc" }.failingRows shouldBe 4L
    }

    test("SQLException during probe → status PROBE_RUNTIME_ERROR with message in problem") {
        val decls = PostgresCheckPreflightProbe.probe(
            connectionThrowing("relation \"users\" does not exist"),
            diffWith(listOf(ageCheck)),
        )
        val d = decls.single()
        d.status shouldBe CheckPreflightStatus.PROBE_RUNTIME_ERROR
        d.problem shouldContain "users"
    }

    test("identifier quoting uses double quotes (PG idiom) so the planner / probe agree") {
        val decls = PostgresCheckPreflightProbe.probe(
            connectionReturning(mapOf("FROM \"users\"" to 0L)),
            diffWith(listOf(ageCheck)),
        )
        decls.single().status shouldBe CheckPreflightStatus.PASSED
    }
})
