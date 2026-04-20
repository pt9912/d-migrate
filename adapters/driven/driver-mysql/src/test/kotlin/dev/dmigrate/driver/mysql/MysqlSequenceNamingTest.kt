package dev.dmigrate.driver.mysql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveMaxLength
import io.kotest.matchers.string.shouldStartWith

class MysqlSequenceNamingTest : FunSpec({

    test("normalize lowercases and strips non-alphanumeric except underscore") {
        MysqlSequenceNaming.normalize("Invoice_Number") shouldBe "invoice_number"
        MysqlSequenceNaming.normalize("my-table.name") shouldBe "mytablename"
        MysqlSequenceNaming.normalize("ABC_123") shouldBe "abc_123"
    }

    test("hash10 is deterministic for same input") {
        val h1 = MysqlSequenceNaming.hash10("invoices", "invoice_number")
        val h2 = MysqlSequenceNaming.hash10("invoices", "invoice_number")
        h1 shouldBe h2
        h1.length shouldBe 10
    }

    test("hash10 differs for different inputs") {
        val h1 = MysqlSequenceNaming.hash10("invoices", "invoice_number")
        val h2 = MysqlSequenceNaming.hash10("invoices", "order_number")
        h1 shouldBe h1 // sanity
        (h1 != h2) shouldBe true
    }

    test("triggerName follows canonical format") {
        val name = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        name shouldStartWith "dmg_seq_"
        name.endsWith("_bi") shouldBe true
        name shouldHaveMaxLength 55
    }

    test("triggerName truncates long names to 16 chars per segment") {
        val name = MysqlSequenceNaming.triggerName(
            "very_long_table_name_that_exceeds_16",
            "very_long_column_name_that_exceeds_16",
        )
        name shouldStartWith "dmg_seq_very_long_table__very_long_column"
        name shouldHaveMaxLength 55
    }

    test("triggerName is stable across calls") {
        val a = MysqlSequenceNaming.triggerName("users", "id")
        val b = MysqlSequenceNaming.triggerName("users", "id")
        a shouldBe b
    }

    test("reservedNames contains the three support object names") {
        MysqlSequenceNaming.reservedNames shouldBe setOf(
            "dmg_sequences", "dmg_nextval", "dmg_setval",
        )
    }

    test("isSupportTriggerName recognizes canonical trigger names") {
        MysqlSequenceNaming.isSupportTriggerName("dmg_seq_users_id_abc1234567_bi") shouldBe true
        MysqlSequenceNaming.isSupportTriggerName("trg_users_updated_at") shouldBe false
    }
})
