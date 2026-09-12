package dev.dmigrate.driver

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

/**
 * Die Namensbildung der MySQL-Sequenz-Emulation, festgenagelt.
 *
 * Sie steht hier, damit Renderer und Probe **denselben** Namen bilden — wer
 * sie aendert, aendert damit still, welche Zeile ein `UPDATE dmg_sequences`
 * trifft und welche der Probe entgeht. Genau deshalb gehoert sie unter Test
 * und nicht unter „triviale Zeichenkettenarbeit".
 */
class MysqlSequenceSupportNamingTest : FunSpec({

    test("normalize keeps letters, digits and underscore, lowercased") {
        MysqlSequenceSupportNaming.normalize("Order-Line.ID 42") shouldBe "orderlineid42"
        MysqlSequenceSupportNaming.normalize("mixed_Case_1") shouldBe "mixed_case_1"
    }

    test("the hash is ten hex characters and depends on both parts") {
        val a = MysqlSequenceSupportNaming.hash10("orders", "id")
        val b = MysqlSequenceSupportNaming.hash10("orders", "no")
        val c = MysqlSequenceSupportNaming.hash10("order", "sid")

        a.length shouldBe 10
        a.all { it in "0123456789abcdef" } shouldBe true
        (a == b) shouldBe false
        // Die Trennung zwischen Tabelle und Spalte muss zaehlen, sonst kollidieren
        // `orders.id` und `order.sid`.
        (a == c) shouldBe false
    }

    test("the trigger name carries both segments, the hash and the suffix") {
        val name = MysqlSequenceSupportNaming.triggerName("Orders", "Id")

        name shouldStartWith "dmg_seq_orders_id_"
        name shouldEndWith "_bi"
        name shouldBe "dmg_seq_orders_id_${MysqlSequenceSupportNaming.hash10("orders", "id")}_bi"
    }

    test("very long identifiers stay inside MySQL's name limit") {
        val name = MysqlSequenceSupportNaming.triggerName("a".repeat(80), "b".repeat(80))

        // 8 + 16 + 1 + 16 + 1 + 10 + 3 = 55
        name.length shouldBeLessThanOrEqual 55
        name shouldBe "dmg_seq_${"a".repeat(16)}_${"b".repeat(16)}_" +
            "${MysqlSequenceSupportNaming.hash10("a".repeat(80), "b".repeat(80))}_bi"
    }

    test("two different columns of one table get different trigger names") {
        val first = MysqlSequenceSupportNaming.triggerName("orders", "id")
        val second = MysqlSequenceSupportNaming.triggerName("orders", "no")

        (first == second) shouldBe false
    }

    // Das Schema geht bewusst NICHT ein: `dmg_sequences` traegt keine Qualifizierung.
    test("the lookup key is the sequence name, unqualified") {
        val ref = SequenceObjectRef(
            name = "orders_id_seq",
            schema = "app",
            dialect = RenameProjectionDialect.MYSQL,
        )

        MysqlSequenceSupportNaming.lookupKey(ref) shouldBe "orders_id_seq"
    }

    test("the accepted marker sets are the ones the renderer and the probe filter on") {
        MysqlSequenceSupportNaming.SUPPORTED_MANAGED_BY shouldBe setOf("d-migrate")
        MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS shouldBe setOf("mysql-sequence-v1")
        MysqlSequenceSupportNaming.SUPPORT_TABLE shouldBe "dmg_sequences"
        MysqlSequenceSupportNaming.NEXTVAL_ROUTINE shouldBe "dmg_nextval"
        MysqlSequenceSupportNaming.SETVAL_ROUTINE shouldBe "dmg_setval"
    }
})
