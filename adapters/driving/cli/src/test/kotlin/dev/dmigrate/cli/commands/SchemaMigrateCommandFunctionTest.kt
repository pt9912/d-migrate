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
 * E.1 Routine-Migration Slice A: end-to-end check that `schema
 * migrate` drives a function diff through the full pipeline
 * (loader → comparator → planner → PostgreSQL renderer →
 * report-writer) and produces the expected Up-SQL artefact +
 * scrubbed routine preview in the JSON report.
 *
 * The fixture pair changes the function body AND adds a
 * SECURITY DEFINER + search_path attribute set — so the test
 * covers body-hash divergence, signature-attr identity, and the
 * `OR REPLACE` Up render together.
 */
class SchemaMigrateCommandFunctionTest : FunSpec({

    fun cli() = DMigrate().subcommands(SchemaCommand())

    fun resourcePath(name: String): Path =
        Path.of(SchemaMigrateCommandFunctionTest::class.java.getResource("/$name")!!.toURI())

    test("schema migrate renders CREATE OR REPLACE FUNCTION for a body change") {
        val dir = Files.createTempDirectory("dmigrate-routine-e2e")
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        val source = resourcePath("fixtures/migrate/replace-function/desired.yaml")
        val target = resourcePath("fixtures/migrate/replace-function/current.yaml")

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
        upSql.shouldContain("CREATE OR REPLACE FUNCTION \"compute_total\"")
        upSql.shouldContain("LANGUAGE \"plpgsql\"")
        upSql.shouldContain("SECURITY DEFINER")
        upSql.shouldContain("SET search_path = \"public\", \"audit\"")
        upSql.shouldContain("\$body\$")
        upSql.shouldContain("RETURN amount * 1.20")

        val reportText = report.readText()
        // Report carries the renderer output; this is the round-trip
        // smoke that confirms the full pipeline (loader → diff →
        // plan → render → report) handles routine ops.
        reportText.shouldContain("ReplaceFunction")
    }

    test("--debug-body is off by default: report carries bodyDisplay = SCRUBBED_ONLY") {
        val dir = Files.createTempDirectory("dmigrate-routine-e2e-debug-default")
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        val source = resourcePath("fixtures/migrate/replace-function/desired.yaml")
        val target = resourcePath("fixtures/migrate/replace-function/current.yaml")

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

        val reportText = report.readText()
        reportText.shouldContain("\"bodyDisplay\": \"SCRUBBED_ONLY\"")
    }

    test("--debug-body flips the report bodyDisplay to RAW_DEBUG (unsafe)") {
        val dir = Files.createTempDirectory("dmigrate-routine-e2e-debug-on")
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        val source = resourcePath("fixtures/migrate/replace-function/desired.yaml")
        val target = resourcePath("fixtures/migrate/replace-function/current.yaml")

        cli().parse(
            listOf(
                "schema", "migrate",
                "--source", source.toString(),
                "--target", "file:$target",
                "--dialect", "postgresql",
                "--output", output.toString(),
                "--report", report.toString(),
                "--report-format", "json",
                "--debug-body",
            ),
        )

        val reportText = report.readText()
        reportText.shouldContain("\"bodyDisplay\": \"RAW_DEBUG\"")
    }

    test("schema migrate exits 0 when source equals target (no-op)") {
        // Same file on both sides exercises the codec roundtrip:
        // load → compare. Identical schemas with security/searchPath
        // attributes must produce zero operations (no spurious
        // Replace from identity-attr serialisation drift).
        val dir = Files.createTempDirectory("dmigrate-routine-e2e-noop")
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        val source = resourcePath("fixtures/migrate/replace-function/desired.yaml")

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
        reportText.shouldNotContain("ReplaceFunction")
        Files.exists(output) shouldBe false // --plan-only writes no SQL artefact
    }
})
