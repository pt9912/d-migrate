package dev.dmigrate.driver.sqlite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * 0.9.7 SQLite-Sequence Phase D unit coverage for
 * [SqliteSequenceMarkerParser]. Plan §6.1 / §3.3 set the contract:
 * the marker may sit anywhere in a `/* ... */` block, supports
 * percent-encoded values, and exposes the canonical
 * `(object, sequence, table, column)` tuple.
 */
class SqliteSequenceMarkerParserTest : FunSpec({

    fun bodyWithMarker(marker: String): String = """
        CREATE TRIGGER "x" BEFORE INSERT ON "t"
        FOR EACH ROW BEGIN
            $marker
            UPDATE "dmg_sequences" SET …;
        END;
    """.trimIndent()

    test("canonical _bi marker resolves to BEFORE_INSERT + tuple") {
        val sql = bodyWithMarker(
            "/* d-migrate:sqlite-sequence-v1 object=sequence-trigger " +
                "sequence=order_seq table=orders column=order_number */",
        )
        val m = SqliteSequenceMarkerParser.parse(sql)
        m.shouldNotBeNull()
        m.objectType shouldBe SqliteSequenceMarkerParser.ObjectType.BEFORE_INSERT
        m.sequenceName shouldBe "order_seq"
        m.tableName shouldBe "orders"
        m.columnName shouldBe "order_number"
    }

    test("canonical _ai marker resolves to AFTER_INSERT") {
        val sql = bodyWithMarker(
            "/* d-migrate:sqlite-sequence-v1 object=sequence-trigger-post " +
                "sequence=order_seq table=orders column=order_number */",
        )
        SqliteSequenceMarkerParser.parse(sql)?.objectType shouldBe
            SqliteSequenceMarkerParser.ObjectType.AFTER_INSERT
    }

    test("marker tolerates whitespace and line breaks inside the block comment") {
        val sql = bodyWithMarker(
            "/*\n" +
                "  d-migrate:sqlite-sequence-v1\n" +
                "  object=sequence-trigger\n" +
                "  sequence=order_seq\n" +
                "  table=orders\n" +
                "  column=order_number\n" +
                "*/",
        )
        val m = SqliteSequenceMarkerParser.parse(sql)
        m.shouldNotBeNull()
        m.sequenceName shouldBe "order_seq"
    }

    test("marker percent-decodes values containing reserved characters") {
        // `*` and `=` and space in identifiers are escaped per §3.3.
        val sql = bodyWithMarker(
            "/* d-migrate:sqlite-sequence-v1 object=sequence-trigger " +
                "sequence=odd%20seq table=t column=col */",
        )
        SqliteSequenceMarkerParser.parse(sql)?.sequenceName shouldBe "odd seq"
    }

    test("marker placed in a later comment block still gets picked up") {
        val sql = """
            CREATE TRIGGER "x" BEFORE INSERT ON "t"
            FOR EACH ROW BEGIN
                /* operator note: this used to live in app code */
                /* d-migrate:sqlite-sequence-v1 object=sequence-trigger sequence=s table=t column=c */
                UPDATE "dmg_sequences" SET …;
            END;
        """.trimIndent()
        SqliteSequenceMarkerParser.parse(sql).shouldNotBeNull()
    }

    test("no marker comment → null") {
        val sql = bodyWithMarker("/* unrelated comment */")
        SqliteSequenceMarkerParser.parse(sql) shouldBe null
    }

    test("marker with unknown object= value → null") {
        val sql = bodyWithMarker(
            "/* d-migrate:sqlite-sequence-v1 object=something-else " +
                "sequence=s table=t column=c */",
        )
        SqliteSequenceMarkerParser.parse(sql) shouldBe null
    }

    test("marker missing a required key → null") {
        val sql = bodyWithMarker(
            "/* d-migrate:sqlite-sequence-v1 object=sequence-trigger " +
                "sequence=s table=t */", // column= missing
        )
        SqliteSequenceMarkerParser.parse(sql) shouldBe null
    }
})
