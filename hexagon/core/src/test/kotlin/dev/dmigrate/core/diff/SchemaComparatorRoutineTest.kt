package dev.dmigrate.core.diff

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * E.1 Routine-Migration Slice A: routine identity pins. The
 * comparator uses normalised body hashes — so cosmetic body
 * variations (CRLF, trailing semicolon, trailing whitespace) do NOT
 * trigger a ReplaceFunction. Signature / security / definer /
 * searchPath / sqlMode are part of routine identity and DO trigger
 * Replace, even when the body hash is unchanged.
 */
class SchemaComparatorRoutineTest : FunSpec({

    val comparator = SchemaComparator()

    fun schemaWith(functions: Map<String, FunctionDefinition>) = SchemaDefinition(
        name = "Test",
        version = "1.0",
        functions = functions,
    )

    fun schemaWithProcs(procedures: Map<String, ProcedureDefinition>) = SchemaDefinition(
        name = "Test",
        version = "1.0",
        procedures = procedures,
    )

    test("cosmetic body changes (CRLF, trailing semicolon, whitespace) do NOT trigger ReplaceFunction") {
        val before = FunctionDefinition(body = "BEGIN\n  RETURN 1;\nEND", language = "plpgsql")
        val after = FunctionDefinition(body = "BEGIN\r\n  RETURN 1;\r\nEND;  \n", language = "plpgsql")
        val diff = comparator.compare(schemaWith(mapOf("f" to before)), schemaWith(mapOf("f" to after)))
        diff.functionsChanged.shouldBeEmpty()
    }

    test("real body change triggers ReplaceFunction") {
        val before = FunctionDefinition(body = "BEGIN RETURN 1; END", language = "plpgsql")
        val after = FunctionDefinition(body = "BEGIN RETURN 2; END", language = "plpgsql")
        val diff = comparator.compare(schemaWith(mapOf("f" to before)), schemaWith(mapOf("f" to after)))
        diff.functionsChanged.shouldHaveSize(1)
        diff.functionsChanged.single().body.shouldNotBeNull()
    }

    test("security flip alone triggers ReplaceFunction even when body hashes match") {
        val before = FunctionDefinition(
            body = "BEGIN RETURN 1; END",
            language = "plpgsql",
            security = RoutineSecurity.INVOKER,
        )
        val after = before.copy(security = RoutineSecurity.DEFINER)
        val diff = comparator.compare(schemaWith(mapOf("f" to before)), schemaWith(mapOf("f" to after)))
        diff.functionsChanged.shouldHaveSize(1)
        diff.functionsChanged.single().security.shouldNotBeNull()
        // The body did NOT change.
        diff.functionsChanged.single().body shouldBe null
    }

    test("definer change alone triggers ReplaceFunction") {
        val before = FunctionDefinition(
            body = "BEGIN RETURN 1; END",
            language = "plpgsql",
            security = RoutineSecurity.DEFINER,
            definer = "svc_app",
        )
        val after = before.copy(definer = "svc_app_v2")
        val diff = comparator.compare(schemaWith(mapOf("f" to before)), schemaWith(mapOf("f" to after)))
        diff.functionsChanged.shouldHaveSize(1)
        diff.functionsChanged.single().definer.shouldNotBeNull()
    }

    test("search_path change alone triggers ReplaceFunction") {
        val before = FunctionDefinition(
            body = "BEGIN RETURN 1; END",
            language = "plpgsql",
            searchPath = listOf("public"),
        )
        val after = before.copy(searchPath = listOf("public", "audit"))
        val diff = comparator.compare(schemaWith(mapOf("f" to before)), schemaWith(mapOf("f" to after)))
        diff.functionsChanged.shouldHaveSize(1)
        diff.functionsChanged.single().searchPath.shouldNotBeNull()
    }

    test("sql_mode change alone triggers ReplaceFunction (MySQL-style)") {
        val before = FunctionDefinition(
            body = "BEGIN RETURN 1; END",
            language = "plpgsql",
            sqlMode = "STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION",
        )
        val after = before.copy(sqlMode = "STRICT_TRANS_TABLES")
        val diff = comparator.compare(schemaWith(mapOf("f" to before)), schemaWith(mapOf("f" to after)))
        diff.functionsChanged.shouldHaveSize(1)
        diff.functionsChanged.single().sqlMode.shouldNotBeNull()
    }

    // ── Slice B: procedure identity pins ──

    test("Slice B: cosmetic procedure body changes do NOT trigger ReplaceProcedure") {
        val before = ProcedureDefinition(body = "BEGIN\n  CALL log('x');\nEND", language = "plpgsql")
        val after = ProcedureDefinition(body = "BEGIN\r\n  CALL log('x');\r\nEND;  \n", language = "plpgsql")
        val diff = comparator.compare(schemaWithProcs(mapOf("p" to before)), schemaWithProcs(mapOf("p" to after)))
        diff.proceduresChanged.shouldBeEmpty()
    }

    test("Slice B: real procedure body change triggers ReplaceProcedure") {
        val before = ProcedureDefinition(body = "BEGIN CALL log('x'); END", language = "plpgsql")
        val after = ProcedureDefinition(body = "BEGIN CALL log('y'); END", language = "plpgsql")
        val diff = comparator.compare(schemaWithProcs(mapOf("p" to before)), schemaWithProcs(mapOf("p" to after)))
        diff.proceduresChanged.shouldHaveSize(1)
        diff.proceduresChanged.single().body.shouldNotBeNull()
    }

    test("Slice B: procedure security flip alone triggers ReplaceProcedure") {
        val before = ProcedureDefinition(
            body = "BEGIN END",
            language = "plpgsql",
            security = RoutineSecurity.INVOKER,
        )
        val after = before.copy(security = RoutineSecurity.DEFINER)
        val diff = comparator.compare(schemaWithProcs(mapOf("p" to before)), schemaWithProcs(mapOf("p" to after)))
        diff.proceduresChanged.shouldHaveSize(1)
        diff.proceduresChanged.single().security.shouldNotBeNull()
        diff.proceduresChanged.single().body shouldBe null
    }

    test("Slice B: procedure definer change alone triggers ReplaceProcedure") {
        val before = ProcedureDefinition(
            body = "BEGIN END",
            language = "plpgsql",
            security = RoutineSecurity.DEFINER,
            definer = "svc_app",
        )
        val after = before.copy(definer = "svc_app_v2")
        val diff = comparator.compare(schemaWithProcs(mapOf("p" to before)), schemaWithProcs(mapOf("p" to after)))
        diff.proceduresChanged.shouldHaveSize(1)
        diff.proceduresChanged.single().definer.shouldNotBeNull()
    }

    test("Slice B: procedure search_path change alone triggers ReplaceProcedure") {
        val before = ProcedureDefinition(
            body = "BEGIN END",
            language = "plpgsql",
            searchPath = listOf("public"),
        )
        val after = before.copy(searchPath = listOf("public", "audit"))
        val diff = comparator.compare(schemaWithProcs(mapOf("p" to before)), schemaWithProcs(mapOf("p" to after)))
        diff.proceduresChanged.shouldHaveSize(1)
        diff.proceduresChanged.single().searchPath.shouldNotBeNull()
    }

    test("Slice B: procedure sql_mode change alone triggers ReplaceProcedure (MySQL-style)") {
        val before = ProcedureDefinition(
            body = "BEGIN END",
            language = "plpgsql",
            sqlMode = "STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION",
        )
        val after = before.copy(sqlMode = "STRICT_TRANS_TABLES")
        val diff = comparator.compare(schemaWithProcs(mapOf("p" to before)), schemaWithProcs(mapOf("p" to after)))
        diff.proceduresChanged.shouldHaveSize(1)
        diff.proceduresChanged.single().sqlMode.shouldNotBeNull()
    }
})
