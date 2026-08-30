package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedFunction
import dev.dmigrate.core.diff.NamedProcedure
import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Der kanonische Key traegt die Identitaet im Modell (`tabelle::name`,
 * `name(in:typ)`) und darf nicht als Bezeichner in die DDL geraten. Diese Specs
 * bauen den `objectRef` so, wie die Reverse-Reader ihn erzeugen — mit dem Key,
 * nicht mit dem blanken Namen.
 */
class PostgresDiffCanonicalKeyTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    val users = TableDefinition(
        columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer, required = true)),
    )
    val trigger = TriggerDefinition(
        table = "users",
        event = TriggerEvent.UPDATE,
        timing = TriggerTiming.BEFORE,
        forEach = TriggerForEach.ROW,
        body = "touch_updated_at()",
    )
    val fn = FunctionDefinition(
        parameters = listOf(ParameterDefinition("p_id", "integer")),
        returns = ReturnType("integer"),
        body = "BEGIN RETURN 1; END;",
        language = "plpgsql",
        sourceDialect = "postgresql",
    )
    val proc = ProcedureDefinition(
        parameters = listOf(ParameterDefinition("p_id", "integer")),
        body = "BEGIN END;",
        language = "plpgsql",
        sourceDialect = "postgresql",
    )

    val empty = SchemaDefinition(name = "App", version = "1")
    val desired = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf("users" to users),
        functions = mapOf("calc(in:integer)" to fn),
        procedures = mapOf("touch(in:integer)" to proc),
        triggers = mapOf("users::last_updated" to trigger),
    )
    val diff = SchemaDiff(
        functionsAdded = listOf(NamedFunction("calc(in:integer)", fn)),
        proceduresAdded = listOf(NamedProcedure("touch(in:integer)", proc)),
        triggersAdded = listOf(NamedTrigger("users::last_updated", trigger)),
    )

    fun sqlOf(up: Boolean): String {
        val plan = planner.plan(empty, desired, diff)
        val result = if (up) gen.generateUp(plan, DdlGenerationOptions()) else gen.generateDown(plan, DdlGenerationOptions())
        return result.statements.joinToString("\n") { it.sql }
    }

    test("Create emits the bare name, never the canonical key") {
        val sql = sqlOf(up = true)
        sql shouldContain "CREATE TRIGGER \"last_updated\""
        sql shouldContain "CREATE FUNCTION \"calc\"(p_id integer)"
        sql shouldContain "CREATE PROCEDURE \"touch\"(p_id integer)"
        sql shouldNotContain "users::last_updated"
        sql shouldNotContain "calc(in:integer)"
        sql shouldNotContain "touch(in:integer)"
    }

    // Das DROP wiegt schwerer als das CREATE: es soll ein Objekt entfernen, das
    // in der Datenbank unter dem blanken Namen steht.
    test("Drop emits the bare name, never the canonical key") {
        val sql = sqlOf(up = false)
        sql shouldContain "DROP TRIGGER \"last_updated\" ON \"users\";"
        sql shouldContain "DROP FUNCTION \"calc\"(integer);"
        sql shouldContain "DROP PROCEDURE \"touch\"(integer);"
        sql shouldNotContain "users::last_updated"
        sql shouldNotContain "calc(in:integer)"
        sql shouldNotContain "touch(in:integer)"
    }
})
