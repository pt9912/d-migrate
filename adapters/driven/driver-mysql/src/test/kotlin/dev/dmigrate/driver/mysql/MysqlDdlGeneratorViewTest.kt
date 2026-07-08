package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.NoteType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MysqlDdlGeneratorViewTest : FunSpec({

    val generator = MysqlDdlGenerator()

    fun emptySchema(
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
        aggregates: Map<String, AggregateDefinition> = emptyMap()
    ) = SchemaDefinition(
        name = "test_schema",
        version = "1.0",
        tables = tables,
        customTypes = customTypes,
        sequences = sequences,
        views = views,
        functions = functions,
        procedures = procedures,
        triggers = triggers,
        aggregates = aggregates
    )

    test("materialized view creates regular VIEW with W103 warning") {
        val schema = emptySchema(
            views = mapOf(
                "active_users_mv" to ViewDefinition(
                    materialized = true,
                    query = "SELECT * FROM users WHERE active = 1"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE OR REPLACE VIEW `active_users_mv` AS"
        ddl shouldContain "SELECT * FROM users WHERE active = 1"
        ddl shouldNotContain "MATERIALIZED"

        val w103 = result.notes.find { it.code == "W103" && it.objectName == "active_users_mv" }
        w103!!.type shouldBe NoteType.WARNING
        w103.message shouldContain "Materialized views are not supported in MySQL"
    }

    test("regular view creates CREATE OR REPLACE VIEW without warnings") {
        val schema = emptySchema(
            views = mapOf(
                "active_users" to ViewDefinition(
                    query = "SELECT * FROM users WHERE active = 1"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE OR REPLACE VIEW `active_users` AS\nSELECT * FROM users WHERE active = 1;"
        result.notes.filter { it.objectName == "active_users" }.shouldBeEmpty()
    }

    test("view with non-mysql source_dialect is transformed and generated") {
        val schema = emptySchema(
            views = mapOf(
                "pg_view" to ViewDefinition(
                    query = "SELECT CURRENT_DATE FROM orders",
                    sourceDialect = "postgresql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE OR REPLACE VIEW `pg_view` AS"
        ddl shouldContain "SELECT CURDATE() FROM orders;"
        result.notes.none { it.code == "E053" && it.objectName == "pg_view" } shouldBe true
        result.skippedObjects.any { it.name == "pg_view" } shouldBe false
    }

    test("view with no query is skipped") {
        val schema = emptySchema(
            views = mapOf(
                "empty_view" to ViewDefinition(query = null)
            )
        )

        val result = generator.generate(schema)

        result.skippedObjects.any { it.name == "empty_view" } shouldBe true
    }

    test("PostgreSQL SQL-aggregate is surfaced as manual action (E053) for MySQL loadable-UDF (N7)") {
        val schema = emptySchema(
            aggregates = mapOf(
                "group_concat" to AggregateDefinition(stateType = "internal", transitionFunction = "tf"),
            ),
        )
        val result = generator.generate(schema)
        // No actual statement is emitted (the E053 note text mentions
        // "CREATE AGGREGATE FUNCTION", so assert on emitted SQL, not the
        // rendered output which includes notes).
        result.statements.none { it.sql.contains("CREATE AGGREGATE") } shouldBe true
        result.skippedObjects.any {
            it.type == "aggregate" && it.name == "group_concat" && it.code == "E053"
        } shouldBe true
    }

    test("MySQL loadable-UDF aggregate emits CREATE AGGREGATE FUNCTION … SONAME (N7)") {
        val schema = emptySchema(
            aggregates = mapOf(
                "my_agg" to AggregateDefinition(
                    returnType = "STRING", library = "udf_agg.so", sourceDialect = "mysql",
                ),
            ),
        )
        val ddl = generator.generate(schema).render()
        ddl shouldContain "CREATE AGGREGATE FUNCTION"
        ddl shouldContain "my_agg"
        ddl shouldContain "RETURNS STRING"
        ddl shouldContain "SONAME 'udf_agg.so'"
    }
})
