package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Eine Partition kommt dazu oder faellt weg. In PostgreSQL ist sie eine eigene
 * Tabelle, also entsteht sie mit `CREATE TABLE … PARTITION OF …` und faellt mit
 * `DROP TABLE`.
 *
 * Was der Weg **nicht** rendert, steht mit im Test: ein Kind, das seinen Namen
 * behaelt und die Grenzen wechselt, ist kein Bestandswechsel, sondern ein
 * Zuschnitt — `DROP` + `CREATE` verloere die Zeilen.
 */
class PostgresDiffPartitionDeltaTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun child(name: String, from: PartitionBound, to: PartitionBound) =
        PartitionDefinition(name = name, from = listOf(from), to = listOf(to))

    fun config(vararg children: PartitionDefinition) =
        PartitionConfig(PartitionType.RANGE, listOf("placed_on"), children.toList())

    fun schema(partitioning: PartitionConfig) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = linkedMapOf("placed_on" to ColumnDefinition(NeutralType.Date)),
                partitioning = partitioning,
            ),
        ),
    )

    fun plan(before: PartitionConfig, after: PartitionConfig) = planner.plan(
        schema(before),
        schema(after),
        SchemaDiff(tablesChanged = listOf(TableDiff(name = "orders", partitioning = ValueChange(before, after)))),
    )

    val q1 = child("p_2026q1", PartitionBound.Value("'2026-01-01'"), PartitionBound.Value("'2026-04-01'"))
    val q2 = child("p_2026q2", PartitionBound.Value("'2026-04-01'"), PartitionBound.Value("'2026-07-01'"))

    test("eine hinzugekommene Partition entsteht als Kindtabelle") {
        val sqls = gen.generateUp(plan(config(q1), config(q1, q2)), DdlGenerationOptions())
            .statements.map { it.sql }

        sqls shouldBe listOf(
            """CREATE TABLE "p_2026q2" PARTITION OF "orders" """ +
                """FOR VALUES FROM ('2026-04-01') TO ('2026-07-01');""",
        )
    }

    test("eine entfallene Partition wird als Kindtabelle verworfen") {
        val sqls = gen.generateUp(plan(config(q1, q2), config(q1)), DdlGenerationOptions())
            .statements.map { it.sql }

        sqls shouldBe listOf("""DROP TABLE "p_2026q2";""")
    }

    test("der Rueckbau dreht Anlegen und Verwerfen um") {
        val sqls = gen.generateDown(plan(config(q1), config(q1, q2)), DdlGenerationOptions())
            .statements.map { it.sql }

        sqls shouldBe listOf("""DROP TABLE "p_2026q2";""")
    }

    test("ein Kind, das nur seine Grenzen wechselt, wird nicht gerendert") {
        val widened = child("p_2026q2", PartitionBound.Value("'2026-04-01'"), PartitionBound.Value("'2026-10-01'"))

        val result = gen.generateUp(plan(config(q1, q2), config(q1, widened)), DdlGenerationOptions())

        result.statements.shouldBeEmpty()
        val diagnostic = result.diagnostics.single { it.code == "PARTITION_BOUNDS_CHANGE_NOT_APPLIED" }
        diagnostic.message shouldContain "p_2026q2"
    }
})
