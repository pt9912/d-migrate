package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.RawSqlExpressionPortability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Die Ausdruecke stammen aus `information_schema` einer echten MySQL 9.7.2
 * (`CHECK_CONSTRAINTS.CHECK_CLAUSE`, `COLUMNS.GENERATION_EXPRESSION`,
 * `STATISTICS.EXPRESSION`), zeichengleich abgeschrieben — samt der zweiten
 * Escape-Ebene, die der Server darueberlegt.
 */
class MysqlServerExpressionTextTest : FunSpec({

    listOf(
        // C1: der Introducer, der das Schema ungueltig machte (E012).
        """(`email` like _latin1\'%@%\')""" to "(email like '%@%')",
        // Backslash-Escape eines Quotes -> das Standard-Escape.
        """(`note` <> _latin1\'it\\\'s\')""" to "(note <> 'it''s')",
        // Ein Backslash im Wert bleibt ein Backslash.
        """(`note` <> _latin1\'a\\\\b\')""" to "(note <> 'a\\b')",
        // `\_` gehoert zum LIKE-Muster und behaelt seinen Backslash.
        """(not((`note` like _latin1\'%\\\\_%\')))""" to "(not((note like '%\\_%')))",
        // Ein `"` im Literal ist ein Zeichen, kein Bezeichner.
        """(`note` <> _latin1\'say "hi"\')""" to """(note <> 'say "hi"')""",
        // N2/M9: Backtick-Quoting -> nackt bzw. `"…"`.
        """((`Qty` > 0) and (`UnitPrice` >= 0))""" to """(("Qty" > 0) and ("UnitPrice" >= 0))""",
        // Weitere Introducer-Formen: `N'…'` kommt als `_utf8mb3`, `_binary` zaehlt mit.
        """(`note` <> _utf8mb3\'nat\')""" to "(note <> 'nat')",
        """(`note` <> _binary\'bb\')""" to "(note <> 'bb')",
        """(`note` <> (_utf8mb4\'c\' collate utf8mb4_bin))""" to "(note <> ('c' collate utf8mb4_bin))",
        // Kein Literal, kein Bezeichner: nichts zu tun.
        "(`note` <> 0x41)" to "(note <> 0x41)",
        "(json_valid(`note`) or (`note` is null))" to "(json_valid(note) or (note is null))",
        // M10: der Berechnungsausdruck mit Zeichenkette (E136 ohne diese Regel).
        """concat(`first_name`,_latin1\' \',`last_name`)""" to "concat(first_name,' ',last_name)",
        "(`Qty` * `UnitPrice`)" to """("Qty" * "UnitPrice")""",
        // Der Ausdrucks-Schluessel eines funktionalen Index, gemessen ebenso.
        """concat(`nm`,_utf8mb4\'x\')""" to "concat(nm,'x')",
        "lower(`Note`)" to """lower("Note")""",
        // Bezeichner mit Sonderzeichen: `'` und `\` tragen die zweite Ebene mit,
        // `"` wird im neutralen Bezeichner verdoppelt, `` ` `` bleibt Zeichen.
        """((((`it\'s` + `back\\slash`) + `dq"col`) + `bt``col`) + `order`)"""
            to """(((("it's" + "back\slash") + "dq""col") + "bt`col") + order)""",
    ).forEachIndexed { index, (server, neutral) ->
        test("normalize #$index: $server") {
            MysqlServerExpressionText.normalize(server) shouldBe neutral
        }
    }

    test("a text without the second escape layer is taken as it stands") {
        // Aeltere Server und die Fixtures der Tests liefern den Ausdruck
        // ungeschachtelt; dann darf nichts abgezogen werden.
        MysqlServerExpressionText.normalize("(age > 0)") shouldBe "(age > 0)"
        MysqlServerExpressionText.normalize("(`note` <> 'x')") shouldBe "(note <> 'x')"
    }

    test("a leading underscore inside a name is no introducer") {
        MysqlServerExpressionText.normalize("(`my_utf8` <> 0)") shouldBe "(my_utf8 <> 0)"
        MysqlServerExpressionText.normalize("(_latin1 > 0)") shouldBe "(_latin1 > 0)"
    }

    test("the control-character escapes keep their value") {
        MysqlServerExpressionText.normalize("""(`note` <> _utf8mb4\'a\\nb\')""") shouldBe "(note <> 'a\nb')"
        MysqlServerExpressionText.normalize("""(`note` <> _utf8mb4\'a\\tb\')""") shouldBe "(note <> 'a\tb')"
    }

    test("a double-quoted run stays as it stands — it is an identifier, not a literal") {
        // Gemessen (MySQL 9.7.2 und 8.0.46): die drei Ausdrucksfelder drucken
        // Bezeichner immer in Backticks und Literale immer in `'…'`, auch unter
        // ANSI_QUOTES und auch fuer eine unter ANSI_QUOTES angelegte Tabelle.
        // Trifft der Leser doch ein `"…"`, gilt die neutrale Lesart.
        MysqlServerExpressionText.normalize("""("Note" <> 'x')""") shouldBe """("Note" <> 'x')"""
        MysqlServerExpressionText.normalize("""("a""b" > 0)""") shouldBe """("a""b" > 0)"""
        // Gegenprobe: im Literal bleibt `"` ein Zeichen.
        MysqlServerExpressionText.normalize("""(`note` <> 'say "hi"')""") shouldBe """(note <> 'say "hi"')"""
    }

    test("M1: a reserved word survives the round trip to MySQL") {
        // `` `key` `` -> neutral `key` -> zurueck als `` `key` ``. Ohne die
        // Rueckquotierung ist `CHECK (key > 0)` am Server ERROR 1064.
        val neutral = MysqlServerExpressionText.normalize("""(`key` > 0)""")
        neutral shouldBe "(key > 0)"
        MysqlRawExpressionText.toMysql(neutral) shouldBe "(`key` > 0)"
    }

    test("the normalised expression is portable for every other target") {
        val neutral = MysqlServerExpressionText.normalize("""(`email` like _latin1\'%@%\')""")
        for (target in listOf(DatabaseDialect.POSTGRESQL, DatabaseDialect.SQLITE, DatabaseDialect.MSSQL)) {
            RawSqlExpressionPortability.assess(neutral, target).portable shouldBe true
        }
        // Gegenprobe: die Serverform ist es nicht (Backtick-Quoting).
        RawSqlExpressionPortability.assess("(`Qty` > 0)", DatabaseDialect.POSTGRESQL).portable shouldBe false
    }
})
