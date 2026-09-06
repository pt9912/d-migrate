package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Oracle Sub-Slice 5e-2: `schema migrate --target oracle` laeuft durch die
 * ECHTE CLI (Kind-Prozess, containerlos) — das Gegenstueck zu dem in
 * Slice 1a gesetzten und hier gekippten Gate-Ablehnungsfall aus
 * [OracleCommandGateE2ETest].
 *
 * Datei-zu-Datei mit `--plan-only`: das belegt die Kette
 * `DialectCommandGate` (kein Eintrag mehr) → `MigrateRendererRegistry`
 * (liefert den Oracle-Renderer statt `null`) → `OracleDiffDdlGenerator`,
 * ohne eine Datenbank zu brauchen. Der Live-Pfad mit `--execute` steht in
 * `test/integration-oracle`.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class OracleSchemaMigrateE2ETest : FunSpec({

    lateinit var tmp: Path
    lateinit var currentYaml: Path
    lateinit var desiredYaml: Path

    beforeSpec {
        tmp = Files.createTempDirectory("dmigrate-e2e-oracle-migrate-")
        currentYaml = tmp.resolve("current.yaml").apply { writeText(CURRENT) }
        desiredYaml = tmp.resolve("desired.yaml").apply { writeText(DESIRED) }
    }

    afterSpec { tmp.deleteRecursively() }

    test("schema migrate --target oracle renders Oracle DDL instead of refusing the dialect") {
        val run = runRealCli(
            listOf(
                "schema", "migrate",
                "--source", desiredYaml.absolutePathString(),
                "--target", "file:" + currentYaml.absolutePathString(),
                "--dialect", "oracle",
                "--plan-only",
            ),
        )
        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 0
        }
        // Das Gate ist weg -- frueher endete derselbe Aufruf mit Exit 2.
        run.stderr shouldNotContain "does not support dialect oracle"
        run.stdout shouldContain "\"dialect\": \"ORACLE\""
        run.stdout shouldContain "\"blockers\": []"
        // Der Renderer hat wirklich gerendert: frueher lieferte die
        // MigrateRendererRegistry fuer oracle `null`, und die Vorbereitung
        // brach mit Exit 2 ab, bevor es Statements gab.
        run.stdout shouldContain "\"statementsTotal\":2"
        run.stdout shouldContain "\"kind\":\"AddColumn\""
        run.stdout shouldContain "\"kind\":\"AlterColumnType\""
    }

    // `--plan-only` unterdrueckt die Statement-Rumpfe per Design
    // (`SchemaMigrateReportBuilder.buildStatementViews`). Ohne das Flag
    // gegen ein Datei-Ziel wird die DDL geschrieben -- erst dort laesst
    // sich belegen, dass es ORACLE-SQL ist und nicht irgendeine.
    test("the rendered DDL is Oracle's, not another dialect's") {
        val out = tmp.resolve("migrate.sql")
        val run = runRealCli(
            listOf(
                "schema", "migrate",
                "--source", desiredYaml.absolutePathString(),
                "--target", "file:" + currentYaml.absolutePathString(),
                "--dialect", "oracle",
                "--output", out.absolutePathString(),
            ),
        )
        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 0
        }
        val ddl = out.readText()
        // `MODIFY` statt `ALTER COLUMN`, `VARCHAR2` statt `VARCHAR`,
        // quoted-lowercase statt gefaltet -- und Oracle klammert die
        // hinzugefuegte Spalte (`ADD (...)`).
        ddl shouldContain "ALTER TABLE \"customers\" ADD (\"nickname\" VARCHAR2(50))"
        ddl shouldContain "ALTER TABLE \"customers\" MODIFY \"email\" VARCHAR2(320)"
    }
})

private val CURRENT = """
    schema_format: "1.0"
    name: "oracle-migrate-e2e"
    version: "1.0.0"

    tables:
      customers:
        columns:
          id:
            type: identifier
          email:
            type: text
            max_length: 254
            required: true
        primary_key: [id]
""".trimIndent()

private val DESIRED = """
    schema_format: "1.0"
    name: "oracle-migrate-e2e"
    version: "1.1.0"

    tables:
      customers:
        columns:
          id:
            type: identifier
          email:
            type: text
            max_length: 320
            required: true
          nickname:
            type: text
            max_length: 50
        primary_key: [id]
""".trimIndent()
