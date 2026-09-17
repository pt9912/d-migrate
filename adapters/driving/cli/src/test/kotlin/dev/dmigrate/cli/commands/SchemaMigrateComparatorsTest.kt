package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.core.diff.TargetProjection
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `schema migrate` vergleicht **wortgleich** (ADR 0056, AK 4 des Slices):
 * keine Schreibweise-Faltung und keine Erzeugungs-Projektion von
 * `schema compare`. Geprueft an genau den Comparatoren, die der Befehl
 * verdrahtet — einmal durch den Befehl selbst, einmal am Objekt.
 */
class SchemaMigrateComparatorsTest : FunSpec({

    /** Dieselbe Tabelle; nur CHECK-Ausdruck und Index-Praedikat werden variiert. */
    fun schemaYaml(check: String, predicate: String): String = """
        schema_format: "1.0"
        name: shop
        version: "1"
        tables:
          orders:
            columns:
              id: { type: integer, required: true }
              quantity: { type: integer }
              status: { type: text, max_length: 20 }
            primary_key: [id]
            indices:
              - name: ix_open
                columns: [status]
                where: "$predicate"
            constraints:
              - name: ck_qty
                type: check
                expression: "$check"
    """.trimIndent()

    fun migrate(current: String, desired: String): String {
        val dir = Files.createTempDirectory("dmigrate-migrate-comparator")
        val currentFile = dir.resolve("current.yaml").apply { writeText(current) }
        val desiredFile = dir.resolve("desired.yaml").apply { writeText(desired) }
        val output = dir.resolve("up.sql")
        val report = dir.resolve("report.json")
        try {
            DMigrate().subcommands(SchemaCommand()).parse(
                listOf(
                    "schema", "migrate",
                    "--source", desiredFile.toString(),
                    "--target", "file:$currentFile",
                    "--dialect", "postgresql",
                    "--output", output.toString(),
                    "--report", report.toString(),
                    "--report-format", "json",
                ),
            )
        } catch (_: ProgramResult) {
            // Ein Blocker ist hier ebenso eine Meldung wie eine Operation.
        }
        return listOf(output, report).filter(Files::exists).joinToString("\n") { it.readText() }
    }

    context("durch den Befehl: eine reine Schreibweise-Differenz wird geplant") {

        val pg = schemaYaml(check = "(quantity > 0)", predicate = "((status)::text <> 'DONE'::text)")

        test("control: the same file on both sides plans nothing for the two objects") {
            val out = migrate(pg, pg)
            out shouldNotContain "ck_qty"
            out shouldNotContain "ix_open"
        }

        test("CHECK and index predicate in another spelling are both reported") {
            // `schema compare` setzt beides gleich (Klammern, Leerraum, der
            // Cast an einer Text-Spalte); `schema migrate` darf es nicht.
            val mssql = schemaYaml(check = "quantity>(0)", predicate = "status<>'DONE'")
            val out = migrate(pg, mssql)
            withClue(out) {
                out shouldContain "ck_qty"
                out shouldContain "ix_open"
            }
        }
    }

    context("am Objekt: beide Comparatoren vergleichen wortgleich") {

        fun table(check: String, sequenceName: String?, legacySerial: Boolean = false) = SchemaDefinition(
            name = "shop", version = "1",
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(
                            NeutralType.BigInteger,
                            generation = ColumnGeneration.Identity(
                                sequenceName = sequenceName,
                                legacySerialSyntax = legacySerial,
                            ),
                        ),
                        "status" to ColumnDefinition(NeutralType.Text(maxLength = 20)),
                        "qty" to ColumnDefinition(NeutralType.Integer),
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(IndexDefinition(name = "ix", columns = listOf(IndexColumn("status")))),
                    constraints = listOf(
                        ConstraintDefinition(name = "ck", type = ConstraintType.CHECK, expression = check),
                    ),
                ),
            ),
        )

        val strictProjection = TargetProjection(type = { it })

        test("a spelling-only CHECK difference is a change") {
            // Kein Wertevorrat (`status = 'x'` faltete der ziel-bewusste
            // Vergleich als Enum-CHECK, ADR 0055): Klammern, Leerraum und der
            // Cast an einer Ganzzahl-Spalte.
            val pg = table("(((qty)::bigint > (0)::bigint) AND (qty < 10))", "public.orders_id_seq")
            val other = table("qty>0 AND qty<(10)", "public.orders_id_seq")
            SchemaMigrateComparators.strict(pg, other).isEmpty() shouldBe false
            SchemaMigrateComparators.targetAware(pg, other, strictProjection, null, null).isEmpty() shouldBe false
        }

        test("the identity sequence name is compared, not projected away as in schema compare") {
            val pg = table("qty > 0", "public.orders_id_seq")
            val mysql = table("qty > 0", null)
            SchemaMigrateComparators.strict(pg, mysql).isEmpty() shouldBe false
        }

        test("legacy_serial_syntax is compared: on PostgreSQL it renders a different column") {
            val identity = table("qty > 0", null)
            val autoIncrement = table("qty > 0", null, legacySerial = true)
            SchemaMigrateComparators.strict(identity, autoIncrement).isEmpty() shouldBe false
            SchemaMigrateComparators.targetAware(identity, autoIncrement, strictProjection, null, null)
                .isEmpty() shouldBe false
        }

        test("control: equal schemas are equal") {
            val pg = table("qty > 0", "s")
            SchemaMigrateComparators.strict(pg, pg).isEmpty() shouldBe true
            SchemaMigrateComparators.targetAware(pg, pg, strictProjection, null, null).isEmpty() shouldBe true
        }
    }
})
