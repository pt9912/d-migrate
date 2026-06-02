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
import kotlin.io.path.writeText

class SchemaMigrateCommandOverlayTest : FunSpec({

    fun cli() = DMigrate().subcommands(SchemaCommand())

    fun resourcePath(name: String): Path =
        Path.of(SchemaMigrateCommandOverlayTest::class.java.getResource("/$name")!!.toURI())

    test("schema migrate reports overlay decode failures instead of failing before preflight") {
        val dir = Files.createTempDirectory("dmigrate-overlay-cli")
        val overlay = dir.resolve("bad-overlay.json")
        val report = dir.resolve("report.json")
        overlay.writeText(
            """
            {
              "formatVersion": "migration-overlay.v1",
              "overlayKind": "using-expression",
              "sourceFingerprint": "src-fp",
              "targetFingerprint": "dst-fp",
              "dialect": "postgresql",
              "entries": [
                {
                  "kind": "approve-risk",
                  "id": "manual"
                }
              ],
              "createdAt": "2026-05-12T10:15:30Z",
              "createdByVersion": "d-migrate-test",
              "overlayHash": "not-a-real-hash"
            }
            """.trimIndent(),
        )

        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "schema",
                    "migrate",
                    "--source",
                    resourcePath("valid-schema.yaml").toString(),
                    "--target",
                    resourcePath("valid-schema.yaml").toString(),
                    "--dialect",
                    "postgresql",
                    "--plan-only",
                    "--report",
                    report.toString(),
                    "--migration-overlay",
                    overlay.toString(),
                ),
            )
        }

        ex.statusCode shouldBe 8
        val renderedReport = Files.readString(report)
        renderedReport shouldContain "\"overlays\":"
        renderedReport shouldContain "\"overlayHash\":\"<unavailable>\""
        renderedReport shouldContain "\"diagnosticCode\":\"OVERLAY_UNKNOWN_ENTRY_KIND\""
    }

    test("schema migrate reports malformed overlay JSON through the preflight report") {
        val dir = Files.createTempDirectory("dmigrate-overlay-cli")
        val overlay = dir.resolve("malformed-overlay.json")
        val report = dir.resolve("report.json")
        overlay.writeText("""{"formatVersion":"migration-overlay.v1","entries":[""")

        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "schema",
                    "migrate",
                    "--source",
                    resourcePath("valid-schema.yaml").toString(),
                    "--target",
                    resourcePath("valid-schema.yaml").toString(),
                    "--dialect",
                    "postgresql",
                    "--plan-only",
                    "--report",
                    report.toString(),
                    "--migration-overlay",
                    overlay.toString(),
                ),
            )
        }

        ex.statusCode shouldBe 8
        val renderedReport = Files.readString(report)
        renderedReport shouldContain "\"overlays\":"
        renderedReport shouldContain "\"overlayHash\":\"<unavailable>\""
        renderedReport shouldContain "\"diagnosticCode\":\"OVERLAY_FIELD_TYPE_MISMATCH\""
    }
})
