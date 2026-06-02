package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.SchemaDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * E.1 Routine-Migration Slice A: pin that the new routine identity
 * attributes (`security`, `definer`, `searchPath`, `sqlMode`)
 * survive a `build → parse` roundtrip on both
 * [FunctionDefinition] and [ProcedureDefinition]. Without this
 * test a regression in the codec would silently drop the attrs and
 * the comparator would see a "no-change" schema where the operator
 * supplied SECURITY DEFINER.
 */
class SchemaNodeProgrammabilityRoutineRoundtripTest : FunSpec({

    val mapper = ObjectMapper()

    fun roundtrip(schema: SchemaDefinition): SchemaDefinition {
        val node = SchemaNodeBuilder.build(mapper, schema)
        return SchemaNodeParser.parse(node)
    }

    test("FunctionDefinition roundtrip preserves security/definer/searchPath/sqlMode") {
        val fn = FunctionDefinition(
            parameters = listOf(ParameterDefinition("amount", "numeric")),
            returns = ReturnType("numeric"),
            language = "plpgsql",
            body = "BEGIN RETURN amount * 1.2; END",
            security = RoutineSecurity.DEFINER,
            definer = "svc_app",
            searchPath = listOf("public", "audit"),
            sqlMode = "STRICT_TRANS_TABLES",
        )
        val schema = SchemaDefinition(name = "App", version = "1", functions = mapOf("calc" to fn))
        val parsed = roundtrip(schema).functions["calc"]!!
        parsed.security shouldBe RoutineSecurity.DEFINER
        parsed.definer shouldBe "svc_app"
        parsed.searchPath shouldContainExactly listOf("public", "audit")
        parsed.sqlMode shouldBe "STRICT_TRANS_TABLES"
        // Existing fields still survive.
        parsed.language shouldBe "plpgsql"
        parsed.body shouldBe "BEGIN RETURN amount * 1.2; END"
    }

    test("ProcedureDefinition roundtrip preserves security/definer/searchPath/sqlMode") {
        val proc = ProcedureDefinition(
            parameters = listOf(ParameterDefinition("id_in", "integer")),
            language = "sql",
            body = "BEGIN END",
            security = RoutineSecurity.INVOKER,
            definer = "owner_role",
            searchPath = listOf("public"),
            sqlMode = "ANSI",
        )
        val schema = SchemaDefinition(name = "App", version = "1", procedures = mapOf("p" to proc))
        val parsed = roundtrip(schema).procedures["p"]!!
        parsed.security shouldBe RoutineSecurity.INVOKER
        parsed.definer shouldBe "owner_role"
        parsed.searchPath shouldContainExactly listOf("public")
        parsed.sqlMode shouldBe "ANSI"
    }

    test("absent identity attrs roundtrip as null (terse legacy schema files keep their shape)") {
        val fn = FunctionDefinition(
            parameters = emptyList(),
            language = "sql",
            body = "RETURN 1",
        )
        val schema = SchemaDefinition(name = "App", version = "1", functions = mapOf("f" to fn))
        val parsed = roundtrip(schema).functions["f"]!!
        parsed.security shouldBe null
        parsed.definer shouldBe null
        parsed.searchPath shouldBe null
        parsed.sqlMode shouldBe null
    }
})
