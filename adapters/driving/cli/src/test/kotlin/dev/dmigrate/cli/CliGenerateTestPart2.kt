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

class CliGenerateTestPart2 : FunSpec({

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


    test("schema generate --output --generate-rollback with spatialite writes spatial rollback file") {
        val source = writeTempSchema(spatialSchemaYaml)
        val outFile = Files.createTempFile("d-migrate-cli-spatial-rb-", ".sql")
        outFile.deleteIfExists()
        try {
            shouldNotThrowAny {
                cli().parse(listOf(
                    "schema", "generate",
                    "--source", source.toString(),
                    "--target", "sqlite",
                    "--spatial-profile", "spatialite",
                    "--output", outFile.toString(),
                    "--generate-rollback",
                ))
            }

            val rollback = outFile.resolveSibling(
                outFile.fileName.toString().removeSuffix(".sql") + ".rollback.sql"
            )
            rollback.exists() shouldBe true
            rollback.readText() shouldContain "DiscardGeometryColumn('places', 'shape')"
            rollback.readText() shouldContain "DiscardGeometryColumn('places', 'area')"
            rollback.readText() shouldContain "DiscardGeometryColumn('places', 'location')"
        } finally {
            val sibling = { suffix: String ->
                outFile.resolveSibling(
                    outFile.fileName.toString().removeSuffix(".sql") + suffix
                )
            }
            Files.deleteIfExists(source)
            Files.deleteIfExists(outFile)
            Files.deleteIfExists(sibling(".rollback.sql"))
            Files.deleteIfExists(sibling(".report.yaml"))
        }
    }

    // ─── --split pre-post CLI integration (0.9.2 AP 6.7) ────────

    test("--split pre-post --output writes pre-data and post-data files") {
        val source = writeTempSchema("""
            schema_format: "1.0"
            name: "SplitTest"
            version: "1.0.0"
            tables:
              t:
                columns:
                  id:
                    type: identifier
                    auto_increment: true
                primary_key: [id]
            triggers:
              trg:
                table: t
                event: insert
                timing: after
                for_each: row
                body: "BEGIN END;"
                source_dialect: postgresql
        """.trimIndent())
        val outFile = Files.createTempFile("d-migrate-split-", ".sql")
        Files.deleteIfExists(outFile) // Remove temp file so we can assert runner doesn't write it
        try {
            val (_, stderr) = captureStreams {
                shouldNotThrowAny {
                    cli().parse(listOf(
                        "--quiet",
                        "schema", "generate",
                        "--source", source.toString(),
                        "--target", "postgresql",
                        "--output", outFile.toString(),
                        "--split", "pre-post",
                    ))
                }
            }
            val prePath = Path.of(outFile.toString().removeSuffix(".sql") + ".pre-data.sql")
            val postPath = Path.of(outFile.toString().removeSuffix(".sql") + ".post-data.sql")
            prePath.exists() shouldBe true
            postPath.exists() shouldBe true
            // Original file should NOT exist
            outFile.exists() shouldBe false
            prePath.readText() shouldContain "CREATE TABLE"
            prePath.readText() shouldNotContain "CREATE TRIGGER"
            postPath.readText() shouldContain "CREATE TRIGGER"
            postPath.readText() shouldNotContain "CREATE TABLE"
        } finally {
            val base = outFile.toString().removeSuffix(".sql")
            Files.deleteIfExists(Path.of("$base.pre-data.sql"))
            Files.deleteIfExists(Path.of("$base.post-data.sql"))
            Files.deleteIfExists(Path.of("$base.report.yaml"))
            Files.deleteIfExists(outFile)
            Files.deleteIfExists(source)
        }
    }

    test("--split pre-post without --output exits 2") {
        val source = writeTempSchema("""
            schema_format: "1.0"
            name: "SplitTest"
            version: "1.0.0"
            tables:
              t:
                columns:
                  id:
                    type: identifier
                primary_key: [id]
        """.trimIndent())
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(listOf(
                    "schema", "generate",
                    "--source", source.toString(),
                    "--target", "postgresql",
                    "--split", "pre-post",
                ))
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(source)
        }
    }

    test("--split pre-post --generate-rollback exits 2") {
        val source = writeTempSchema("""
            schema_format: "1.0"
            name: "SplitTest"
            version: "1.0.0"
            tables:
              t:
                columns:
                  id:
                    type: identifier
                primary_key: [id]
        """.trimIndent())
        val outFile = Files.createTempFile("d-migrate-split-", ".sql")
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(listOf(
                    "schema", "generate",
                    "--source", source.toString(),
                    "--target", "postgresql",
                    "--output", outFile.toString(),
                    "--split", "pre-post",
                    "--generate-rollback",
                ))
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(outFile)
            Files.deleteIfExists(source)
        }
    }

    test("--split single is identical to no --split") {
        val source = writeTempSchema("""
            schema_format: "1.0"
            name: "SplitTest"
            version: "1.0.0"
            tables:
              t:
                columns:
                  id:
                    type: identifier
                primary_key: [id]
        """.trimIndent())
        try {
            val (outNoSplit, _) = captureStreams {
                cli().parse(listOf(
                    "--quiet",
                    "schema", "generate",
                    "--source", source.toString(),
                    "--target", "postgresql",
                ))
            }
            val (outSingle, _) = captureStreams {
                cli().parse(listOf(
                    "--quiet",
                    "schema", "generate",
                    "--source", source.toString(),
                    "--target", "postgresql",
                    "--split", "single",
                ))
            }
            fun stripHeader(ddl: String) = ddl.lines()
                .filter { !it.startsWith("-- Generated by") && !it.startsWith("-- Target:") }
                .joinToString("\n").trim()
            stripHeader(outNoSplit) shouldBe stripHeader(outSingle)
        } finally {
            Files.deleteIfExists(source)
        }
    }

    // ─── 0.9.3: --mysql-named-sequences E2E ──────────────────────

    val minimalSchemaYaml = """
        schema_format: "1.0"
        name: "Minimal"
        version: "1.0.0"
        encoding: "utf-8"
        tables:
          users:
            columns:
              id: { type: identifier, auto_increment: true }
              name: { type: text, max_length: 100, required: true }
            primary_key: [id]
    """.trimIndent()

    test("E2E: --mysql-named-sequences helper_table with --target mysql succeeds") {
        val source = writeTempSchema(minimalSchemaYaml)
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "schema", "generate",
                        "--source", source.toString(),
                        "--target", "mysql",
                        "--mysql-named-sequences", "helper_table",
                    )
                )
            }
        } finally {
            Files.deleteIfExists(source)
        }
    }

    test("E2E: --mysql-named-sequences with --target postgresql exits 2") {
        val source = writeTempSchema(minimalSchemaYaml)
        try {
            val (_, stderr) = captureStreams {
                val ex = shouldThrow<ProgramResult> {
                    cli().parse(
                        listOf(
                            "schema", "generate",
                            "--source", source.toString(),
                            "--target", "postgresql",
                            "--mysql-named-sequences", "helper_table",
                        )
                    )
                }
                ex.statusCode shouldBe 2
            }
            stderr shouldContain "--mysql-named-sequences is only valid with --target mysql"
        } finally {
            Files.deleteIfExists(source)
        }
    }

    test("E2E: DDL header contains d-migrate 0.9.4 version") {
        val source = writeTempSchema(minimalSchemaYaml)
        try {
            val (out, _) = captureStreams {
                shouldNotThrowAny {
                    cli().parse(
                        listOf(
                            "schema", "generate",
                            "--source", source.toString(),
                            "--target", "postgresql",
                        )
                    )
                }
            }
            out shouldContain "Generated by d-migrate 0.9.4"
        } finally {
            Files.deleteIfExists(source)
        }
    }

    test("E2E: Report contains d-migrate 0.9.4 generator string") {
        val source = writeTempSchema(minimalSchemaYaml)
        val outFile = Files.createTempFile("d-migrate-cli-gen-093-", ".sql")
        try {
            captureStreams {
                shouldNotThrowAny {
                    cli().parse(
                        listOf(
                            "schema", "generate",
                            "--source", source.toString(),
                            "--target", "postgresql",
                            "--output", outFile.toString(),
                        )
                    )
                }
            }
            val reportPath = Path.of(outFile.toString().replace(".sql", ".report.yaml"))
            val report = Files.readString(reportPath)
            report shouldContain "generator: \"d-migrate 0.9.4\""
            Files.deleteIfExists(reportPath)
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(outFile)
        }
    }

    test("E2E: --output-format json with --target mysql includes generator 0.9.4") {
        val source = writeTempSchema(minimalSchemaYaml)
        try {
            val (out, _) = captureStreams {
                shouldNotThrowAny {
                    cli().parse(
                        listOf(
                            "--output-format", "json",
                            "schema", "generate",
                            "--source", source.toString(),
                            "--target", "mysql",
                        )
                    )
                }
            }
            out shouldContain "\"generator\": \"d-migrate 0.9.4\""
        } finally {
            Files.deleteIfExists(source)
        }
    }
})
