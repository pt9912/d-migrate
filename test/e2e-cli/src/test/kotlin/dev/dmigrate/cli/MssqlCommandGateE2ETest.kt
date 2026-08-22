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
 * MSSQL Slice 1a (docs/planning/in-progress/mssql-dialect-scoping.md):
 * Gate-Ablehnungen des `DialectCommandGate` (ADR 0047) als Subprozess-E2E
 * gegen die ECHTE CLI — containerlos.
 *
 * Jeder Fall muss mit Exit 2 + Gate-Meldung enden, BEVOR eine Verbindung
 * versucht wird: die mssql-URLs zeigen auf einen Port, an dem niemand
 * lauscht — ein Verbindungsversuch waere Exit 4/7, nicht 2. Damit ist
 * pro Kommando belegt, dass das Gate an der Kommando-Grenze sitzt
 * (Kommando-Verfuegbarkeits-Tabelle im Plan-Dokument). Der Slice, der ein
 * Kommando fuer mssql liefert, nimmt es aus `GatedCommand` — und kippt
 * den zugehoerigen Fall hier in einen Funktions-E2E um (`schema generate`
 * und `export <tool>`: siehe `MssqlSchemaGenerateE2ETest`).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class MssqlCommandGateE2ETest : FunSpec({

    lateinit var tmp: Path
    lateinit var schemaYaml: Path
    lateinit var rowsJson: Path

    beforeSpec {
        tmp = Files.createTempDirectory("dmigrate-e2e-mssql-gate-")
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
            run.stderr shouldContain "$display does not support dialect mssql yet"
            run.stderr shouldContain "ADR 0047"
            run.stderr shouldContain "schema reverse"
        }
    }

    test("data export from an mssql source is refused before any connection attempt") {
        expectGateRefusal(
            "data export",
            listOf(
                "data", "export",
                "--source", UNREACHABLE_MSSQL_URL,
                "--tables", "users",
                "--format", "json",
                "--output", tmp.resolve("export.json").absolutePathString(),
            ),
        )
    }

    test("data import into an mssql target is refused before any connection attempt") {
        expectGateRefusal(
            "data import",
            listOf(
                "data", "import",
                "--target", UNREACHABLE_MSSQL_URL,
                "--source", rowsJson.absolutePathString(),
                "--table", "users",
                "--format", "json",
            ),
        )
    }

    test("data transfer refuses mssql as source before any connection attempt") {
        expectGateRefusal(
            "data transfer",
            listOf(
                "data", "transfer",
                "--source", UNREACHABLE_MSSQL_URL,
                "--target", "sqlite://" + tmp.resolve("transfer-target.db").absolutePathString(),
                "--tables", "users",
            ),
        )
    }

    test("data transfer refuses mssql as target before any connection attempt") {
        expectGateRefusal(
            "data transfer",
            listOf(
                "data", "transfer",
                "--source", "sqlite://" + tmp.resolve("transfer-source.db").absolutePathString(),
                "--target", UNREACHABLE_MSSQL_URL,
                "--tables", "users",
            ),
        )
    }

    test("schema migrate --dialect mssql against a file target is refused at the command boundary") {
        expectGateRefusal(
            "schema migrate",
            listOf(
                "schema", "migrate",
                "--source", schemaYaml.absolutePathString(),
                "--target", "file:" + schemaYaml.absolutePathString(),
                "--dialect", "mssql",
                "--plan-only",
            ),
        )
    }

    test("data profile against an mssql source is refused before any connection attempt") {
        expectGateRefusal(
            "data profile",
            listOf("data", "profile", "--source", UNREACHABLE_MSSQL_URL),
        )
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
        primary_key: [id]
""".trimIndent()
