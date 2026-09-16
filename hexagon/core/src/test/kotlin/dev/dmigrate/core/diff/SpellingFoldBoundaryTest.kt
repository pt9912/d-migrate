package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Die Grenze der Schreibweise-Faltung (ADR 0056, „Wann die Faltung sich
 * zurueckzieht"). Jeder Fall steht als **Paar**, das die Faltung bis 1.7.1
 * gleichsetzte, obwohl es Verschiedenes bedeuten kann — und daneben als
 * **Gegenprobe**, die zeigt, dass die Faltung dort weiter greift, wo sie darf.
 */
class SpellingFoldBoundaryTest : FunSpec({

    fun equalAsCheck(left: String, right: String) = ConstraintDiffContract.canonicallyEqual(left, right)

    fun checkSchema(expression: String) = SchemaDefinition(
        name = "app", version = "1.0",
        tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
                primaryKey = listOf("id"),
                constraints = listOf(ConstraintDefinition(name = "ck", type = ConstraintType.CHECK, expression = expression)),
            ),
        ),
    )

    fun checkChanged(left: String, right: String) =
        SchemaComparator(canonicalizeRawExpressions = true).compare(checkSchema(left), checkSchema(right))
            .tablesChanged.isNotEmpty()

    fun viewSchema(query: String) = SchemaDefinition(
        name = "app", version = "1.0",
        views = mapOf("v" to ViewDefinition(query = query)),
    )

    fun viewChanged(left: String, right: String) =
        SchemaComparator(canonicalizeRawExpressions = true).compare(viewSchema(left), viewSchema(right))
            .viewsChanged.isNotEmpty()

    context("Rueckzug: ein Kommentarzeichen ausserhalb eines Literals") {

        val broken = "a = 1 -- x\nAND b = 2"
        val commentedOut = "a = 1 -- x AND b = 2"

        test("CHECK: a line comment whose extent changes is not folded") {
            equalAsCheck(broken, commentedOut) shouldBe false
            checkChanged(broken, commentedOut) shouldBe true
        }

        test("CHECK: a block comment stops the folding altogether") {
            equalAsCheck("a = 1 /* c */ AND b = 2", "a=1 /* c */ AND b=2") shouldBe false
        }

        test("view: a line comment whose extent changes is not folded") {
            viewChanged("SELECT a FROM t -- x\nWHERE b = 2", "SELECT a FROM t -- x WHERE b = 2") shouldBe true
        }

        test("counter-check: the same texts without a comment still fold, and a literal '--' is text") {
            equalAsCheck("a = 1 AND b = 2", "a=1 AND b=2") shouldBe true
            equalAsCheck("note = '--'", "note='--'") shouldBe true
            viewChanged("SELECT '--' AS x FROM t", "SELECT  '--'  AS x FROM t") shouldBe false
        }

        test("counter-check: identical texts stay equal even with a comment") {
            equalAsCheck(broken, broken) shouldBe true
        }
    }

    context("Rueckzug: Dollar-Quoting") {

        test("CHECK: whitespace inside a dollar-quoted string is not folded") {
            equalAsCheck("note = \$\$a  b\$\$", "note = \$\$a b\$\$") shouldBe false
            equalAsCheck("note = \$q\$a  b\$q\$", "note = \$q\$a b\$q\$") shouldBe false
        }

        test("view: whitespace inside a dollar-quoted string is not folded") {
            viewChanged("SELECT \$\$a  b\$\$ FROM t", "SELECT \$\$a b\$\$ FROM t") shouldBe true
        }

        test("counter-check: dollar signs inside a standard literal are text") {
            equalAsCheck("note = '\$\$'", "note='\$\$'") shouldBe true
        }
    }

    context("Rueckzug: ein Backslash") {

        // Fuer MySQL ist das **ein** Literal (`a'  b'`), dessen Leerraum
        // zaehlt. Ein Scanner mit Standard-Quoting liest `'a\'`, dann Text,
        // dann ein leeres Literal — und faltete den Leerraum dazwischen.
        val mysqlEscaped = "note = 'a\\'  b\\''"
        val mysqlEscapedNarrow = "note = 'a\\' b\\''"

        test("CHECK: a MySQL escape makes the literal boundary uncertain") {
            equalAsCheck(mysqlEscaped, mysqlEscapedNarrow) shouldBe false
        }

        test("view: the same") {
            viewChanged("SELECT 'a\\'  b\\'' FROM t", "SELECT 'a\\' b\\'' FROM t") shouldBe true
        }

        test("counter-check: a doubled quote is a recognised escape and folds") {
            equalAsCheck("note = 'it''s'", "note='it''s'") shouldBe true
        }
    }

    context("Rueckzug: eine offene Quotierung") {

        test("an unterminated literal or identifier is not folded") {
            equalAsCheck("note = 'abc", "note='abc") shouldBe false
            equalAsCheck("\"col > 0", "\"col>0") shouldBe false
            equalAsCheck("[col > 0", "[col>0") shouldBe false
        }
    }

    context("Literalschutz im Sichten-Rumpf") {

        test("whitespace inside a literal is a change") {
            viewChanged("SELECT 'a  b' FROM t", "SELECT 'a b' FROM t") shouldBe true
            viewChanged("SELECT 'a , b' FROM t", "SELECT 'a,b' FROM t") shouldBe true
        }

        test("quotes inside a literal are a change") {
            viewChanged("SELECT '\"x\"' FROM t", "SELECT 'x' FROM t") shouldBe true
        }

        test("counter-check: quoting and whitespace outside a literal still fold") {
            viewChanged("SELECT \"x\" ,  'a  b' FROM t", "SELECT x,'a  b' FROM t") shouldBe false
        }
    }

    context("Ein nicht-einfacher quotierter Bezeichner ist geschuetzt") {

        test("CHECK: whitespace inside a quoted name is a different column") {
            equalAsCheck("\"my  col\" > 0", "\"my col\" > 0") shouldBe false
            equalAsCheck("[my  col] > 0", "[my col] > 0") shouldBe false
        }

        test("view: the same") {
            viewChanged("SELECT \"my  col\" FROM t", "SELECT \"my col\" FROM t") shouldBe true
        }

        test("counter-check: around a protected name the whitespace still folds") {
            equalAsCheck("\"my col\" > 0", "\"my col\">0") shouldBe true
        }

        test("counter-check: an array subscript is syntax, not quoting") {
            equalAsCheck("tags[1] = 'a'", "tags[1]='a'") shouldBe true
            equalAsCheck("tags[i] = 'a'", "tagsi = 'a'") shouldBe false
        }
    }

    context("Ein Cast faellt nur, wenn er den Wert nicht aendern kann") {

        test("a cast on a column stays: only the column type knows whether it rounds") {
            equalAsCheck("(price::integer > 5)", "price > 5") shouldBe false
            equalAsCheck("((amount)::numeric > 0)", "amount > 0") shouldBe false
            checkChanged("(price::integer > 5)", "price > 5") shouldBe true
        }

        test("a cast with a type modifier stays: it can truncate") {
            equalAsCheck("code = 'abc'::varchar(2)", "code = 'abc'") shouldBe false
            equalAsCheck("price > (0)::numeric(10,2)", "price > 0") shouldBe false
        }

        test("an array cast stays: it is another value") {
            equalAsCheck("tags = '{a}'::text[]", "tags = '{a}'") shouldBe false
        }

        test("a narrowing or rounding cast on a literal stays") {
            equalAsCheck("x > 2.5::integer", "x > 2.5") shouldBe false
            equalAsCheck("x > 5::smallint", "x > 5") shouldBe false
            equalAsCheck("x > f(0)::numeric", "x > f(0)") shouldBe false
        }

        test("counter-check: a string literal cast to an unbounded text type folds") {
            equalAsCheck("email like '%@%'::text", "email like '%@%'") shouldBe true
            equalAsCheck("status = 'NEW'::character varying", "status = 'NEW'") shouldBe true
            equalAsCheck("status = 'NEW'::VARCHAR", "status = 'NEW'") shouldBe true
        }

        test("counter-check: an integer literal cast to numeric folds, with or without parentheses") {
            equalAsCheck("price > (0)::numeric", "price > 0") shouldBe true
            equalAsCheck("price > 0::numeric", "price>0") shouldBe true
            checkChanged("(price > (0)::numeric)", "price>(0)") shouldBe false
        }
    }

    context("Die Klammern eines Funktionsaufrufs sind Syntax") {

        test("a call is not an identifier of the same letters") {
            equalAsCheck("f(x) > 0", "fx > 0") shouldBe false
        }

        test("counter-check: the call itself still folds its whitespace and quoting") {
            equalAsCheck("(char_length(name) > 0)", "char_length(`name`)>(0)") shouldBe true
        }
    }

    test("the migrate comparator reports every one of these pairs verbatim") {
        SchemaComparator().compare(checkSchema("a = 1 AND b = 2"), checkSchema("a=1 AND b=2"))
            .tablesChanged.shouldNotBeEmpty()
        SchemaComparator().compare(viewSchema("SELECT \"x\" FROM t"), viewSchema("SELECT x FROM t"))
            .viewsChanged.shouldNotBeEmpty()
        SchemaComparator().compare(checkSchema("a = 1"), checkSchema("a = 1"))
            .tablesChanged.shouldBeEmpty()
    }
})
