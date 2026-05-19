package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.CheckPreflightStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

class MysqlCheckPreflightProbeTest : FunSpec({

    val planner = DiffPlanner()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun diffWith(constraints: List<ConstraintDefinition>) =
        planner.plan(
            emptySchema(), emptySchema(),
            SchemaDiff(
                tablesChanged = constraints.map { TableDiff(name = "users", constraintsAdded = listOf(it)) },
            ),
        )

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

    val ageCheck = ConstraintDefinition(name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0")

    test("no CHECK Adds → empty declarations") {
        MysqlCheckPreflightProbe.probe(connectionReturning(emptyMap()), diffWith(emptyList())).shouldBeEmpty()
    }

    test("CHECK with zero violations → PASSED") {
        val d = MysqlCheckPreflightProbe.probe(
            connectionReturning(mapOf("age >= 0" to 0L)),
            diffWith(listOf(ageCheck)),
        ).single()
        d.status shouldBe CheckPreflightStatus.PASSED
        d.failingRows shouldBe null
        d.dialect shouldBe "mysql"
    }

    test("CHECK with violations → FAILED, failingRows populated") {
        val d = MysqlCheckPreflightProbe.probe(
            connectionReturning(mapOf("age >= 0" to 3L)),
            diffWith(listOf(ageCheck)),
        ).single()
        d.status shouldBe CheckPreflightStatus.FAILED
        d.failingRows shouldBe 3L
    }

    test("identifier quoting uses backticks (MySQL idiom)") {
        val d = MysqlCheckPreflightProbe.probe(
            connectionReturning(mapOf("FROM `users`" to 0L)),
            diffWith(listOf(ageCheck)),
        ).single()
        d.status shouldBe CheckPreflightStatus.PASSED
    }

    test("SQLException → PROBE_RUNTIME_ERROR with message in problem") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } throws SQLException("Table 'app.users' doesn't exist")
        val d = MysqlCheckPreflightProbe.probe(conn, diffWith(listOf(ageCheck))).single()
        d.status shouldBe CheckPreflightStatus.PROBE_RUNTIME_ERROR
        d.problem shouldContain "users"
    }
})
