package dev.dmigrate.driver.postgresql

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
 * [ADR 0049] + der Vertrag, den `MssqlIndexDdlHelper` ausspricht: `generate` und
 * `migrate` müssen denselben Index schreiben.
 *
 * PostgreSQL baut seinen `CREATE INDEX` an zwei Stellen — im `DdlGenerator` und
 * im Diff-Builder. Genau daran sind die beiden schon einmal auseinandergelaufen:
 * der Generator lernte `INCLUDE`, der Diff-Builder nicht. Das Symptom war kein
 * Fehler, sondern Drift im Post-Compare nach einem Lauf mit Exit 0.
 *
 * Dieser Test stellt die beiden Pfade deshalb gegeneinander, statt sie einzeln
 * zu prüfen.
 */
class PostgresCoveringIndexParityTest : FunSpec({

    val generator = PostgresDdlGenerator()
    val planner = DiffPlanner()
    val diffGenerator = PostgresDiffDdlGenerator()

    val covering = IndexDefinition(
        name = "ix_cover",
        columns = listOf(IndexColumn("id")),
        includeColumns = listOf("title", "body"),
    )

    fun table(indices: List<IndexDefinition>) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "title" to ColumnDefinition(NeutralType.Text()),
            "body" to ColumnDefinition(NeutralType.Text()),
        ),
        primaryKey = listOf("id"),
        indices = indices,
    )

    fun schema(indices: List<IndexDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to table(indices)))

    /** Die `CREATE INDEX`-Zeile aus dem vollständigen Generate-Skript. */
    fun generatedIndexSql(): String =
        generator.generate(schema(listOf(covering))).render()
            .lines().first { it.startsWith("CREATE INDEX \"ix_cover\"") }
            .trim()

    /** Dieselbe Zeile, wenn der Diff-Pfad den Index anlegt. */
    fun migratedIndexSql(): String {
        val current = schema(emptyList())
        val desired = schema(listOf(covering))
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(covering))),
        )
        return diffGenerator.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
            .statements.map { it.sql }
            .first { it.startsWith("CREATE INDEX \"ix_cover\"") }
            .trim()
    }

    test("the migrate path renders INCLUDE, not just the generate path") {
        migratedIndexSql() shouldContain """INCLUDE ("title", "body")"""
    }

    test("both paths write the same index") {
        // Der Kern: nicht dass beide INCLUDE koennen, sondern dass sie sich decken.
        migratedIndexSql() shouldBe generatedIndexSql()
    }

    test("INCLUDE stands between the key columns and a filter") {
        val filtered = covering.copy(where = "\"title\" IS NOT NULL")
        val current = schema(emptyList())
        val desired = schema(listOf(filtered))
        val sql = diffGenerator.generateUp(
            planner.plan(
                current, desired,
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(filtered)))),
            ),
            DdlGenerationOptions(),
        ).statements.map { it.sql }.first { it.startsWith("CREATE INDEX") }

        // PostgreSQL erwartet diese Reihenfolge; vertauscht ist es kein gueltiges SQL.
        val includeAt = sql.indexOf("INCLUDE (")
        val whereAt = sql.indexOf(" WHERE ")
        (includeAt in 1..<whereAt) shouldBe true
    }
})
