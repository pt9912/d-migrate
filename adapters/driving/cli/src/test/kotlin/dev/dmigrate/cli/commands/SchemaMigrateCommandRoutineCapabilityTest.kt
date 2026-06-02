package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.DMigrate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * 0.9.7 routine-capability-configurable-source Sub-Slice C end-to-end
 * pin: a `.d-migrate.yaml` with a structurally-parsable but
 * semantically-invalid `routineCapability:` section flows through the
 * resolver, surfaces as
 * [dev.dmigrate.driver.EffectiveRoutineCapability.Invalid] in the
 * pipeline, and lands in the migration report as a
 * `ROUTINE_CAPABILITY_CONFIG_INVALID` block with the operator's
 * reason string.
 *
 * Plan §3 acceptance criterion: "SchemaMigrateCommandTest End-to-End:
 * invalide YAML-Capability → ROUTINE_CAPABILITY_CONFIG_INVALID Manifest-Block."
 *
 * "Invalid" here means inputs the Sub-Slice-A parser categorises as
 * Invalid (e.g. a YAML float for `minServerVersion`, which SnakeYAML
 * coerces to `Double` and the parser rejects). Structurally-broken
 * YAML (e.g. `routineCapability: true`) is rejected earlier by the
 * resolver's load step and surfaces as a `ConfigResolveException`
 * before the pipeline runs — that path is pinned in
 * `RoutineCapabilityConfigResolverTest`.
 */
class SchemaMigrateCommandRoutineCapabilityTest : FunSpec({

    fun cli() = DMigrate().subcommands(SchemaCommand())

    fun resourcePath(name: String): Path =
        Path.of(
            SchemaMigrateCommandRoutineCapabilityTest::class.java.getResource("/$name")!!.toURI(),
        )

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-routine-cap-cli-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    test("invalid YAML routineCapability surfaces as ROUTINE_CAPABILITY_CONFIG_INVALID in the report") {
        val dir = Files.createTempDirectory("dmigrate-routine-cap-e2e-yaml")
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        val source = resourcePath("fixtures/migrate/replace-mysql-function/desired.yaml")
        val target = resourcePath("fixtures/migrate/replace-mysql-function/current.yaml")
        // YAML float for minServerVersion — SnakeYAML deserialises 8.0
        // to Double, the Sub-Slice-A parser rejects with "must be a
        // quoted string" because float coercion silently truncates the
        // operator's intent.
        val cfg = tempConfig(
            """
            routineCapability:
              function:
                enabled: true
                minServerVersion: 8.0
            """.trimIndent(),
        )

        val exit = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "--config", cfg.toString(),
                    "schema", "migrate",
                    "--source", source.toString(),
                    "--target", "file:$target",
                    "--dialect", "mysql",
                    "--output", output.toString(),
                    "--report", report.toString(),
                    "--report-format", "json",
                ),
            )
        }
        exit.statusCode shouldBe 8

        val reportText = report.readText()
        reportText.shouldContain("ROUTINE_CAPABILITY_CONFIG_INVALID")
        reportText.shouldContain("must be a quoted string")
    }

    test("invalid --routine-capability CLI flag surfaces the same diagnostic block") {
        val dir = Files.createTempDirectory("dmigrate-routine-cap-e2e-cli")
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        val source = resourcePath("fixtures/migrate/replace-mysql-function/desired.yaml")
        val target = resourcePath("fixtures/migrate/replace-mysql-function/current.yaml")

        val exit = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "schema", "migrate",
                    "--source", source.toString(),
                    "--target", "file:$target",
                    "--dialect", "mysql",
                    "--output", output.toString(),
                    "--report", report.toString(),
                    "--report-format", "json",
                    "--routine-capability", "function:enabled=yes",
                ),
            )
        }
        exit.statusCode shouldBe 8

        val reportText = report.readText()
        reportText.shouldContain("ROUTINE_CAPABILITY_CONFIG_INVALID")
        reportText.shouldContain("invalid 'enabled' value 'yes'")
    }
})
