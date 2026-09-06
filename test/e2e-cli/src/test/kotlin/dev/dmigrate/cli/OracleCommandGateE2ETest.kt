package dev.dmigrate.cli

import dev.dmigrate.cli.integration.runRealCli
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/**
 * Oracle Slice 1a/2 (docs/planning/in-progress/oracle-dialect-scoping.md):
 * Gate-Ablehnungen des `DialectCommandGate` (ADR 0052) als Subprozess-E2E
 * gegen die ECHTE CLI — containerlos.
 *
 * Jeder Fall muss mit Exit 2 + Gate-Meldung enden, BEVOR eine Verbindung
 * versucht wird: die oracle-URLs zeigen auf einen Port, an dem niemand
 * lauscht — ein Verbindungsversuch waere Exit 4/7, nicht 2. Damit ist
 * pro Kommando belegt, dass das Gate an der Kommando-Grenze sitzt
 * (Kommando-Verfuegbarkeits-Tabelle im Plan-Dokument). Der Slice, der ein
 * Kommando fuer oracle liefert, nimmt es aus `GatedCommand` — und kippt
 * den zugehoerigen Fall hier in einen Funktions-E2E um (`schema generate`
 * und `export <tool>`: Slice 2, siehe `OracleSchemaGenerateE2ETest`).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class OracleCommandGateE2ETest : FunSpec({

    lateinit var tmp: Path
    lateinit var schemaYaml: Path
    lateinit var rowsJson: Path

    beforeSpec {
        tmp = Files.createTempDirectory("dmigrate-e2e-oracle-gate-")
        schemaYaml = tmp.resolve("schema.yaml").apply { writeText(MINIMAL_SCHEMA) }
        rowsJson = tmp.resolve("users.json").apply { writeText("""[{"id":1,"name":"alice"}]""") }
    }

    afterSpec {
        tmp.deleteRecursively()
    }

    fun expectGateRefusal(display: String, args: List<String>) {
        val run = runRealCli(args)
        withClue("args=$args\n--- stdout ---\n${run.stdout}\n--- stderr ---\n${run.stderr}") {
            run.exitCode shouldBe 2
            run.stderr shouldContain "$display does not support dialect oracle yet"
            run.stderr shouldContain "ADR 0052"
            run.stderr shouldContain "schema reverse"
        }
    }

    test("data export from an oracle source is refused before any connection attempt") {
        expectGateRefusal(
            "data export",
            listOf(
                "data", "export",
                "--source", UNREACHABLE_ORACLE_URL,
                "--tables", "users",
                "--format", "json",
                "--output", tmp.resolve("export.json").absolutePathString(),
            ),
        )
    }

    test("data import into an oracle target is refused before any connection attempt") {
        expectGateRefusal(
            "data import",
            listOf(
                "data", "import",
                "--target", UNREACHABLE_ORACLE_URL,
                "--source", rowsJson.absolutePathString(),
                "--table", "users",
                "--format", "json",
            ),
        )
    }

    test("data transfer refuses oracle as source before any connection attempt") {
        expectGateRefusal(
            "data transfer",
            listOf(
                "data", "transfer",
                "--source", UNREACHABLE_ORACLE_URL,
                "--target", "sqlite://" + tmp.resolve("transfer-target.db").absolutePathString(),
                "--tables", "users",
            ),
        )
    }

    test("data transfer refuses oracle as target before any connection attempt") {
        expectGateRefusal(
            "data transfer",
            listOf(
                "data", "transfer",
                "--source", "sqlite://" + tmp.resolve("transfer-source.db").absolutePathString(),
                "--target", UNREACHABLE_ORACLE_URL,
                "--tables", "users",
            ),
        )
    }

    test("schema migrate --dialect oracle against a file target is refused at the command boundary") {
        expectGateRefusal(
            "schema migrate",
            listOf(
                "schema", "migrate",
                "--source", schemaYaml.absolutePathString(),
                "--target", "file:" + schemaYaml.absolutePathString(),
                "--dialect", "oracle",
                "--plan-only",
            ),
        )
    }

    test("data profile against an oracle source is refused before any connection attempt") {
        expectGateRefusal(
            "data profile",
            listOf("data", "profile", "--source", UNREACHABLE_ORACLE_URL),
        )
    }
})

/** Port 1 lauscht nirgends — ein Verbindungsversuch wuerde sichtbar scheitern (Exit 4/7). */
private const val UNREACHABLE_ORACLE_URL = "oracle://app:Gate_E2E_Pa55word@127.0.0.1:1/orclpdb1"

private val MINIMAL_SCHEMA = """
    schema_format: "1.0"
    name: "oracle-gate-e2e"
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
        primary_key: [id]
""".trimIndent()
