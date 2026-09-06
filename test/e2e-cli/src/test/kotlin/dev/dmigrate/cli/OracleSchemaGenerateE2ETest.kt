package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Oracle Slice 2 (docs/planning/in-progress/oracle-dialect-scoping.md):
 * `schema generate --target oracle` und `export <tool> --target oracle`
 * laufen durch die ECHTE CLI (Kind-Prozess, containerlos) — die
 * Gegenstuecke zu den in Slice 1a gekippten Gate-Ablehnungsfaellen.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class OracleSchemaGenerateE2ETest : FunSpec({

    lateinit var tmp: Path
    lateinit var schemaYaml: Path

    beforeSpec {
        tmp = Files.createTempDirectory("dmigrate-e2e-oracle-generate-")
        schemaYaml = tmp.resolve("schema.yaml").apply { writeText(SCHEMA) }
    }

    afterSpec {
        tmp.deleteRecursively()
    }

    test("schema generate --target oracle writes DDL plus sidecar report") {
        val out = tmp.resolve("schema.sql")
        val run = runRealCli(
            listOf(
                "schema", "generate",
                "--source", schemaYaml.absolutePathString(),
                "--target", "oracle",
                "--output", out.absolutePathString(),
                "--deterministic",
            ),
        )
        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 0
        }
        val ddl = out.readText()
        ddl shouldContain "-- Target: oracle"
        ddl shouldContain "CREATE TABLE \"customers\" ("
        ddl shouldContain "\"id\" NUMBER(9) GENERATED ALWAYS AS IDENTITY"
        ddl shouldContain "\"email\" VARCHAR2(254) NOT NULL CONSTRAINT \"uq_customers_email\" UNIQUE"
        ddl shouldContain "CONSTRAINT \"fk_orders_customer_id\" FOREIGN KEY (\"customer_id\") REFERENCES \"customers\" (\"id\")"
        // Skript-Darstellung: jedes ausfuehrbare Statement bekommt einen eigenen
        // `/`-Batch-Trenner (DialectCapabilities.batchSeparator).
        ddl shouldContain "CREATE INDEX \"idx_orders_customer\" ON \"orders\" (\"customer_id\");\n/\n"
        ddl shouldContain "CREATE OR REPLACE FORCE VIEW \"active_customers\" AS\nSELECT id, email FROM customers;\n/"
        ddl shouldNotContain "does not support dialect oracle"
        Files.exists(tmp.resolve("schema.report.yaml")) shouldBe true
    }

    test("export flyway --target oracle writes a versioned migration") {
        val outDir = tmp.resolve("flyway")
        val run = runRealCli(
            listOf(
                "export", "flyway",
                "--source", schemaYaml.absolutePathString(),
                "--target", "oracle",
                "--output", outDir.absolutePathString(),
                "--version", "1.0.0",
            ),
        )
        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 0
        }
        val migrations = outDir.listDirectoryEntries("V*.sql")
        migrations shouldHaveSize 1
        val migration = migrations.single().readText()
        migration shouldContain "CREATE TABLE \"customers\" ("
        migration shouldContain "CREATE OR REPLACE FORCE VIEW \"active_customers\" AS\nSELECT id, email FROM customers"
    }

    listOf(
        Triple("liquibase", "1.0.0", "*.xml"),
        Triple("django", "0001_initial", "*.py"),
        Triple("knex", "20260101000000", "*.js"),
    ).forEach { (tool, version, glob) ->
        test("export $tool --target oracle writes an Oracle migration artefact") {
            val outDir = tmp.resolve(tool)
            val run = runRealCli(
                listOf(
                    "export", tool,
                    "--source", schemaYaml.absolutePathString(),
                    "--target", "oracle",
                    "--output", outDir.absolutePathString(),
                    "--version", version,
                ),
            )
            withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
                run.exitCode shouldBe 0
            }
            val artefacts = outDir.listDirectoryEntries(glob)
            artefacts shouldHaveSize 1
            artefacts.single().readText() shouldContain "CREATE TABLE \"customers\""
        }
    }
})

private val SCHEMA = """
    schema_format: "1.0"
    name: "oracle-generate-e2e"
    version: "1.0.0"

    tables:
      customers:
        columns:
          id:
            type: identifier
            auto_increment: true
          email:
            type: text
            max_length: 254
            required: true
            unique: true
        primary_key: [id]
      orders:
        columns:
          id:
            type: identifier
            auto_increment: true
          customer_id:
            type: integer
            required: true
            references:
              table: customers
              column: id
        primary_key: [id]
        indices:
          - name: idx_orders_customer
            columns: [customer_id]

    views:
      active_customers:
        query: "SELECT id, email FROM customers"
        dependencies:
          tables: [customers]
""".trimIndent()
