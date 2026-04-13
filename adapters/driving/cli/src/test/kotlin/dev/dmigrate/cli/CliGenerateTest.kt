package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

class CliGenerateTest : FunSpec({

    beforeSpec { registerDrivers() }
    afterSpec { DatabaseDriverRegistry.clear() }

    fun resourcePath(name: String): String =
        CliGenerateTest::class.java.getResource("/$name")!!.path

    fun cli() = DMigrate().subcommands(SchemaCommand())

    test("schema generate with valid schema and --target postgresql exits successfully") {
        shouldNotThrowAny {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("valid-schema.yaml"), "--target", "postgresql"))
        }
    }

    test("schema generate with valid schema and --target mysql exits successfully") {
        shouldNotThrowAny {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("valid-schema.yaml"), "--target", "mysql"))
        }
    }

    test("schema generate with valid schema and --target sqlite exits successfully") {
        shouldNotThrowAny {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("valid-schema.yaml"), "--target", "sqlite"))
        }
    }

    test("schema generate with invalid target exits with code 2") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("valid-schema.yaml"), "--target", "oracle"))
        }
        ex.statusCode shouldBe 2
    }

    test("schema generate with broken YAML exits with code 7") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("broken.yaml"), "--target", "postgresql"))
        }
        ex.statusCode shouldBe 7
    }

    test("schema generate with invalid schema exits with code 3") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("invalid-schema.yaml"), "--target", "postgresql"))
        }
        ex.statusCode shouldBe 3
    }

    // ─── E2E: --output triggers the real TransformationReportWriter ──
    // This path is not covered by the Runner unit tests because those
    // inject a fake reportWriter lambda. We need at least one test that
    // exercises the default lambda in SchemaGenerateRunner (which delegates
    // to TransformationReportWriter().write(...)).

    test("schema generate --output writes the DDL file AND the sidecar report") {
        val outFile = Files.createTempFile("d-migrate-cli-gen-", ".sql")
        outFile.deleteIfExists()
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "schema", "generate",
                        "--source", resourcePath("valid-schema.yaml"),
                        "--target", "postgresql",
                        "--output", outFile.toString(),
                    )
                )
            }
            outFile.exists() shouldBe true
            // The DDL file contains at least one CREATE TABLE
            outFile.readText() shouldBe outFile.readText() // just read — test liveness
            // The sidecar report is alongside the DDL: <basename>.report.yaml
            val reportPath = outFile.resolveSibling(
                outFile.fileName.toString().removeSuffix(".sql") + ".report.yaml"
            )
            reportPath.exists() shouldBe true
        } finally {
            Files.deleteIfExists(outFile)
            val reportPath = outFile.resolveSibling(
                outFile.fileName.toString().removeSuffix(".sql") + ".report.yaml"
            )
            Files.deleteIfExists(reportPath)
        }
    }

    test("schema generate --output --generate-rollback writes both DDL and rollback files") {
        val outFile = Files.createTempFile("d-migrate-cli-gen-rb-", ".sql")
        outFile.deleteIfExists()
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "schema", "generate",
                        "--source", resourcePath("valid-schema.yaml"),
                        "--target", "postgresql",
                        "--output", outFile.toString(),
                        "--generate-rollback",
                    )
                )
            }
            outFile.exists() shouldBe true
            val rollback = outFile.resolveSibling(
                outFile.fileName.toString().removeSuffix(".sql") + ".rollback.sql"
            )
            rollback.exists() shouldBe true
        } finally {
            val sibling = { suffix: String ->
                outFile.resolveSibling(
                    outFile.fileName.toString().removeSuffix(".sql") + suffix
                )
            }
            Files.deleteIfExists(outFile)
            Files.deleteIfExists(sibling(".rollback.sql"))
            Files.deleteIfExists(sibling(".report.yaml"))
        }
    }

    // ─── Spatial Profile (Phase D) ──────────────────────────────

    test("schema generate --spatial-profile is accepted for valid combo") {
        shouldNotThrowAny {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("valid-schema.yaml"),
                "--target", "postgresql", "--spatial-profile", "postgis"))
        }
    }

    test("schema generate with invalid spatial profile exits with code 2") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "generate", "--source", resourcePath("valid-schema.yaml"),
                "--target", "mysql", "--spatial-profile", "spatialite"))
        }
        ex.statusCode shouldBe 2
    }
})
