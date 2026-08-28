package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * [ADR 0049]: MySQL kennt weder INCLUDE-Spalten noch eine Steuerung der Ablage.
 * `schema generate` sagt das mit W142/W143 — und `schema migrate` muss es auch
 * sagen. Warnt nur der eine Pfad, verliert der andere dieselbe Struktur
 * stillschweigend, und der Anwender erfährt es nicht.
 */
class MysqlCoveringIndexDiffNoteTest : FunSpec({

    val generator = MysqlDdlGenerator()
    val planner = DiffPlanner()
    val diffGenerator = MysqlDiffDdlGenerator()

    val covering = IndexDefinition(
        name = "ix_cover",
        columns = listOf(IndexColumn("id")),
        includeColumns = listOf("title"),
        clustered = true,
    )

    fun schema(indices: List<IndexDefinition>) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf("docs" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "title" to ColumnDefinition(NeutralType.Text(maxLength = 100)),
            ),
            primaryKey = listOf("id"),
            indices = indices,
        )),
    )

    test("the migrate path reports the same degradation as the generate path") {
        val generateCodes = generator.generate(schema(listOf(covering)))
            .notes.map { it.code }.filter { it == "W142" || it == "W143" }

        val current = schema(emptyList())
        val desired = schema(listOf(covering))
        val plan = planner.plan(
            current, desired,
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(covering)))),
        )
        val migrateCodes = diffGenerator.generateUp(plan, DdlGenerationOptions())
            .diagnostics.map { it.code }.filter { it == "W142" || it == "W143" }

        generateCodes shouldBe listOf("W142", "W143")
        migrateCodes shouldBe generateCodes
    }

    test("the diagnostic names the columns that were dropped") {
        val current = schema(emptyList())
        val desired = schema(listOf(covering))
        val diagnostics = diffGenerator.generateUp(
            planner.plan(
                current, desired,
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(covering)))),
            ),
            DdlGenerationOptions(),
        ).diagnostics

        diagnostics.first { it.code == "W142" }.message shouldContain "title"
    }

    test("an index without either property produces no such diagnostic") {
        val plain = IndexDefinition(name = "ix", columns = listOf(IndexColumn("id")))
        val current = schema(emptyList())
        val desired = schema(listOf(plain))
        val diagnostics = diffGenerator.generateUp(
            planner.plan(
                current, desired,
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(plain)))),
            ),
            DdlGenerationOptions(),
        ).diagnostics
        diagnostics.none { it.code == "W142" || it.code == "W143" } shouldBe true
    }
})
