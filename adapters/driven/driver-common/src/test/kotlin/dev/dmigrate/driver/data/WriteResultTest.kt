package dev.dmigrate.driver.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WriteResultTest : FunSpec({

    test("basic construction") {
        val result = WriteResult(rowsInserted = 10, rowsUpdated = 3, rowsSkipped = 2, rowsUnknown = 1)
        result.rowsInserted shouldBe 10
        result.rowsUpdated shouldBe 3
        result.rowsSkipped shouldBe 2
        result.rowsUnknown shouldBe 1
        result.totalRows shouldBe 16
    }

    test("default rowsUnknown is 0") {
        val result = WriteResult(rowsInserted = 5, rowsUpdated = 0, rowsSkipped = 0)
        result.rowsUnknown shouldBe 0
    }

    test("totalRows sums all fields") {
        val result = WriteResult(
            rowsInserted = 100,
            rowsUpdated = 50,
            rowsSkipped = 25,
            rowsUnknown = 10,
        )
        result.totalRows shouldBe 185
    }

    test("rejects negative rowsInserted") {
        shouldThrow<IllegalArgumentException> {
            WriteResult(rowsInserted = -1, rowsUpdated = 0, rowsSkipped = 0)
        }
    }

    test("rejects negative rowsUpdated") {
        shouldThrow<IllegalArgumentException> {
            WriteResult(rowsInserted = 0, rowsUpdated = -1, rowsSkipped = 0)
        }
    }

    test("rejects negative rowsSkipped") {
        shouldThrow<IllegalArgumentException> {
            WriteResult(rowsInserted = 0, rowsUpdated = 0, rowsSkipped = -1)
        }
    }

    test("rejects negative rowsUnknown") {
        shouldThrow<IllegalArgumentException> {
            WriteResult(rowsInserted = 0, rowsUpdated = 0, rowsSkipped = 0, rowsUnknown = -1)
        }
    }

    test("all-zero is valid") {
        val result = WriteResult(rowsInserted = 0, rowsUpdated = 0, rowsSkipped = 0, rowsUnknown = 0)
        result.totalRows shouldBe 0
    }

    test("data class equality") {
        val a = WriteResult(rowsInserted = 1, rowsUpdated = 2, rowsSkipped = 3, rowsUnknown = 4)
        val b = WriteResult(rowsInserted = 1, rowsUpdated = 2, rowsSkipped = 3, rowsUnknown = 4)
        (a == b) shouldBe true
    }

    test("data class copy") {
        val original = WriteResult(rowsInserted = 1, rowsUpdated = 2, rowsSkipped = 3)
        val copied = original.copy(rowsUpdated = 5)
        copied.rowsUpdated shouldBe 5
        copied.rowsInserted shouldBe 1
        copied.rowsSkipped shouldBe 3
        copied.rowsUnknown shouldBe 0
    }
})
