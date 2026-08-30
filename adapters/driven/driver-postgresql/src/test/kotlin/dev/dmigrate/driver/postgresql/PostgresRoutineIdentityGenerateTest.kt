package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Die Identitaets-Angaben einer Routine gehoeren in die erzeugte DDL.
 *
 * Ein gepinnter `search_path` ist die uebliche Absicherung einer
 * SECURITY-DEFINER-Routine; faellt er beim Zurueckschreiben weg, entscheidet
 * wieder der Suchpfad des Aufrufers, welche Tabelle der Rumpf trifft.
 */
class PostgresRoutineIdentityGenerateTest : FunSpec({

    val generator = PostgresDdlGenerator()

    fun schema(
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
    ) = SchemaDefinition(name = "App", version = "1", functions = functions, procedures = procedures)

    test("a SECURITY DEFINER function keeps its pinned search_path") {
        val fn = FunctionDefinition(
            returns = ReturnType("integer"),
            body = "BEGIN RETURN 1; END;",
            language = "plpgsql",
            security = RoutineSecurity.DEFINER,
            searchPath = listOf("public", "pg_temp"),
            sourceDialect = "postgresql",
        )
        val ddl = generator.generate(schema(functions = mapOf("f()" to fn))).render()
        ddl shouldContain "SECURITY DEFINER"
        ddl shouldContain """SET search_path = "public", "pg_temp""""
    }

    test("a procedure carries the same attributes as a function") {
        val proc = ProcedureDefinition(
            body = "BEGIN END;",
            language = "plpgsql",
            security = RoutineSecurity.DEFINER,
            searchPath = listOf("app"),
            sourceDialect = "postgresql",
        )
        val ddl = generator.generate(schema(procedures = mapOf("p()" to proc))).render()
        ddl shouldContain "CREATE OR REPLACE PROCEDURE"
        ddl shouldContain "SECURITY DEFINER"
        ddl shouldContain """SET search_path = "app""""
    }

    test("a routine without the attributes renders none of them") {
        val fn = FunctionDefinition(
            returns = ReturnType("integer"), body = "BEGIN RETURN 1; END;",
            language = "sql", sourceDialect = "postgresql",
        )
        val ddl = generator.generate(schema(functions = mapOf("f()" to fn))).render()
        ddl shouldContain "CREATE OR REPLACE FUNCTION"
        ddl shouldNotContain "search_path"
        ddl shouldNotContain "SECURITY"
    }
})
