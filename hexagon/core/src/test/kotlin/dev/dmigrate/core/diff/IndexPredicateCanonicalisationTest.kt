package dev.dmigrate.core.diff

import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

/**
 * Die zwei Faltungszweige, die dem Compare-Pfad fehlten (ADR 0056): das
 * **Index-Praedikat** und der **Leerraum um Kommas** im CHECK-Pfad.
 *
 * Die Paare sind die Schreibweisen, in denen zwei Reverses desselben Schemas
 * dasselbe Praedikat zurueckgeben. Daneben stehen die Faelle, die ein Fund
 * bleiben muessen — darunter die Umschreibung `= ANY(ARRAY[…])` gegen
 * `IN (…)`, die keine Schreibweise ist (ADR 0055).
 */
class IndexPredicateCanonicalisationTest : FunSpec({

    fun indexed(where: String?, keys: List<IndexColumn> = listOf(IndexColumn("status"))) = SchemaDefinition(
        name = "app", version = "1.0",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer),
                    "status" to ColumnDefinition(NeutralType.Text()),
                ),
                primaryKey = listOf("id"),
                indices = listOf(IndexDefinition(name = "ix_order_open", columns = keys, where = where)),
            ),
        ),
    )

    /** `schema compare` — mit Kanonisierung. */
    fun compare(left: String?, right: String?) =
        SchemaComparator(canonicalizeRawExpressions = true).compare(indexed(left), indexed(right))

    /** `schema migrate` — ohne. */
    fun migrate(left: String?, right: String?) =
        SchemaComparator().compare(indexed(left), indexed(right))

    context("Zweig 1: das Index-Praedikat — Schreibweise ist keine Aenderung") {

        test("identifier quoting") {
            compare("(status <> 'DONE')", "(\"status\" <> 'DONE')").tablesChanged.shouldBeEmpty()
            compare("(status <> 'DONE')", "([status] <> 'DONE')").tablesChanged.shouldBeEmpty()
        }

        test("whitespace and the outer parentheses") {
            compare("(status <> 'DONE')", "status<>'DONE'").tablesChanged.shouldBeEmpty()
        }

        test("the whitespace after a list comma") {
            compare("status IN ('NEW','PAID')", "status IN ('NEW', 'PAID')").tablesChanged.shouldBeEmpty()
        }

        test("a cast PostgreSQL adds to a text literal") {
            compare("(status <> 'DONE'::text)", "status <> 'DONE'").tablesChanged.shouldBeEmpty()
        }

        test("the measured pair: the cast falls and the comma gap with it") {
            compare(
                "status = ANY (ARRAY['NEW'::text, 'PAID'::text])",
                "status = ANY (ARRAY['NEW','PAID'])",
            ).tablesChanged.shouldBeEmpty()
        }
    }

    context("Zweig 1: was keine Schreibweise ist, bleibt ein Fund") {

        test("a different literal") {
            compare("status <> 'DONE'", "status <> 'VOID'").tablesChanged.single().indicesChanged.shouldNotBeEmpty()
        }

        test("the rewrite = ANY(ARRAY[…]) against IN (…) is a form, not a spelling (ADR 0055)") {
            compare("status = ANY (ARRAY['NEW','PAID'])", "status IN ('NEW','PAID')")
                .tablesChanged.single().indicesChanged.shouldNotBeEmpty()
        }

        test("a comma inside a literal is text") {
            compare("status = 'a,b'", "status = 'a, b'").tablesChanged.shouldNotBeEmpty()
        }

        test("one side without a predicate") {
            compare("status <> 'DONE'", null).tablesChanged.shouldNotBeEmpty()
        }

        test("a key expression stays verbatim, even in compare") {
            val left = indexed(null, listOf(IndexColumn.expression("upper(status)")))
            val right = indexed(null, listOf(IndexColumn.expression("upper(\"status\")")))
            SchemaComparator(canonicalizeRawExpressions = true).compare(left, right)
                .tablesChanged.shouldNotBeEmpty()
        }

        test("the reported change carries both predicates unfolded") {
            val change = compare("status <> 'DONE'", "status  <>  'VOID'")
                .tablesChanged.single().indicesChanged.single()
            change.before.where shouldBe "status <> 'DONE'"
            change.after.where shouldBe "status  <>  'VOID'"
        }
    }

    context("Zweig 1: der Rueckzug gilt auch am Index-Praedikat (ADR 0056)") {

        test("a comment whose extent changes is not folded") {
            compare("status <> 'DONE' -- x\nAND id > 0", "status <> 'DONE' -- x AND id > 0")
                .tablesChanged.shouldNotBeEmpty()
        }

        test("a literal differing only in whitespace or quoting stays a change") {
            compare("status = 'a  b'", "status = 'a b'").tablesChanged.shouldNotBeEmpty()
            compare("status = '\"a\"'", "status = 'a'").tablesChanged.shouldNotBeEmpty()
        }

        test("a quoting the scanner cannot delimit is not folded") {
            compare("status = \$\$a  b\$\$", "status = \$\$a b\$\$").tablesChanged.shouldNotBeEmpty()
            compare("status = 'a\\'  b\\''", "status = 'a\\' b\\''").tablesChanged.shouldNotBeEmpty()
        }
    }

    context("Der Migrate-Pfad und der Fingerabdruck falten nicht") {

        test("migrate's comparator still reports a pure spelling difference") {
            migrate("(status <> 'DONE')", "(\"status\" <> 'DONE')")
                .tablesChanged.single().indicesChanged.shouldNotBeEmpty()
            migrate("status IN ('NEW','PAID')", "status IN ('NEW', 'PAID')")
                .tablesChanged.single().indicesChanged.shouldNotBeEmpty()
        }

        test("the fingerprint keeps both spellings apart and its algorithm does not move") {
            val left = indexed("status IN ('NEW','PAID')")
            val right = indexed("status IN ('NEW', 'PAID')")
            MigrationFingerprint.compute(left) shouldNotBe MigrationFingerprint.compute(right)
            MigrationFingerprint.project(left).shouldStartWith("algorithm=schema-fingerprint-v17\n")
        }
    }

    context("Zweig 2: der Leerraum um Kommas im CHECK-Pfad") {

        fun checked(expression: String) = SchemaDefinition(
            name = "app", version = "1.0",
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(name = "ck_status", type = ConstraintType.CHECK, expression = expression),
                    ),
                ),
            ),
        )

        test("a list comma gap is not a change") {
            SchemaComparator(canonicalizeRawExpressions = true)
                .compare(checked("status IN ('NEW','PAID')"), checked("status IN ('NEW', 'PAID')"))
                .tablesChanged.shouldBeEmpty()
            ConstraintDiffContract.canonicallyEqual("coalesce(a,b) > 0", "coalesce(a , b) > 0") shouldBe true
        }

        test("the comma inside a literal stays protected") {
            ConstraintDiffContract.canonicallyEqual("note = 'a,b'", "note = 'a, b'") shouldBe false
        }

        test("migrate still reports the comma gap") {
            SchemaComparator()
                .compare(checked("status IN ('NEW','PAID')"), checked("status IN ('NEW', 'PAID')"))
                .tablesChanged.shouldNotBeEmpty()
        }
    }
})
