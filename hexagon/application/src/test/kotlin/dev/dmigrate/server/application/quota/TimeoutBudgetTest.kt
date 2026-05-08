package dev.dmigrate.server.application.quota

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

class TimeoutBudgetTest : FunSpec({

    test("constructs with positive duration") {
        val budget = TimeoutBudget("data.export", Duration.ofSeconds(30))
        budget.operation shouldBe "data.export"
        budget.total shouldBe Duration.ofSeconds(30)
    }

    test("rejects zero duration") {
        shouldThrow<IllegalArgumentException> {
            TimeoutBudget("op", Duration.ZERO)
        }
    }

    test("rejects negative duration") {
        shouldThrow<IllegalArgumentException> {
            TimeoutBudget("op", Duration.ofSeconds(-1))
        }
    }

    test("rejects blank operation") {
        shouldThrow<IllegalArgumentException> {
            TimeoutBudget("   ", Duration.ofSeconds(1))
        }
    }
})
