package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Deckt die JSON-String-Escapes von [SchemaMigratePreflightRenderers] — die
 * Control-/Sonderzeichen-Zweige wurden von keinem bestehenden Test getroffen.
 * Sonderzeichen werden über [Char]-Codes gebaut, damit der Quelltext keine
 * rohen Escapes/Control-Chars enthält.
 */
class SchemaMigratePreflightRenderersTest : FunSpec({

    val bs = Char(0x5C).toString() // '\'
    val quote = Char(0x22).toString() // '"'
    // Ein Wert, der jeden jsonString-Zweig trifft: Backslash, Quote, LF, CR, TAB,
    // und ein generisches Control-Zeichen (< 0x20, kein LF/CR/TAB).
    val tricky = "op" + Char(0x5C) + Char(0x22) + Char(0x0A) + Char(0x0D) + Char(0x09) + Char(0x01)

    test("sqlite-cast-preflight JSON escaped Backslash/Quote/LF/CR/TAB/Control") {
        val view = SchemaMigrateSqliteCastPreflightView(
            operationId = tricky,
            dialect = "sqlite",
            table = "t",
            column = "c",
            sourceType = "TEXT",
            targetType = "INTEGER",
            status = "would_fail",
            sqlHash = "abc",
            totalRows = 5L,
            failingRows = null,
            sampleRowIds = listOf("row" + Char(0x09)),
            problem = null,
        )

        val json = SchemaMigratePreflightRenderers.renderSqliteCastPreflights(listOf(view))

        json shouldContain (bs + bs) // escaped backslash
        json shouldContain (bs + quote) // escaped quote
        json shouldContain (bs + "n") // LF
        json shouldContain (bs + "r") // CR
        json shouldContain (bs + "t") // TAB
        json shouldContain (bs + "u0001") // generic control char
        json shouldContain (quote + "totalRows" + quote + ":5")
        json shouldContain (quote + "failingRows" + quote + ":null")
        json shouldContain (quote + "problem" + quote + ":null")
    }

    test("mysql-sequence-canonicity JSON rendert Werte und null-Felder") {
        val view = SchemaMigrateMysqlSequenceCanonicityView(
            operationId = "op1",
            dialect = "mysql",
            kind = "sequence",
            objectName = "users_seq",
            status = "drift",
            sqlHash = "h",
            driftField = "increment",
            expected = "1",
            actual = "2",
            problem = null,
        )

        val json = SchemaMigratePreflightRenderers.renderMysqlSequenceCanonicity(listOf(view))

        json shouldContain (quote + "driftField" + quote + ":" + quote + "increment" + quote)
        json shouldContain (quote + "problem" + quote + ":null")
    }
})
