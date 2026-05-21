package dev.dmigrate.core.diff.migration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * 0.9.7 preserve-current-value Sub-Slice A: pins the
 * [SequenceObjectRef] value contract that
 * [DiffOperation.AlterSequenceCurrentValue] and the dialect-side
 * `SequenceCurrentValueProbe` adapters depend on. Pure data-class
 * test — equality, default-`schema = null`, and dialect-branching.
 */
class SequenceObjectRefTest : FunSpec({

    test("default schema is null — unqualified sequence reference") {
        val ref = SequenceObjectRef(name = "order_seq", dialect = RenameProjectionDialect.POSTGRESQL)
        ref.schema shouldBe null
        ref.name shouldBe "order_seq"
        ref.dialect shouldBe RenameProjectionDialect.POSTGRESQL
    }

    test("equality includes all three structural fields") {
        val a = SequenceObjectRef("order_seq", "public", RenameProjectionDialect.POSTGRESQL)
        val b = SequenceObjectRef("order_seq", "public", RenameProjectionDialect.POSTGRESQL)
        val differentSchema = SequenceObjectRef("order_seq", "audit", RenameProjectionDialect.POSTGRESQL)
        val differentDialect = SequenceObjectRef("order_seq", "public", RenameProjectionDialect.MYSQL)
        val differentName = SequenceObjectRef("invoice_seq", "public", RenameProjectionDialect.POSTGRESQL)

        a shouldBe b
        a shouldNotBe differentSchema
        a shouldNotBe differentDialect
        a shouldNotBe differentName
    }

    test("copy() preserves untouched fields") {
        val original = SequenceObjectRef("order_seq", "public", RenameProjectionDialect.POSTGRESQL)
        val renamed = original.copy(name = "invoice_seq")
        renamed.name shouldBe "invoice_seq"
        renamed.schema shouldBe "public"
        renamed.dialect shouldBe RenameProjectionDialect.POSTGRESQL
    }

    test("all three dialects construct without exception — covers the RenameProjectionDialect mirror") {
        // Pins the boundary contract: SequenceObjectRef carries
        // RenameProjectionDialect (not DatabaseDialect) so it stays
        // in hexagon:core. If a future refactor accidentally drops
        // a dialect from the mirror enum, this test fails to
        // construct.
        for (d in RenameProjectionDialect.entries) {
            SequenceObjectRef("seq", null, d).dialect shouldBe d
        }
    }
})
