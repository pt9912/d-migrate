package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Ein Cast in einem rohen Ausdruck faellt nur, wenn er **nur Schreibweise**
 * ist — entschieden mit den Spaltentypen der Tabelle dieser Seite (P9,
 * ADR 0056: „ein Cast auf den Typ, den der Operand ohnehin hat").
 *
 * Die Gegenproben sind der Kern: ein Cast aendert den Wert eines Literals
 * nicht, kann aber die **umgebende Operation** umtypen. Die vier ersten Paare
 * sind gegen PostgreSQL gemessen (Ergebnis in Klammern: mit Cast / ohne).
 */
class ColumnCastFoldTest : FunSpec({

    val columns = mapOf(
        "status" to NeutralType.Text(maxLength = 20),
        "note" to NeutralType.Text(),
        "email" to NeutralType.Text(maxLength = 254),
        "code" to NeutralType.Char(length = 3),
        "qty" to NeutralType.Integer,
        "small" to NeutralType.SmallInt,
        "big" to NeutralType.BigInteger,
        "price" to NeutralType.Decimal(10, 2),
        "x" to NeutralType.Float(FloatPrecision.DOUBLE),
        "r" to NeutralType.Float(FloatPrecision.SINGLE),
        "d" to NeutralType.Date,
        "ts" to NeutralType.DateTime(timezone = false),
        "tstz" to NeutralType.DateTime(timezone = true),
        "tm" to NeutralType.Time,
        // `citext` liest der PostgreSQL-Reverse als benutzerdefinierten Typ.
        "ci" to NeutralType.Enum(refType = "citext"),
        "flag" to NeutralType.BooleanType,
        // `inet` und `interval` liest der PostgreSQL-Reverse als Text ohne
        // Laenge (R301) — wie eine echte `text`-Spalte.
        "ip" to NeutralType.Text(),
        "mail" to NeutralType.Email,
        // Einen `integer`-Identity-Primaerschluessel liest der
        // PostgreSQL-Reverse als `identifier`.
        "ident" to NeutralType.Identifier(autoIncrement = true),
    )

    fun equal(left: String, right: String, leftTypes: Map<String, NeutralType> = columns, rightTypes: Map<String, NeutralType> = columns) =
        ConstraintDiffContract.canonicallyEqual(left, right, ColumnTypes(leftTypes), ColumnTypes(rightTypes))

    fun table(check: String?, predicate: String?) = SchemaDefinition(
        name = "app", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = columns.mapValues { ColumnDefinition(it.value) } + ("id" to ColumnDefinition(NeutralType.Integer)),
                primaryKey = listOf("id"),
                constraints = listOfNotNull(
                    check?.let { ConstraintDefinition(name = "ck", type = ConstraintType.CHECK, expression = it) },
                ),
                indices = listOfNotNull(
                    predicate?.let { IndexDefinition(name = "ix", columns = listOf(IndexColumn("id")), where = it) },
                ),
            ),
        ),
    )

    fun compareCheck(left: String, right: String) =
        SchemaComparator(canonicalizeRawExpressions = true).compare(table(left, null), table(right, null)).isEmpty()

    fun compareIndex(left: String, right: String) =
        SchemaComparator(canonicalizeRawExpressions = true).compare(table(null, left), table(null, right)).isEmpty()

    fun migrateCheck(left: String, right: String) =
        SchemaComparator().compare(table(left, null), table(right, null)).isEmpty()

    /** Die Pflicht-Gleichsetzungen — in CHECK **und** Index-Praedikat. */
    val folded = listOf(
        "(status)::text = 'x'::text" to "status = 'x'",
        "(price > (0)::numeric)" to "price > 0",
        "(x > (0)::double precision)" to "x > 0",
        "d > '2024-01-01'::date" to "d > '2024-01-01'",
        "code = 'A'::bpchar" to "code = 'A'",
    )

    context("Gleichgesetzt: der Cast hat den Typ, den der Operand ohnehin hat") {

        test("the required pairs fold in CHECK and in the index predicate") {
            for ((pg, other) in folded) {
                withClue("$pg <-> $other") {
                    equal(pg, other) shouldBe true
                    compareCheck(pg, other) shouldBe true
                    compareIndex(pg, other) shouldBe true
                }
            }
        }

        test("PostgreSQL's LIKE on a varchar column: column cast and literal cast") {
            equal("((email)::text ~~ '%@%'::text)", "email like '%@%'") shouldBe true
            equal("(note ~~ '%@%'::text)", "note like '%@%'") shouldBe true
        }

        test("a column widened to a broader integer or to numeric") {
            equal("((qty)::bigint > 0)", "qty > 0") shouldBe true
            equal("((qty)::numeric > 2.5)", "qty > 2.5") shouldBe true
            equal("((small)::integer > 0)", "small > 0") shouldBe true
        }

        test("literal casts of the column's own type family") {
            equal("(ts > '2024-01-01 00:00:00'::timestamp without time zone)", "ts > '2024-01-01 00:00:00'") shouldBe true
            equal("(tstz > '2024-01-01'::timestamp with time zone)", "tstz > '2024-01-01'") shouldBe true
            equal("(tm > '08:00:00'::time without time zone)", "tm > '08:00:00'") shouldBe true
            equal("(r > (0)::double precision)", "r > 0") shouldBe true
            equal("(r > '0.5'::real)", "r > '0.5'") shouldBe true
            equal("(qty > '-5'::integer)", "qty > '-5'") shouldBe true
            equal("(qty > 5::smallint)", "qty > 5") shouldBe true
            equal("(price >= '0.5'::numeric)", "price >= '0.5'") shouldBe true
        }

        test("a column cast to text on a text column with a length or an email column") {
            equal("((status)::text <> 'x'::text)", "status <> 'x'") shouldBe true
            equal("((mail)::text = 'x'::text)", "mail = 'x'") shouldBe true
        }

        test("an identifier counts as the widest integer: only a cast to bigint or numeric falls") {
            equal("((ident)::bigint > 0)", "ident > 0") shouldBe true
            equal("((ident)::numeric > 0)", "ident > 0") shouldBe true
            equal("(ident > (5)::bigint)", "ident > 5") shouldBe true
        }

        test("the literal may stand on the left of an equality") {
            equal("'x'::text = status", "'x' = status") shouldBe true
        }

        test("a text column in front of ANY: the column cast and a text-typed array") {
            equal(
                "((status)::text = ANY ((ARRAY['a'::character varying, 'b'::character varying])::text[]))",
                "status = ANY ((ARRAY['a'::character varying, 'b'::character varying])::text[])",
            ) shouldBe true
            equal("(note = ANY (ARRAY['a'::text, 'b'::text]))", "note = ANY (ARRAY['a','b'])") shouldBe true
        }

        test("the comparison may sit in a composition, behind NOT or in grouping parentheses") {
            equal("((status)::text = 'x'::text) AND (price > (0)::numeric)", "status = 'x' AND price > 0") shouldBe true
            equal("NOT ((status)::text = 'x'::text)", "NOT (status = 'x')") shouldBe true
        }
    }

    context("Ein Fund: der Cast kann die umgebende Operation umtypen") {

        test("next to an arithmetic operator (PG: t / f at qty = 3)") {
            equal("qty / 2::numeric > 1", "qty / 2 > 1") shouldBe false
            equal("(qty)::numeric / 2 > 1", "qty / 2 > 1") shouldBe false
            compareCheck("qty / 2::numeric > 1", "qty / 2 > 1") shouldBe false
        }

        test("a cast right at the comparison, but with arithmetic on its other side") {
            // Hier steht der Cast unmittelbar am Vergleich — nur die Grenze
            // des Operanden verhindert die Faltung. `1 < 3::numeric / 2` ist
            // wahr, `1 < 3 / 2` nicht; `5 / 2::numeric > 2` ebenso.
            equal("1 < (qty)::numeric / 2", "1 < qty / 2") shouldBe false
            equal("5 / (qty)::numeric > 2", "5 / qty > 2") shouldBe false
            equal("price > (1)::numeric / 3", "price > 1 / 3") shouldBe false
            equal("2 * (0)::numeric < price", "2 * 0 < price") shouldBe false
        }

        test("a comparison inside an argument list or on a BETWEEN level") {
            equal("coalesce(flag AND note = 'a'::text, false)", "coalesce(flag AND note = 'a', false)") shouldBe false
            equal("price BETWEEN 1 AND price > (0)::numeric", "price BETWEEN 1 AND price > 0") shouldBe false
        }

        test("on a citext column (PG: f / t)") {
            equal("ci = 'FOO'::text", "ci = 'FOO'") shouldBe false
        }

        test("a text literal against char(n) (PG: f / t)") {
            equal("code = 'a  '::text", "code = 'a  '") shouldBe false
            compareIndex("code = 'a  '::text", "code = 'a  '") shouldBe false
        }

        test("a bpchar literal against varchar (PG: t / f)") {
            equal("status = 'a  '::bpchar", "status = 'a  '") shouldBe false
        }

        test("a date literal against a timestamp column") {
            equal("ts > '2024-01-01 12:00'::date", "ts > '2024-01-01 12:00'") shouldBe false
            equal("tstz > '2024-01-01'::timestamp", "tstz > '2024-01-01'") shouldBe false
        }

        test("a cast on a name that is no column of the table") {
            equal("(other)::text = 'x'", "other = 'x'") shouldBe false
            equal("other = 'x'::text", "other = 'x'") shouldBe false
            equal("status = 'x'::text", "status = 'x'", rightTypes = columns - "status") shouldBe true
            equal("status = 'x'", "status = 'x'::text", rightTypes = columns - "status") shouldBe false
        }

        test("a cast with a type modifier") {
            equal("status = 'abc'::varchar(2)", "status = 'abc'") shouldBe false
            equal("price > (0)::numeric(10,2)", "price > 0") shouldBe false
        }

        test("a column cast that can change the value") {
            equal("(code)::text = 'a'::text", "code = 'a'") shouldBe false
            equal("((price)::integer > 5)", "price > 5") shouldBe false
            equal("(price::integer > 5)", "price > 5") shouldBe false
            equal("((qty)::smallint > 5)", "qty > 5") shouldBe false
            equal("((x)::numeric > 0)", "x > 0") shouldBe false
        }

        test("a literal cast that rounds or fails where the plain literal does not") {
            equal("(r > (0.1)::real)", "r > 0.1") shouldBe false
            equal("(r > '0.1'::double precision)", "r > '0.1'") shouldBe false
            equal("(qty > 70000::smallint)", "qty > 70000") shouldBe false
            equal("(small = '70000'::integer)", "small = '70000'") shouldBe false
            equal("(x > (0)::numeric)", "x > 0") shouldBe false
        }

        test("a widened column against an untyped string literal") {
            equal("((qty)::bigint = '3000000000')", "qty = '3000000000'") shouldBe false
        }

        test("inside an argument list, after a function name, on a BETWEEN level") {
            equal("coalesce((status)::text, '') = 'x'", "coalesce(status, '') = 'x'") shouldBe false
            equal("price > f (0)::numeric", "price > f (0)") shouldBe false
            equal("price BETWEEN (0)::numeric AND 5", "price BETWEEN 0 AND 5") shouldBe false
            equal("status IN ('a'::text, 'b'::text)", "status IN ('a', 'b')") shouldBe false
        }

        test("LIKE folds only a text pattern on a text column") {
            equal("code ~~ 'a  '::bpchar", "code like 'a  '") shouldBe false
            equal("code ~~ 'A%'::text", "code like 'A%'") shouldBe false
            equal("status ~~ (other)::text", "status like other") shouldBe false
        }

        test("a type name in another spelling is another type") {
            equal("status = 'x'::TEXT", "status = 'x'") shouldBe false
            equal("status = 'x'::character", "status = 'x'") shouldBe false
        }

        test("a decimal literal cast to double precision: numeric without precision reads as float") {
            // Gemessen (PostgreSQL 18.6): `numeric` ohne Praezision schreibt
            // `(nu > 0.5)`, `double precision` `(x > (0.5)::double precision)`;
            // der Reverse liest beide Spalten als `float`.
            equal("(x > (0.5)::double precision)", "x > 0.5") shouldBe false
            equal("(r > (0.5)::double precision)", "r > 0.5") shouldBe false
            equal("(x > (1e3)::double precision)", "x > 1e3") shouldBe false
            compareCheck("(x > (0.5)::double precision)", "(x > 0.5)") shouldBe false
            compareIndex("(x > (0.5)::double precision)", "(x > 0.5)") shouldBe false
        }

        test("a column cast to text on a text column without a length (PG: `inet`, `interval` read as text)") {
            // Gemessen (PostgreSQL 18.6): `(ip)::text` gibt `10.0.0.1/32`; eine
            // echte `text`-Spalte bekommt keinen Spalten-Cast.
            equal("((ip)::text <> '10.0.0.1'::text)", "(ip <> '10.0.0.1'::text)") shouldBe false
            equal("((ip)::text <> '1 day'::text)", "ip <> '1 day'") shouldBe false
            equal("((note)::text ~~ 'a%'::text)", "note like 'a%'") shouldBe false
            compareCheck("((ip)::text <> '10.0.0.1'::text)", "(ip <> '10.0.0.1'::text)") shouldBe false
        }

        test("an identifier is not narrower than bigint, and an untyped literal never takes its width") {
            equal("((ident)::integer > 0)", "ident > 0") shouldBe false
            equal("((ident)::smallint > 0)", "ident > 0") shouldBe false
            equal("(ident = '5'::integer)", "ident = '5'") shouldBe false
            equal("(ident = '5'::bigint)", "ident = '5'") shouldBe false
        }

        test("character without a length is character(1): it never stands for char(n)") {
            equal("code = 'A'::character", "code = 'A'") shouldBe false
            equal("code = 'A'::char", "code = 'A'") shouldBe false
            compareCheck("code = 'A'::character", "code = 'A'") shouldBe false
        }

        test("an ANY array of bpchar elements compares padded (not text)") {
            equal("note = ANY (ARRAY['a  '::bpchar])", "note = ANY (ARRAY['a  '])") shouldBe false
        }

        test("an ANY array whose elements are not all string literals") {
            equal("note = ANY (ARRAY['a'::text, note])", "note = ANY (ARRAY['a', note])") shouldBe false
            equal("code = ANY (ARRAY['a'::bpchar])", "code = ANY (ARRAY['a'])") shouldBe false
        }

        test("types without a family never fold") {
            equal("flag = 'true'::boolean", "flag = 'true'") shouldBe false
        }
    }

    context("Ohne Tabellenkontext faellt kein Cast") {

        test("the context-free comparison keeps every cast") {
            ConstraintDiffContract.canonicallyEqual("email like '%@%'::text", "email like '%@%'") shouldBe false
            ConstraintDiffContract.canonicallyEqual("price > (0)::numeric", "price > 0") shouldBe false
        }

        test("a column name that two columns share without regard to case is ambiguous") {
            val ambiguous = mapOf("Status" to NeutralType.Text(), "status" to NeutralType.Text())
            equal("status = 'x'::text", "status = 'x'", ambiguous, ambiguous) shouldBe false
        }

        test("a keyword is no column, even if a column carries its name") {
            val keyword = mapOf("user" to NeutralType.Text())
            equal("user = 'x'::text", "user = 'x'", keyword, keyword) shouldBe false
        }
    }

    context("Die Spaltentypen **dieser** Seite — links und rechts verschieden") {

        /** Eine Tabelle nur mit [types]; CHECK und Index-Praedikat tragen [expression]. */
        fun sideTable(types: Map<String, NeutralType>, expression: String) = SchemaDefinition(
            name = "app", version = "1",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = types.mapValues { ColumnDefinition(it.value) } + ("id" to ColumnDefinition(NeutralType.Integer)),
                    primaryKey = listOf("id"),
                    constraints = listOf(ConstraintDefinition(name = "ck", type = ConstraintType.CHECK, expression = expression)),
                    indices = listOf(IndexDefinition(name = "ix", columns = listOf(IndexColumn("id")), where = expression)),
                ),
            ),
        )

        /** Ob CHECK **und** Index-Praedikat als unveraendert gelten (die Spalten selbst duerfen sich unterscheiden). */
        fun sameExpression(
            leftTypes: Map<String, NeutralType>,
            left: String,
            rightTypes: Map<String, NeutralType>,
            right: String,
        ): Boolean {
            val changed = SchemaComparator(canonicalizeRawExpressions = true)
                .compare(sideTable(leftTypes, left), sideTable(rightTypes, right))
                .tablesChanged.singleOrNull()
            val check = changed?.constraintsChanged.orEmpty().isEmpty()
            val index = changed?.indicesChanged.orEmpty().isEmpty()
            check shouldBe index
            return check
        }

        val varchar = mapOf("status" to NeutralType.Text(maxLength = 20))
        val bpchar = mapOf("status" to NeutralType.Char(length = 3))
        val none = emptyMap<String, NeutralType>()
        val pgCast = "((status)::text = 'x'::text)"
        val plain = "status = 'x'"

        test("the cast is decided with the column set of its own side") {
            // Nur die Seite mit dem Cast kennt die Spalte.
            sameExpression(varchar, pgCast, none, plain) shouldBe true
            sameExpression(none, plain, varchar, pgCast) shouldBe true
            // Die Seite mit dem Cast kennt sie nicht.
            sameExpression(none, pgCast, varchar, plain) shouldBe false
            sameExpression(varchar, plain, none, pgCast) shouldBe false
        }

        test("the cast is decided with the column type of its own side") {
            // Links `varchar(20)`: der Cast haelt den Wert. Rechts `char(3)`.
            sameExpression(varchar, pgCast, bpchar, plain) shouldBe true
            sameExpression(bpchar, plain, varchar, pgCast) shouldBe true
            // Links `char(3)`: derselbe Cast streicht die Leerzeichen am Ende.
            sameExpression(bpchar, pgCast, varchar, plain) shouldBe false
            sameExpression(varchar, plain, bpchar, pgCast) shouldBe false
        }
    }

    test("the migrate comparator keeps every cast") {
        for ((pg, other) in folded) {
            withClue(pg) { migrateCheck(pg, other) shouldBe false }
        }
    }
})
