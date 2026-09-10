package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class OraclePostApplyStatusProbeTest : FunSpec({

    fun createFunction(name: String) = DiffOperation.CreateFunction(
        id = "create:$name",
        objectRef = DiffObjectRef(DiffObjectType.FUNCTION, listOf(name)),
        function = FunctionDefinition(returns = ReturnType(type = "integer"), body = "BEGIN RETURN 1; END;"),
    )

    fun createTable(name: String) = DiffOperation.CreateTable(
        id = "create:$name",
        objectRef = DiffObjectRef(DiffObjectType.TABLE, listOf(name)),
        table = TableDefinition(columns = emptyMap()),
    )

    fun planOf(vararg ops: DiffOperation) = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = ops.toList(),
    )

    test("gefragt wird ALL_ERRORS, nicht der Status — INVALID allein ist mehrdeutig") {
        val sql = slot<String>()
        val jdbc = mockk<JdbcOperations> {
            every { queryList(capture(sql), *anyVararg()) } returns emptyList()
        }

        OraclePostApplyStatusProbe.probe(jdbc, planOf(createFunction("f")))

        sql.captured shouldContain "FROM all_errors"
        sql.captured shouldContain "attribute = 'ERROR'"
        // Der Besitzer-Filter: ein Aufrufer mit weiterreichenden Rechten darf
        // nicht die kaputten Objekte fremder Schemas als eigene melden.
        sql.captured shouldContain "SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')"
    }

    test("gefragt wird nur nach den Objekten, die dieser Lauf anfasst") {
        val params = mutableListOf<Any?>()
        val jdbc = mockk<JdbcOperations> {
            every { queryList(any(), *varargAll { params.add(it); true }) } returns emptyList()
        }

        OraclePostApplyStatusProbe.probe(jdbc, planOf(createFunction("f_a"), createFunction("f_b")))

        params shouldBe listOf("f_a", "f_b")
    }

    test("die Namen gehen buchstabengetreu hinaus — der Generator schreibt sie in Anfuehrungszeichen") {
        // Hochgestellt faende die Abfrage die Objekte nicht wieder: der
        // Katalog fuehrt sie so, wie sie im Schema stehen.
        val params = mutableListOf<Any?>()
        val jdbc = mockk<JdbcOperations> {
            every { queryList(any(), *varargAll { params.add(it); true }) } returns emptyList()
        }

        OraclePostApplyStatusProbe.probe(jdbc, planOf(createFunction("gemischt_Gross")))

        params shouldBe listOf("gemischt_Gross")
    }

    test("ein Plan ohne uebersetzbare Objekte fragt gar nicht erst nach") {
        val jdbc = mockk<JdbcOperations>()

        OraclePostApplyStatusProbe.probe(jdbc, planOf(createTable("t"))).shouldBeEmpty()

        verify(exactly = 0) { jdbc.queryList(any(), *anyVararg()) }
    }

    test("jede Fehlerzeile wird zu einem Befund mit Fundstelle") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(any(), *anyVararg()) } returns listOf(
                mapOf(
                    "name" to "f", "type" to "FUNCTION", "line" to 1, "position" to 49,
                    "text" to "PLS-00201: identifier 'KEIN_BEZEICHNER' must be declared\n",
                ),
            )
        }

        val found = OraclePostApplyStatusProbe.probe(jdbc, planOf(createFunction("f"))).single()

        found.name shouldBe "f"
        found.objectType shouldBe "FUNCTION"
        found.line shouldBe 1
        found.position shouldBe 49
        found.message shouldBe "PLS-00201: identifier 'KEIN_BEZEICHNER' must be declared"
        found.describe() shouldContain "FUNCTION f (Zeile 1, Spalte 49)"
    }
})
