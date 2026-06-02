package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.DMigrate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * E.1 Routine-Migration Slice C.2: end-to-end check that `schema
 * migrate --dialect mysql` drives a function diff through the full
 * pipeline (loader → comparator → planner → MySQL renderer →
 * report-writer) and produces a delimiterfreies Up-SQL artefact.
 * The fixture pair changes the function body AND adds SQL SECURITY
 * DEFINER, so the test covers body-hash divergence, identity-attr
 * persistence, and the conservative Oracle-MySQL default route
 * (`DROP` + `CREATE`, no `OR REPLACE`) together.
 */
class SchemaMigrateCommandMysqlRoutineTest : FunSpec({

    fun cli() = DMigrate().subcommands(SchemaCommand())

    fun resourcePath(name: String): Path =
        Path.of(SchemaMigrateCommandMysqlRoutineTest::class.java.getResource("/$name")!!.toURI())

    test("schema migrate renders guarded DROP + CREATE for a MySQL body change") {
        val dir = Files.createTempDirectory("dmigrate-mysql-routine-e2e")
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        val source = resourcePath("fixtures/migrate/replace-mysql-function/desired.yaml")
        val target = resourcePath("fixtures/migrate/replace-mysql-function/current.yaml")

        cli().parse(
            listOf(
                "schema", "migrate",
                "--source", source.toString(),
                "--target", "file:$target",
                "--dialect", "mysql",
                "--output", output.toString(),
                "--report", report.toString(),
                "--report-format", "json",
            ),
        )

        val upSql = output.readText()
        upSql.shouldContain("DROP FUNCTION `compute_total`")
        upSql.shouldContain("CREATE FUNCTION `compute_total`")
        upSql.shouldNotContain("CREATE OR REPLACE FUNCTION `compute_total`")
        upSql.shouldContain("(amount DECIMAL(10,2))")
        upSql.shouldContain("RETURNS DECIMAL(10,2)")
        upSql.shouldContain("LANGUAGE SQL")
        upSql.shouldContain("DETERMINISTIC")
        upSql.shouldContain("SQL SECURITY DEFINER")
        upSql.shouldContain("RETURN amount * 1.20")
        upSql.shouldNotContain("DELIMITER") // canonical artefact stays delimiter-free
        upSql.shouldNotContain("\$body\$") // no Postgres-style dollar-quoting

        val reportText = report.readText()
        reportText.shouldContain("ReplaceFunction")
    }

    test("schema migrate exits 0 when source equals target (no-op)") {
        val dir = Files.createTempDirectory("dmigrate-mysql-routine-noop")
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        val source = resourcePath("fixtures/migrate/replace-mysql-function/desired.yaml")

        cli().parse(
            listOf(
                "schema", "migrate",
                "--source", source.toString(),
                "--target", "file:$source",
                "--dialect", "mysql",
                "--output", output.toString(),
                "--report", report.toString(),
                "--report-format", "json",
                "--plan-only",
            ),
        )

        val reportText = report.readText()
        reportText.shouldNotContain("ReplaceFunction")
        Files.exists(output) shouldBe false
    }
})
