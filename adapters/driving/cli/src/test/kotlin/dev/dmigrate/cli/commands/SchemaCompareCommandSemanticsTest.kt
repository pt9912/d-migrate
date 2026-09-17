package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.core.identity.ReverseScopeCodec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * `schema compare` **durch den Befehl** — an genau dem Comparator, den
 * `SchemaCompareWiring` verdrahtet (Slice-Befund M3: ein strikter Comparator
 * dort liess die Tests gruen). Dieselbe Semantik gilt fuer beide
 * MCP-Oberflaechen (`SchemaCompareRuntimeSemanticsTest`).
 */
class SchemaCompareCommandSemanticsTest : FunSpec({

    /** Exit-Code und Textreport eines Laufs Datei gegen Datei. */
    fun compare(source: String, target: String): Pair<Int, String> {
        val dir = Files.createTempDirectory("dmigrate-compare-semantics")
        val sourceFile = dir.resolve("source.yaml").apply { writeText(source) }
        val targetFile = dir.resolve("target.yaml").apply { writeText(target) }
        val original = System.out
        val capture = ByteArrayOutputStream()
        System.setOut(PrintStream(capture, true, Charsets.UTF_8))
        val exit = try {
            DMigrate().subcommands(SchemaCommand()).parse(
                listOf("schema", "compare", "--source", "file:$sourceFile", "--target", "file:$targetFile"),
            )
            0
        } catch (result: ProgramResult) {
            result.statusCode
        } finally {
            System.setOut(original)
        }
        return exit to capture.toString(Charsets.UTF_8)
    }

    fun schema(name: String, version: String, check: String, predicate: String, generation: String) = """
        schema_format: "1.0"
        name: "$name"
        version: "$version"
        tables:
          orders:
            columns:
              id: { type: biginteger, required: true, generation: $generation }
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

    val identity = "{ type: identity, mode: by_default }"
    val pgCheck = "((quantity > 0) AND ((status)::text <> 'x'::text))"
    val pgPredicate = "((status)::text <> 'DONE'::text)"

    context("die Faltung roher Ausdruecke ist verdrahtet") {

        test("a CHECK and an index predicate in another spelling, with PostgreSQL's casts, are identical") {
            val pg = schema("shop", "1", pgCheck, pgPredicate, identity)
            val mssql = schema("shop", "1", "[quantity]>(0) AND [status]<>'x'", "[status]<>'DONE'", identity)
            val (exit, report) = compare(pg, mssql)
            withClue(report) {
                exit shouldBe 0
                report shouldContain "IDENTICAL"
            }
        }

        test("control: a real change of the predicate is reported") {
            val pg = schema("shop", "1", pgCheck, pgPredicate, identity)
            val changed = schema("shop", "1", pgCheck, "status <> 'VOID'", identity)
            val (exit, report) = compare(pg, changed)
            withClue(report) {
                exit shouldBe 1
                report shouldContain "ix_open"
            }
        }
    }

    context("Reverse-Markierung und Erzeugungs-Projektion sind verdrahtet (P6, P10)") {

        val reverse = ReverseScopeCodec.REVERSE_VERSION
        val pgName = ReverseScopeCodec.postgresName("shop", "public")
        val myName = ReverseScopeCodec.mysqlName("shop")
        val pgIdentity = "{ type: identity, mode: by_default, sequence_name: public.orders_id_seq }"
        val myIdentity = "{ type: identity, mode: by_default, legacy_serial_syntax: true }"

        test("a PostgreSQL IDENTITY against MySQL's AUTO_INCREMENT: neither name nor flag is a change") {
            val (exit, report) = compare(
                schema(pgName, reverse, pgCheck, pgPredicate, pgIdentity),
                schema(myName, reverse, pgCheck, pgPredicate, myIdentity),
            )
            withClue(report) {
                exit shouldBe 0
                report shouldNotContain "legacy_serial_syntax"
                report shouldNotContain "__dmigrate_reverse__"
            }
        }

        test("two hand-written schemas with the same difference stay strict") {
            val (exit, report) = compare(
                schema("shop", "1", pgCheck, pgPredicate, pgIdentity),
                schema("shop", "1", pgCheck, pgPredicate, myIdentity),
            )
            withClue(report) {
                exit shouldBe 1
                report shouldContain "legacy_serial_syntax=true"
            }
        }
    }

    test("the kind of a changed custom type stands lowercase, as in the document") {
        fun withType(type: String) = """
            schema_format: "1.0"
            name: s
            version: "1"
            custom_types:
              st: $type
            tables:
              p: { columns: { id: { type: integer } }, primary_key: [id] }
        """.trimIndent()
        val (exit, report) = compare(
            withType("{ kind: enum, values: [a] }"),
            withType("{ kind: domain, base_type: text }"),
        )
        withClue(report) {
            exit shouldBe 1
            report shouldContain "kind: enum -> domain"
            report shouldNotContain "ENUM"
        }
    }
})
