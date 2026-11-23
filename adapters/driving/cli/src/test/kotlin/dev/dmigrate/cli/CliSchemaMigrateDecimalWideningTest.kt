package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * Issue #79 ueber die CLI, mit der Reproduktion aus dem Issue: `schema migrate` (PostgreSQL, Datei
 * gegen Datei) blockierte jede Aenderung von `precision`/`scale` einer `decimal`-Spalte mit Exit 8
 * (`PG_USING_OVERLAY_MISSING`), auch die verlustfreie Erweiterung `DECIMAL(12,4)` -> `DECIMAL(14,4)`.
 */
class CliSchemaMigrateDecimalWideningTest : FunSpec({

    fun run(vararg args: String): Int = try {
        DMigrate().subcommands(SchemaCommand()).parse(args.toList())
        0
    } catch (e: ProgramResult) {
        e.statusCode
    }

    fun yaml(precision: Int, scale: Int) = """
        schema_format: "1.0"
        name: "repro"
        version: "1.0.0"
        tables:
          obj_order:
            columns:
              id: { type: uuid, required: true }
              a_amount: { type: decimal, precision: $precision, scale: $scale }
            primary_key: [id]
    """.trimIndent() + "\n"

    fun write(dir: Path, name: String, content: String): Path =
        dir.resolve(name).also { Files.writeString(it, content) }

    fun migrate(dir: Path, precision: Int, scale: Int): Int {
        val current = write(dir, "d1.yaml", yaml(12, 4))
        val desired = write(dir, "dw.yaml", yaml(precision, scale))
        return run(
            "schema", "migrate", "--source", desired.toString(), "--target", "file:$current",
            "--dialect", "postgresql", "--output", dir.resolve("V.sql").toString(),
            "--report", dir.resolve("V.report.yaml").toString(), "--report-format", "yaml",
        )
    }

    test("DECIMAL(12,4) -> DECIMAL(14,4): Exit 0, TYPE ohne USING") {
        val dir = Files.createTempDirectory("cli-decimal-widen")

        val exit = migrate(dir, 14, 4)

        withClue(Files.readString(dir.resolve("V.report.yaml"))) { exit shouldBe 0 }
        val sql = Files.readString(dir.resolve("V.sql"))
        sql shouldContain "ALTER TABLE \"obj_order\" ALTER COLUMN \"a_amount\" TYPE DECIMAL(14,4);"
        sql shouldNotContain "USING"
    }

    for ((precision, scale) in listOf(10 to 4, 12 to 2, 12 to 6)) {
        test("Verengung DECIMAL(12,4) -> DECIMAL($precision,$scale) bleibt Exit 8 (PG_USING_OVERLAY_MISSING)") {
            val dir = Files.createTempDirectory("cli-decimal-narrow")

            val exit = migrate(dir, precision, scale)

            val report = Files.readString(dir.resolve("V.report.yaml"))
            withClue(report) {
                exit shouldBe 8
                report shouldContain "PG_USING_OVERLAY_MISSING"
            }
        }
    }
})
