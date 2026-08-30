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
import kotlin.io.path.writeText

/**
 * Kein Kommando lehnt mssql mehr an der Kommando-Grenze ab ([ADR 0047]) —
 * Subprozess-E2E gegen die ECHTE CLI, containerlos.
 *
 * Der Beleg laeuft ueber den Exit-Code: die mssql-URL zeigt auf einen Port, an
 * dem niemand lauscht. Ein abgelehntes Kommando endete mit Exit 2 (Usage),
 * bevor es die Verbindung ueberhaupt versucht; ein freigeschaltetes kommt bis
 * zum Verbindungsversuch und endet mit Exit 4/7. Die Funktions-E2E liegen
 * daneben: `schema generate` und `export <tool>` in
 * `MssqlSchemaGenerateE2ETest`, der Datenpfad in `MssqlTransferE2ETest`.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class MssqlCommandGateE2ETest : FunSpec({

    lateinit var tmp: Path
    lateinit var schemaYaml: Path

    beforeSpec {
        tmp = Files.createTempDirectory("dmigrate-e2e-mssql-gate-")
        schemaYaml = tmp.resolve("schema.yaml").apply { writeText(MINIMAL_SCHEMA) }
    }

    afterSpec {
        tmp.deleteRecursively()
    }

    test("schema migrate --dialect mssql plans T-SQL instead of being refused") {
        // Der Gegenbeweis zur Ablehnung: dasselbe Kommando, das hier frueher
        // mit Exit 2 endete, rendert jetzt einen Plan. Ziel ist ein Schema
        // ohne die Spalte `email` — der Plan muss sie hinzufuegen.
        //
        // `--plan-only` gibt die Statements nicht aus; belegt wird deshalb,
        // dass der MSSQL-Renderer die Operation ANGENOMMEN hat
        // (`rendered: true`, keine Blocker). Wie das T-SQL aussieht, pinnen
        // die Renderer-Tests und der Live-Round-Trip.
        val target = tmp.resolve("target.yaml").apply { writeText(SCHEMA_WITHOUT_EMAIL) }
        val run = runRealCli(
            listOf(
                "schema", "migrate",
                "--source", schemaYaml.absolutePathString(),
                "--target", "file:" + target.absolutePathString(),
                "--dialect", "mssql",
                "--plan-only",
            ),
        )
        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 0
            run.stdout shouldContain "\"dialect\": \"MSSQL\""
            run.stdout shouldContain "\"blockers\": []"
            run.stdout shouldContain "\"operationsRendered\":1"
            run.stdout shouldContain "\"kind\":\"AddColumn\""
            run.stdout shouldContain "\"path\":[\"users\",\"email\"]"
        }
    }

    test("data profile against an mssql source reaches the connection instead of being refused") {
        // Der Gegenbeweis zur frueheren Ablehnung: kein Exit 2 mit
        // Gate-Meldung mehr, sondern der Verbindungsfehler des Ports, an dem
        // niemand lauscht. Das Kommando kommt also bis zum Treiber.
        val run = runRealCli(listOf("data", "profile", "--source", UNREACHABLE_MSSQL_URL))
        withClue("--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 4
            run.stderr shouldNotContain "does not support dialect mssql yet"
        }
    }
})

/** Port 1 lauscht nirgends — ein Verbindungsversuch wuerde sichtbar scheitern (Exit 4/7). */
private const val UNREACHABLE_MSSQL_URL = "mssql://sa:Gate_E2E_Pa55word@127.0.0.1:1/dmigrate_gate"

private val MINIMAL_SCHEMA = """
    schema_format: "1.0"
    name: "mssql-gate-e2e"
    version: "1.0.0"

    tables:
      users:
        columns:
          id:
            type: identifier
            auto_increment: true
          name:
            type: text
            max_length: 100
            required: true
          email:
            type: text
            max_length: 120
        primary_key: [id]
""".trimIndent()

/** Dasselbe Schema ohne `email` — der Migrate-Plan muss die Spalte hinzufuegen. */
private val SCHEMA_WITHOUT_EMAIL = MINIMAL_SCHEMA
    .lines()
    .filterIndexed { i, line ->
        val emailBlock = MINIMAL_SCHEMA.lines().indexOfFirst { it.trim() == "email:" }
        i !in emailBlock..(emailBlock + 2)
    }
    .joinToString("\n")
