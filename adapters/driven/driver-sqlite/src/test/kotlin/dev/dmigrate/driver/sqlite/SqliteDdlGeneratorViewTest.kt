package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

class SqliteDdlGeneratorViewTest : FunSpec({

    val generator = SqliteDdlGenerator()

    fun schema(
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap()
    ) = SchemaDefinition(
        name = "test-schema",
        version = "1.0.0",
        customTypes = customTypes,
        tables = tables,
        sequences = sequences,
        views = views,
        functions = functions,
        procedures = procedures,
        triggers = triggers
    )

    test("view produces CREATE VIEW IF NOT EXISTS without OR REPLACE") {
        val s = schema(
            views = mapOf(
                "active_users" to ViewDefinition(
                    query = "SELECT * FROM users WHERE active = 1"
                )
            )
        )
        val result = generator.generate(s)
        val viewSql = result.statements
            .map { it.sql.trim() }
            .first { it.contains("VIEW") && !it.startsWith("--") }

        viewSql shouldStartWith "CREATE VIEW IF NOT EXISTS \"active_users\""
        viewSql shouldNotContain "OR REPLACE"
        viewSql shouldContain "SELECT * FROM users WHERE active = 1"
    }

    test("materialized view is created as regular VIEW with W103 warning") {
        val s = schema(
            views = mapOf(
                "stats_summary" to ViewDefinition(
                    query = "SELECT count(*) as cnt FROM events",
                    materialized = true
                )
            )
        )
        val result = generator.generate(s)
        val viewSql = result.statements
            .map { it.sql.trim() }
            .first { it.contains("VIEW") && !it.startsWith("--") }

        viewSql shouldStartWith "CREATE VIEW IF NOT EXISTS \"stats_summary\""
        viewSql shouldNotContain "MATERIALIZED"
        result.notes.any { it.code == "W103" && it.objectName == "stats_summary" } shouldBe true
    }

    test("view with incompatible source dialect is transformed and generated") {
        val s = schema(
            views = mapOf(
                "pg_view" to ViewDefinition(
                    query = "SELECT NOW() FROM orders",
                    sourceDialect = "postgresql"
                )
            )
        )
        val result = generator.generate(s)

        result.render() shouldContain "CREATE VIEW IF NOT EXISTS \"pg_view\" AS"
        result.render() shouldContain "SELECT datetime('now') FROM orders;"
        result.skippedObjects.any { it.type == "view" && it.name == "pg_view" } shouldBe false
        result.notes.any { it.code == "E053" && it.objectName == "pg_view" } shouldBe false
    }

    test("view with no query is skipped") {
        val s = schema(
            views = mapOf(
                "empty_view" to ViewDefinition(query = null)
            )
        )
        val result = generator.generate(s)

        result.skippedObjects.any { it.type == "view" && it.name == "empty_view" } shouldBe true
    }
})
