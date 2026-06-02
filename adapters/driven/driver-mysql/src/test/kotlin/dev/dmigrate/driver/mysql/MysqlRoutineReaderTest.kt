package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class MysqlRoutineReaderTest : FunSpec({

    val jdbc = mockk<JdbcOperations>()
    val reader = MysqlRoutineReader()

    fun stubRoutineParameters() {
        every {
            jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any(), any())
        } returns emptyList()
    }

    // ── readFunctions: identity attributes ─────────

    test("readFunctions populates security / definer / sqlMode from row map") {
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf(
                "routine_name" to "my_func",
                "routine_type" to "FUNCTION",
                "data_type" to "int",
                "dtd_identifier" to "int",
                "routine_definition" to "RETURN 1;",
                "is_deterministic" to "YES",
                "routine_body" to "SQL",
                "security_type" to "DEFINER",
                "definer" to "'alice'@'%'",
                "sql_mode" to "STRICT_ALL_TABLES,NO_ZERO_DATE",
            ),
        )
        stubRoutineParameters()
        val result = reader.readFunctions(jdbc, "mydb")
        result.size shouldBe 1
        val fn = result.values.single()
        fn.security shouldBe RoutineSecurity.DEFINER
        fn.definer shouldBe "'alice'@'%'"
        fn.sqlMode shouldBe "STRICT_ALL_TABLES,NO_ZERO_DATE"
    }

    test("readFunctions maps INVOKER security_type") {
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf(
                "routine_name" to "f", "routine_type" to "FUNCTION",
                "data_type" to "int", "dtd_identifier" to "int",
                "routine_definition" to "RETURN 0;", "is_deterministic" to "NO",
                "routine_body" to "SQL",
                "security_type" to "INVOKER",
                "definer" to "'bob'@'localhost'",
                "sql_mode" to "",
            ),
        )
        stubRoutineParameters()
        val fn = reader.readFunctions(jdbc, "mydb").values.single()
        fn.security shouldBe RoutineSecurity.INVOKER
        fn.definer shouldBe "'bob'@'localhost'"
        // empty sql_mode is normalised to null so a file-side with
        // omitted sql_mode does not flag as spurious replace.
        fn.sqlMode.shouldBeNull()
    }

    test("readFunctions leaves identity fields null when columns are missing or blank") {
        // Defensive against older MySQL versions or restricted views
        // where information_schema.routines returns a subset of columns
        // (or nulls). Reader must not produce a spurious diff in that case.
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf(
                "routine_name" to "f", "routine_type" to "FUNCTION",
                "data_type" to "int", "dtd_identifier" to "int",
                "routine_definition" to "RETURN 0;", "is_deterministic" to "NO",
                "routine_body" to "SQL",
                "security_type" to null, "definer" to null, "sql_mode" to null,
            ),
        )
        stubRoutineParameters()
        val fn = reader.readFunctions(jdbc, "mydb").values.single()
        fn.security.shouldBeNull()
        fn.definer.shouldBeNull()
        fn.sqlMode.shouldBeNull()
    }

    // ── readProcedures: identity attributes ────────

    test("readProcedures populates security / definer / sqlMode from row map") {
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns listOf(
            mapOf(
                "routine_name" to "my_proc",
                "routine_type" to "PROCEDURE",
                "routine_definition" to "BEGIN END;",
                "routine_body" to "SQL",
                "security_type" to "DEFINER",
                "definer" to "'alice'@'%'",
                "sql_mode" to "ANSI_QUOTES",
            ),
        )
        stubRoutineParameters()
        val proc = reader.readProcedures(jdbc, "mydb").values.single()
        proc.security shouldBe RoutineSecurity.DEFINER
        proc.definer shouldBe "'alice'@'%'"
        proc.sqlMode shouldBe "ANSI_QUOTES"
    }
})
