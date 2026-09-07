package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DatabaseDialect
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

/**
 * Die Ausfuehrungs- und Fehlerbehandlungsschleife der CHECK-Preflight-Sonde.
 *
 * Sie stand einmal fuenfmal zeichengleich in den Treibermodulen, und jede
 * Fassung hatte nur ihren eigenen Dialekt-Test: eine Aenderung mitzuziehen
 * und eine Fassung zu vergessen fiel nirgends auf. Jetzt gibt es eine
 * Schleife — und diese Spezifikation ist ihr Test.
 */
class JdbcCheckPreflightProbeTest : FunSpec({

    fun diffWith(vararg constraints: Pair<String, String?>) = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = constraints.mapIndexed { index, (name, expression) ->
            DiffOperation.AddConstraint(
                id = "op-$index",
                objectRef = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("orders", name)),
                constraint = ConstraintDefinition(
                    name = name,
                    type = ConstraintType.CHECK,
                    expression = expression,
                ),
            )
        },
    )

    fun connectionReturning(vararg answers: Any): Connection {
        var call = -1
        val statement = mockk<Statement>(relaxUnitFun = true)
        every { statement.executeQuery(any()) } answers {
            when (val answer = answers[++call]) {
                is SQLException -> throw answer
                else -> mockk<ResultSet>(relaxUnitFun = true) {
                    every { next() } returns true
                    every { getLong(1) } returns answer as Long
                }
            }
        }
        return mockk<Connection>(relaxUnitFun = true) { every { createStatement() } returns statement }
    }

    test("a CHECK the existing rows satisfy passes") {
        val result = JdbcCheckPreflightProbe.probe(
            DatabaseDialect.POSTGRESQL, connectionReturning(0L), diffWith("ck_amount" to "amount > 0"),
        )

        result.single().status shouldBe CheckPreflightStatus.PASSED
        result.single().failingRows shouldBe null
        result.single().dialect shouldBe "postgresql"
    }

    test("violating rows are counted, not just reported as a failure") {
        // Die Zahl ist die Auskunft, die dem Anwender die Arbeit abnimmt:
        // ohne sie wuesste er nur, DASS der Constraint nicht haelt.
        val result = JdbcCheckPreflightProbe.probe(
            DatabaseDialect.MSSQL, connectionReturning(7L), diffWith("ck_amount" to "amount > 0"),
        )

        result.single().status shouldBe CheckPreflightStatus.FAILED
        result.single().failingRows shouldBe 7L
    }

    test("a failing probe query is a status, not an exception") {
        // Sonst braeche der ganze Lauf an einer LESENDEN Vorabfrage ab.
        val result = JdbcCheckPreflightProbe.probe(
            DatabaseDialect.ORACLE,
            connectionReturning(SQLException("ORA-00942: table or view does not exist")),
            diffWith("ck_amount" to "amount > 0"),
        )

        result.single().status shouldBe CheckPreflightStatus.PROBE_RUNTIME_ERROR
        result.single().problem!! shouldContain "ORA-00942"
    }

    test("one broken probe does not take the others with it") {
        val result = JdbcCheckPreflightProbe.probe(
            DatabaseDialect.MYSQL,
            connectionReturning(0L, SQLException("boom"), 3L),
            diffWith("a" to "x > 0", "b" to "y > 0", "c" to "z > 0"),
        )

        result.map { it.status } shouldBe listOf(
            CheckPreflightStatus.PASSED,
            CheckPreflightStatus.PROBE_RUNTIME_ERROR,
            CheckPreflightStatus.FAILED,
        )
    }

    test("the dialect steers the quoting the planner hashes over") {
        // Weicht das Quoting hier von dem des Planers ab, findet der Renderer
        // die passende Deklaration am Emissionspunkt nicht mehr wieder.
        val postgres = JdbcCheckPreflightProbe.probe(
            DatabaseDialect.POSTGRESQL, connectionReturning(0L), diffWith("ck" to "amount > 0"),
        ).single()
        val mysql = JdbcCheckPreflightProbe.probe(
            DatabaseDialect.MYSQL, connectionReturning(0L), diffWith("ck" to "amount > 0"),
        ).single()

        postgres.sqlHash shouldBe postgres.sqlHash
        (postgres.sqlHash == mysql.sqlHash) shouldBe false
    }

    test("a constraint without an expression is nothing to probe") {
        JdbcCheckPreflightProbe.probe(
            DatabaseDialect.SQLITE, connectionReturning(), diffWith("ck" to null),
        ).shouldBeEmpty()
    }
})
