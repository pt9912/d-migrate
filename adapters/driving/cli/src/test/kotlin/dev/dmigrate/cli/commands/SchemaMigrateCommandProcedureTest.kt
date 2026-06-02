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
 * E.1 Routine-Migration Slice B: end-to-end check that `schema
 * migrate` drives a procedure diff through the full pipeline
 * (loader → comparator → planner → PostgreSQL renderer →
 * report-writer) and produces the expected Up-SQL artefact. The
 * fixture pair changes the procedure body AND adds a SECURITY
 * DEFINER + search_path attribute set — so the test covers
 * body-hash divergence, identity-attr persistence, and the
 * `OR REPLACE` Up render together.
 */
class SchemaMigrateCommandProcedureTest : FunSpec({

    fun cli() = DMigrate().subcommands(SchemaCommand())

    fun resourcePath(name: String): Path =
        Path.of(SchemaMigrateCommandProcedureTest::class.java.getResource("/$name")!!.toURI())

    test("schema migrate renders CREATE OR REPLACE PROCEDURE for a body change") {
        val dir = Files.createTempDirectory("dmigrate-routine-e2e-proc")
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        val source = resourcePath("fixtures/migrate/replace-procedure/desired.yaml")
        val target = resourcePath("fixtures/migrate/replace-procedure/current.yaml")

        cli().parse(
            listOf(
                "schema", "migrate",
                "--source", source.toString(),
                "--target", "file:$target",
                "--dialect", "postgresql",
                "--output", output.toString(),
                "--report", report.toString(),
                "--report-format", "json",
            ),
        )

        val upSql = output.readText()
        upSql.shouldContain("CREATE OR REPLACE PROCEDURE \"audit_call\"")
        upSql.shouldContain("LANGUAGE \"plpgsql\"")
        upSql.shouldContain("SECURITY DEFINER")
        upSql.shouldContain("SET search_path = \"public\", \"audit\"")
        upSql.shouldContain("\$body\$")
        upSql.shouldContain("CALL audit_log_v2(id_in)")
        upSql.shouldNotContain("RETURNS") // procedures have no return type

        val reportText = report.readText()
        reportText.shouldContain("ReplaceProcedure")
    }

    test("schema migrate exits 0 when source equals target (no-op)") {
        val dir = Files.createTempDirectory("dmigrate-routine-e2e-proc-noop")
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        val source = resourcePath("fixtures/migrate/replace-procedure/desired.yaml")

        cli().parse(
            listOf(
                "schema", "migrate",
                "--source", source.toString(),
                "--target", "file:$source",
                "--dialect", "postgresql",
                "--output", output.toString(),
                "--report", report.toString(),
                "--report-format", "json",
                "--plan-only",
            ),
        )

        val reportText = report.readText()
        reportText.shouldNotContain("ReplaceProcedure")
        Files.exists(output) shouldBe false
    }
})
