package dev.dmigrate.driver.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class FinishTableResultTest : FunSpec({

    test("Success carries adjustments") {
        val adj = SequenceAdjustment(
            table = "users",
            column = "id",
            sequenceName = "users_id_seq",
            newValue = 42,
        )
        val result = FinishTableResult.Success(listOf(adj))
        result.adjustments shouldHaveSize 1
        result.adjustments[0].table shouldBe "users"
        result.adjustments[0].newValue shouldBe 42
    }

    test("Success with empty adjustments") {
        val result = FinishTableResult.Success(emptyList())
        result.adjustments shouldHaveSize 0
    }

    test("PartialFailure carries adjustments and cause") {
        val adj = SequenceAdjustment(
            table = "orders",
            column = "id",
            sequenceName = null,
            newValue = 100,
        )
        val cause = RuntimeException("trigger re-enable failed")
        val result = FinishTableResult.PartialFailure(listOf(adj), cause)
        result.adjustments shouldHaveSize 1
        result.adjustments[0].table shouldBe "orders"
        result.cause shouldBe cause
    }

    test("sealed dispatch via when") {
        val results: List<FinishTableResult> = listOf(
            FinishTableResult.Success(emptyList()),
            FinishTableResult.PartialFailure(emptyList(), RuntimeException("test")),
        )

        val labels = results.map { result ->
            when (result) {
                is FinishTableResult.Success -> "success"
                is FinishTableResult.PartialFailure -> "partial"
            }
        }

        labels shouldBe listOf("success", "partial")
        results[0].shouldBeInstanceOf<FinishTableResult.Success>()
        results[1].shouldBeInstanceOf<FinishTableResult.PartialFailure>()
    }
})
