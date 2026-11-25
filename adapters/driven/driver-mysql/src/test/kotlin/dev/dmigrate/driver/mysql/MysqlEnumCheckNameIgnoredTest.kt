package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * ADR 0069: ein `values_constraint` an einer Inline-Enum hat auf MySQL kein Gegenstueck, denn MySQL rendert ein
 * natives `ENUM` ohne CHECK. Das Schema laeuft, der Name bleibt ohne Wirkung und wird als `W165` gemeldet — im
 * Generate-Pfad als Note, im Migrate-Pfad als Diagnose mit demselben Wortlaut.
 */
class MysqlEnumCheckNameIgnoredTest : FunSpec({

    fun table(name: String?) = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Identifier(true), ordinal = 1),
            "status" to ColumnDefinition(
                NeutralType.Enum(values = listOf("open", "closed"), valuesConstraintName = name), ordinal = 2,
            ),
        ),
        primaryKey = listOf("id"),
    )

    fun schema(name: String?) = SchemaDefinition(name = "s", version = "1", tables = mapOf("t" to table(name)))

    test("generate: das native ENUM bleibt, der Name faellt weg und meldet W165") {
        val result = MysqlDdlGenerator().generate(schema("ck_state"))

        result.render() shouldContain "ENUM('open', 'closed')"
        // Nur die Anweisung: die Meldung selbst steht als Kommentar im Ergebnis und nennt den Namen.
        result.render().lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n") shouldNotContain "ck_state"
        val note = result.notes.single { it.code == "W165" }
        note.objectName shouldBe "t.status"
        note.message shouldContain "ck_state"
    }

    test("generate ohne Namen: keine Meldung") {
        MysqlDdlGenerator().generate(schema(null)).notes.none { it.code == "W165" } shouldBe true
    }

    test("migrate: CreateTable meldet W165 mit demselben Wortlaut") {
        val empty = SchemaDefinition(name = "App", version = "1")
        val r = MysqlDiffDdlGenerator().generateUp(
            DiffPlanner().plan(empty, empty, SchemaDiff(tablesAdded = listOf(NamedTable("t", table("ck_state"))))),
            DdlGenerationOptions(),
        )

        r.statements.joinToString { it.sql } shouldNotContain "ck_state"
        r.diagnostics.single { it.code == "W165" }.message shouldBe MysqlEnumCheckNameIgnored.message("status", "ck_state")
    }
})
