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
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer),
                    "price" to ColumnDefinition(NeutralType.Decimal(10, 2)),
                ),
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

    context("Rueckzug: MySQLs Zeilenkommentar `#`") {

        test("CHECK: a hash comment whose extent changes is not folded") {
            equalAsCheck("a = 1 # x\nAND b = 2", "a = 1 # x AND b = 2") shouldBe false
        }

        test("PostgreSQL's XOR `#` costs the folding, nothing else") {
            equalAsCheck("a # b = 1", "a#b=1") shouldBe false
        }

        test("counter-check: a hash inside a literal is text") {
            equalAsCheck("note = '#'", "note='#'") shouldBe true
        }
    }

    context("Rueckzug: Oracles alternative Quotierung") {

        // Fuer Oracle ist `q'[a'  'b]'` **ein** Literal. Der Standard-Scanner
        // liest zwei (`'[a'` und `'b]'`) und faltete den Leerraum dazwischen.
        test("q'…', Q'…', nq'…' and Nq'…' are not folded") {
            equalAsCheck("note = q'[a'  'b]'", "note = q'[a' 'b]'") shouldBe false
            equalAsCheck("note = Q'[a'  'b]'", "note = Q'[a' 'b]'") shouldBe false
            equalAsCheck("note = nq'[a'  'b]'", "note = nq'[a' 'b]'") shouldBe false
            equalAsCheck("note = Nq'[a'  'b]'", "note = Nq'[a' 'b]'") shouldBe false
        }

        test("counter-check: a name ending in q before a literal with a gap still folds") {
            equalAsCheck("q = 'x'", "q='x'") shouldBe true
            equalAsCheck("freq = 'x'", "freq='x'") shouldBe true
        }
    }

    context("Rueckzug: ein Dollar-Tag mit Buchstaben ausserhalb von ASCII") {

        test("CHECK and view: whitespace inside \$ä\$…\$ä\$ is not folded") {
            equalAsCheck("note = \$ä\$a  b\$ä\$", "note = \$ä\$a b\$ä\$") shouldBe false
            viewChanged("SELECT \$ä\$a  b\$ä\$ FROM t", "SELECT \$ä\$a b\$ä\$ FROM t") shouldBe true
        }
    }

    context("Rueckzug: `[` nach Leerraum hinter einem Namen") {

        test("PostgreSQL reads `tags [pos]` as a subscript") {
            equalAsCheck("tags [pos] = 1", "tags pos = 1") shouldBe false
            viewChanged("SELECT (x) [i] FROM t", "SELECT (x) i FROM t") shouldBe true
            viewChanged("SELECT \"my col\" [i] FROM t", "SELECT \"my col\" i FROM t") shouldBe true
        }

        test("counter-check: where the text shows T-SQL quoting elsewhere, it is an alias") {
            viewChanged("SELECT [o].[id] FROM [orders] [o]", "SELECT o.id FROM orders o") shouldBe false
            equalAsCheck("[a] = 1 AND tags [pos] = 1", "a = 1 AND tags pos = 1") shouldBe true
        }

        test("counter-check: after a keyword it is T-SQL quoting, after ARRAY an array") {
            equalAsCheck("x = 1 AND [pos] = 2", "x = 1 AND pos = 2") shouldBe true
            equalAsCheck("x IS NULL OR [pos] >= 2", "x IS NULL OR pos>=2") shouldBe true
            equalAsCheck("a = ANY (ARRAY [b])", "a = ANY (ARRAY  [b])") shouldBe true
            equalAsCheck("a = ANY (ARRAY [b])", "a = ANY (ARRAY b)") shouldBe false
        }
    }

    context("Grenze: `\"…\"` ist immer ein Bezeichner") {

        // MySQL ohne ANSI_QUOTES und SQLite als Rueckfall lesen `"abc"` als
        // String. Die Faltung kennt das nicht; die Spec nennt es als Grenze.
        test("a double-quoted simple name is unwrapped like any identifier") {
            equalAsCheck("note = \"abc\"", "note = abc") shouldBe true
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

        // Seit P9 entscheidet der Spaltentyp; die Faelle hier pruefen die
        // Literal- und Modifikator-Grenze mit einer Tabelle, die die Spalten
        // kennt. Die Regel selbst steht in `ColumnCastFoldTest`.
        val typed = ColumnTypes(
            mapOf(
                "price" to NeutralType.Decimal(10, 2),
                "amount" to NeutralType.Float(),
                "x" to NeutralType.Integer,
                "code" to NeutralType.Text(),
                "tags" to NeutralType.Array("text"),
                "email" to NeutralType.Text(),
                "status" to NeutralType.Text(maxLength = 10),
            ),
        )

        fun equalTyped(left: String, right: String) =
            ConstraintDiffContract.canonicallyEqual(left, right, typed, typed)

        test("a cast on a column stays: only the column type knows whether it rounds") {
            equalAsCheck("(price::integer > 5)", "price > 5") shouldBe false
            equalTyped("(price::integer > 5)", "price > 5") shouldBe false
            equalTyped("((amount)::numeric > 0)", "amount > 0") shouldBe false
            checkChanged("(price::integer > 5)", "price > 5") shouldBe true
        }

        test("a cast with a type modifier stays: it can truncate") {
            equalAsCheck("code = 'abc'::varchar(2)", "code = 'abc'") shouldBe false
            equalAsCheck("price > (0)::numeric(10,2)", "price > 0") shouldBe false
            equalTyped("code = 'abc'::varchar(2)", "code = 'abc'") shouldBe false
            equalTyped("price > (0)::numeric(10,2)", "price > 0") shouldBe false
        }

        test("an array cast stays: it is another value") {
            equalAsCheck("tags = '{a}'::text[]", "tags = '{a}'") shouldBe false
            equalTyped("tags = '{a}'::text[]", "tags = '{a}'") shouldBe false
        }

        test("a narrowing or rounding cast on a literal stays") {
            equalAsCheck("x > 2.5::integer", "x > 2.5") shouldBe false
            equalAsCheck("x > 5::smallint", "x > 5") shouldBe false
            equalAsCheck("x > f(0)::numeric", "x > f(0)") shouldBe false
            equalTyped("x > 2.5::integer", "x > 2.5") shouldBe false
            equalTyped("x > f(0)::numeric", "x > f(0)") shouldBe false
            // Mit Tabelle entscheidet der Wert: `5` passt in `smallint`,
            // `70000` nicht — dort scheitert der Cast, das Literal nicht.
            equalTyped("x > 70000::smallint", "x > 70000") shouldBe false
            equalTyped("x > 5::smallint", "x > 5") shouldBe true
        }

        test("a type name in capitals is not folded: quoted, it would name another type") {
            equalTyped("status = 'NEW'::VARCHAR", "status = 'NEW'") shouldBe false
        }

        test("without a table no cast falls, not even a harmless one") {
            equalAsCheck("email like '%@%'::text", "email like '%@%'") shouldBe false
            equalAsCheck("price > (0)::numeric", "price > 0") shouldBe false
        }

        test("counter-check: a string literal cast to the text type of its column folds") {
            equalTyped("email like '%@%'::text", "email like '%@%'") shouldBe true
            equalTyped("status = 'NEW'::character varying", "status = 'NEW'") shouldBe true
            equalTyped("status = 'NEW'::varchar", "status = 'NEW'") shouldBe true
        }

        test("counter-check: an integer literal cast to numeric folds against a numeric column") {
            equalTyped("price > (0)::numeric", "price > 0") shouldBe true
            equalTyped("price > 0::numeric", "price>0") shouldBe true
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

    context("Quotierte Schluesselwoerter bleiben quotiert") {

        test("a quoted keyword is a column, the bare one a value or a function") {
            equalAsCheck("\"user\" = 'x'", "user = 'x'") shouldBe false
            equalAsCheck("[user] = 'x'", "user = 'x'") shouldBe false
            equalAsCheck("`user` = 'x'", "user = 'x'") shouldBe false
            equalAsCheck("a = \"null\"", "a = null") shouldBe false
            equalAsCheck("a = \"current_date\"", "a = current_date") shouldBe false
            equalAsCheck("a = \"true\"", "a = true") shouldBe false
            equalAsCheck("\"USER\" = 'x'", "USER = 'x'") shouldBe false
        }

        test("a quoted junction word is not read as AND/OR") {
            equalAsCheck("(\"and\" = 1) OR b = 2", "(and = 1) OR b = 2") shouldBe false
            // `"or"(…)` ruft eine Funktion dieses Namens; entpackt faelle die
            // Klammer als Operanden-Klammer hinter `OR`.
            equalAsCheck("x = 1 OR \"or\"(b = 2)", "x = 1 OR orb = 2") shouldBe false
        }

        test("view: the same") {
            viewChanged("SELECT \"user\" FROM t", "SELECT user FROM t") shouldBe true
            viewChanged("SELECT \"current_date\" FROM t", "SELECT current_date FROM t") shouldBe true
        }

        test("counter-check: the three quotings of a keyword are one spelling, a plain name is unwrapped") {
            equalAsCheck("[user] = 'x'", "\"user\" = 'x'") shouldBe true
            equalAsCheck("`user` = 'x'", "\"user\" = 'x'") shouldBe true
            equalAsCheck("[status] = 'x'", "status = 'x'") shouldBe true
            viewChanged("SELECT [user] FROM t", "SELECT \"user\" FROM t") shouldBe false
        }

        test("counter-check: a different case is a different quoted name") {
            equalAsCheck("\"User\" = 'x'", "\"user\" = 'x'") shouldBe false
        }
    }

    context("Namen ausserhalb von ASCII") {

        test("a call is not a name of the same letters") {
            equalAsCheck("maß(x) > 0", "maßx > 0") shouldBe false
        }

        test("a name ending in `or` is no junction") {
            equalAsCheck("señor(b = 2) OR c", "señorb = 2 OR c") shouldBe false
        }

        test("counter-check: around such names the folding still works") {
            equalAsCheck("(maß > (0))", "maß>0") shouldBe true
            equalAsCheck("(größe = 1) OR (b = 2)", "größe = 1 OR b = 2") shouldBe true
        }
    }

    context("Klammern, die zu einer Syntax gehoeren") {

        test("whitespace before a call's parenthesis keeps it a call") {
            equalAsCheck("f (x) > 0", "f x > 0") shouldBe false
            val price = ColumnTypes(mapOf("price" to NeutralType.Decimal(10, 2)))
            ConstraintDiffContract.canonicallyEqual("price > f (0)::numeric", "price > f (0)", price, price) shouldBe false
        }

        test("a parenthesis right before a dot is a field access") {
            equalAsCheck("(addr).city = 'x'", "addr.city = 'x'") shouldBe false
            equalAsCheck("(addr) .city = 'x'", "addr .city = 'x'") shouldBe false
        }

        test("counter-check: the call and the qualified name still fold their whitespace") {
            equalAsCheck("f (x) > 0", "f (x)>0") shouldBe true
            equalAsCheck("(addr.city) = 'x'", "addr.city='x'") shouldBe true
            equalAsCheck("NOT (flag) OR b = 2", "NOT flag OR b = 2") shouldBe true
        }

        test("unbalanced brackets around the whole text stay") {
            equalAsCheck("(a])", "a]") shouldBe false
        }
    }

    context("`~~` ist `LIKE` — mit Leerraum") {

        test("the operator does not glue its operands together") {
            equalAsCheck("x~~y", "xlikey") shouldBe false
        }

        test("NOT LIKE and ILIKE stay what they are") {
            equalAsCheck("x !~~ 'a'", "x !like 'a'") shouldBe false
            equalAsCheck("x ~~* 'a'", "x like* 'a'") shouldBe false
        }

        test("counter-check: with or without whitespace it is `like`") {
            equalAsCheck("x ~~ 'a'", "x like 'a'") shouldBe true
            equalAsCheck("x~~'a'", "x like 'a'") shouldBe true
        }
    }

    context("Leerraum um Operatoren") {

        test("around division and modulo") {
            equalAsCheck("a / b > 1", "a/b>1") shouldBe true
            equalAsCheck("a % b = 0", "a%b=0") shouldBe true
        }

        test("not where joining two operator characters changes the operator") {
            equalAsCheck("a < @ b", "a <@ b") shouldBe false
            equalAsCheck("a @ > b", "a @> b") shouldBe false
            equalAsCheck("a = ~ b", "a =~ b") shouldBe false
            viewChanged("SELECT a = @ b FROM t", "SELECT a =@ b FROM t") shouldBe true
        }

        test("counter-check: a trailing sign joins as PostgreSQL splits it again") {
            equalAsCheck("a < -1", "a<-1") shouldBe true
            equalAsCheck("a = - 1", "a=-1") shouldBe true
            equalAsCheck("a - -1 > 0", "a - - 1 > 0") shouldBe true
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
