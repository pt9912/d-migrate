package dev.dmigrate.driver.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SequenceAdjustmentTest : FunSpec({

    test("stores PostgreSQL adjustment with explicit sequence name") {
        val adjustment = SequenceAdjustment(
            table = "public.users",
            column = "id",
            sequenceName = "public.users_id_seq",
            newValue = 43,
        )

        adjustment.table shouldBe "public.users"
        adjustment.column shouldBe "id"
        adjustment.sequenceName shouldBe "public.users_id_seq"
        adjustment.newValue shouldBe 43
    }

    test("stores MySQL adjustment without sequence name") {
        val adjustment = SequenceAdjustment(
            table = "orders",
            column = "id",
            sequenceName = null,
            newValue = 101,
        )

        adjustment.sequenceName shouldBe null
        adjustment.newValue shouldBe 101
    }

    test("stores SQLite reset adjustment with next generated value one") {
        val adjustment = SequenceAdjustment(
            table = "items",
            column = "id",
            sequenceName = null,
            newValue = 1,
        )

        adjustment.table shouldBe "items"
        adjustment.column shouldBe "id"
        adjustment.sequenceName shouldBe null
        adjustment.newValue shouldBe 1
    }

    test("supports equality and copy semantics") {
        val a = SequenceAdjustment("users", "id", "users_id_seq", 7)
        val b = SequenceAdjustment("users", "id", "users_id_seq", 7)
        val copied = a.copy(newValue = 8)

        (a == b) shouldBe true
        copied.table shouldBe "users"
        copied.column shouldBe "id"
        copied.sequenceName shouldBe "users_id_seq"
        copied.newValue shouldBe 8
    }
})
