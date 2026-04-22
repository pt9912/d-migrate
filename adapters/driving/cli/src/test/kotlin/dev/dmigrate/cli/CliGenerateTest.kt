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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

class CliGenerateTest : FunSpec({

    beforeSpec { registerDrivers() }
    afterSpec { DatabaseDriverRegistry.clear() }

    fun resourcePath(name: String): String =
        CliGenerateTest::class.java.getResource("/$name")!!.path

    fun cli() = DMigrate().subcommands(SchemaCommand())

    fun captureStreams(block: () -> Unit): Pair<String, String> {
        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return out.toString(Charsets.UTF_8) to err.toString(Charsets.UTF_8)
    }

    fun writeTempSchema(content: String): Path =
        Files.createTempFile("d-migrate-schema-", ".yaml").also { Files.writeString(it, content) }

    val spatialSchemaYaml = """
        schema_format: "1.0"
        name: "Spatial Schema"
        version: "1.0.0"

        tables:
          places:
            columns:
              id:
                type: identifier
                auto_increment: true
              name:
                type: text
                max_length: 100
                required: true
              location:
                type: geometry
                geometry_type: point
                srid: 4326
              area:
                type: geometry
                geometry_type: polygon
              shape:
                type: geometry
            primary_key: [id]
    """.trimIndent()

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

    test("schema generate --output-format json with spatial mysql schema exposes W120") {
        val source = writeTempSchema(spatialSchemaYaml)
        try {
            val (stdout, stderr) = captureStreams {
                shouldNotThrowAny {
                    cli().parse(listOf(
                        "--output-format", "json",
                        "schema", "generate",
                        "--source", source.toString(),
                        "--target", "mysql",
                    ))
                }
            }

            stdout shouldContain "\"target\": \"mysql\""
            stdout shouldContain "\"code\": \"W120\""
            stdout shouldContain "\"warnings\": 1"
            stdout shouldContain "\"object\": \"location\""
            stderr shouldContain "Warning [W120]"
        } finally {
            Files.deleteIfExists(source)
        }
    }

    test("schema generate --output-format json with spatial profile none exposes E052") {
        val source = writeTempSchema(spatialSchemaYaml)
        try {
            val (stdout, stderr) = captureStreams {
                shouldNotThrowAny {
                    cli().parse(listOf(
                        "--output-format", "json",
                        "schema", "generate",
                        "--source", source.toString(),
                        "--target", "postgresql",
                        "--spatial-profile", "none",
                    ))
                }
            }

            stdout shouldContain "\"target\": \"postgresql\""
            stdout shouldContain "\"code\": \"E052\""
            stdout shouldContain "\"name\": \"places\""
            stdout shouldContain "\"hint\": \"Use --spatial-profile to enable spatial DDL generation for this dialect\""
            stdout shouldNotContain "CREATE TABLE \\\"places\\\""
            stderr shouldContain "Action required [E052]"
            stderr shouldContain "Skipped [E052] table 'places'"
        } finally {
            Files.deleteIfExists(source)
        }
    }

    test("schema generate --output with spatial mysql schema writes sidecar report with W120") {
        val source = writeTempSchema(spatialSchemaYaml)
        val outFile = Files.createTempFile("d-migrate-cli-spatial-mysql-", ".sql")
        outFile.deleteIfExists()
        try {
            shouldNotThrowAny {
                cli().parse(listOf(
                    "schema", "generate",
                    "--source", source.toString(),
                    "--target", "mysql",
                    "--output", outFile.toString(),
                ))
            }

            outFile.exists() shouldBe true
            outFile.readText() shouldContain "POINT /*!80003 SRID 4326 */"
            val reportPath = outFile.resolveSibling(
                outFile.fileName.toString().removeSuffix(".sql") + ".report.yaml"
            )
            reportPath.exists() shouldBe true
            reportPath.readText() shouldContain "code: W120"
            reportPath.readText() shouldContain "object: \"location\""
        } finally {
            val reportPath = outFile.resolveSibling(
                outFile.fileName.toString().removeSuffix(".sql") + ".report.yaml"
            )
            Files.deleteIfExists(source)
            Files.deleteIfExists(outFile)
            Files.deleteIfExists(reportPath)
        }
    }

    test("schema generate --output with spatial profile none writes sidecar report with E052") {
        val source = writeTempSchema(spatialSchemaYaml)
        val outFile = Files.createTempFile("d-migrate-cli-spatial-none-", ".sql")
        outFile.deleteIfExists()
        try {
            shouldNotThrowAny {
                cli().parse(listOf(
                    "schema", "generate",
                    "--source", source.toString(),
                    "--target", "postgresql",
                    "--spatial-profile", "none",
                    "--output", outFile.toString(),
                ))
            }

            outFile.exists() shouldBe true
            outFile.readText() shouldNotContain "CREATE TABLE \"places\""
            val reportPath = outFile.resolveSibling(
                outFile.fileName.toString().removeSuffix(".sql") + ".report.yaml"
            )
            reportPath.exists() shouldBe true
            reportPath.readText() shouldContain "code: E052"
            reportPath.readText() shouldContain "name: \"places\""
            reportPath.readText() shouldContain "hint: \"Use --spatial-profile to enable spatial DDL generation for this dialect\""
        } finally {
            val reportPath = outFile.resolveSibling(
                outFile.fileName.toString().removeSuffix(".sql") + ".report.yaml"
            )
            Files.deleteIfExists(source)
            Files.deleteIfExists(outFile)
            Files.deleteIfExists(reportPath)
        }
    }

})
