package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * ADR 0069 ueber die CLI, mit der Reproduktion aus Issue #81: `schema migrate` (PostgreSQL, Datei gegen Datei) mit einer
 * geaenderten Werteliste einer Inline-Enum. Ohne `values_constraint` kennt der Up den Namen des alten CHECKs nicht und
 * blockiert (Exit 8); mit `values_constraint` steht der Name in der Datei, der Up loest den alten CHECK unter ihm.
 */
class CliSchemaMigrateEnumCheckNameTest : FunSpec({

    fun run(vararg args: String): Int = try {
        DMigrate().subcommands(SchemaCommand()).parse(args.toList())
        0
    } catch (e: ProgramResult) {
        e.statusCode
    }

    fun yaml(values: String, name: String?) = """
        schema_format: "1.0"
        name: "repro"
        version: "1.0.0"
        tables:
          t_a:
            columns:
              id: { type: integer, required: true }
              state: { type: enum, values: $values${name?.let { ", values_constraint: $it" }.orEmpty()} }
            primary_key: [id]
    """.trimIndent() + "\n"

    fun write(dir: Path, name: String, content: String): Path = dir.resolve(name).also { Files.writeString(it, content) }

    fun migrate(dir: Path, current: Path, desired: Path): Int = run(
        "schema", "migrate", "--source", desired.toString(), "--target", "file:$current", "--dialect", "postgresql",
        "--output", dir.resolve("up.sql").toString(), "--report", dir.resolve("up.report.yaml").toString(),
        "--report-format", "yaml",
    )

    test("benannt: Exit 0, der Up loest den alten CHECK unter dem Namen und legt den neuen an") {
        val dir = Files.createTempDirectory("cli-enum-check-name")
        val a = write(dir, "a.yaml", yaml("[ACTIVE, DISABLED]", "ck_a_state"))
        val b = write(dir, "b.yaml", yaml("[ACTIVE, DISABLED, LOCKED]", "ck_a_state"))

        val exit = migrate(dir, a, b)

        withClue(Files.readString(dir.resolve("up.report.yaml"))) { exit shouldBe 0 }
        val sql = Files.readString(dir.resolve("up.sql"))
        sql shouldContain "ALTER TABLE \"t_a\" DROP CONSTRAINT IF EXISTS \"ck_a_state\";"
        sql shouldContain "ADD CONSTRAINT \"ck_a_state\" CHECK (\"state\" IN ('ACTIVE', 'DISABLED', 'LOCKED'));"
    }

    test("nur der Name aendert sich: Exit 0, DROP des alten und ADD des neuen CHECK") {
        val dir = Files.createTempDirectory("cli-enum-check-rename")
        val a = write(dir, "a.yaml", yaml("[ACTIVE, DISABLED]", "ck_old"))
        val b = write(dir, "b.yaml", yaml("[ACTIVE, DISABLED]", "ck_new"))

        migrate(dir, a, b) shouldBe 0

        val sql = Files.readString(dir.resolve("up.sql"))
        sql shouldContain "DROP CONSTRAINT IF EXISTS \"ck_old\";"
        sql shouldContain "ADD CONSTRAINT \"ck_new\" CHECK"
    }

    test("ohne Namen: weiter Exit 8, und die Diagnose nennt values_constraint als Ausweg") {
        val dir = Files.createTempDirectory("cli-enum-check-unnamed")
        val a = write(dir, "a.yaml", yaml("[ACTIVE, DISABLED]", null))
        val b = write(dir, "b.yaml", yaml("[ACTIVE, DISABLED, LOCKED]", null))

        val exit = migrate(dir, a, b)

        val report = Files.readString(dir.resolve("up.report.yaml"))
        withClue(report) {
            exit shouldBe 8
            report shouldContain "POSTGRES_ENUM_CHECK_NAME_UNKNOWN"
            report shouldContain "values_constraint"
        }
    }

    test("values_constraint an einem ref_type ist E126, schema validate endet mit Exit 3") {
        val dir = Files.createTempDirectory("cli-enum-check-e126")
        val file = write(
            dir, "s.yaml",
            """
            schema_format: "1.0"
            name: "repro"
            version: "1.0.0"
            custom_types:
              st: { kind: enum, values: [a, b] }
            tables:
              t:
                columns:
                  id: { type: integer, required: true }
                  state: { type: enum, ref_type: st, values_constraint: ck_state }
                primary_key: [id]
            """.trimIndent() + "\n",
        )

        run("schema", "validate", "--source", file.toString()) shouldBe 3
    }
})
