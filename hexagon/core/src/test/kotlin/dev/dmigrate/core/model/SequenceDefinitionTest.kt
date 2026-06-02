package dev.dmigrate.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 0.9.7 preserve-current-value Sub-Slice A: pins the default value
 * and additive-copy semantics of
 * [SequenceDefinition.preserveCurrentValue]. The field is the entry
 * point for opting into runtime-state preservation — silently flipping
 * the default would change the migration behaviour of every existing
 * schema, so the test fails loud on that.
 */
class SequenceDefinitionTest : FunSpec({

    test("preserveCurrentValue defaults to false — pre-0.9.7 behaviour for all existing schemas") {
        val seq = SequenceDefinition()
        seq.preserveCurrentValue shouldBe false
    }

    test("preserveCurrentValue = true survives copy roundtrip without affecting other fields") {
        val original = SequenceDefinition(
            start = 100L,
            increment = 5L,
            minValue = 10L,
            maxValue = 999L,
            cycle = true,
            cache = 20,
            preserveCurrentValue = true,
        )
        val copied = original.copy()
        copied.preserveCurrentValue shouldBe true
        copied.start shouldBe 100L
        copied.cycle shouldBe true
        copied.cache shouldBe 20
    }

    test("copy(preserveCurrentValue = false) is the documented opt-out path") {
        val withPreserve = SequenceDefinition(preserveCurrentValue = true)
        val withoutPreserve = withPreserve.copy(preserveCurrentValue = false)
        withoutPreserve.preserveCurrentValue shouldBe false
        // Other defaults unchanged.
        withoutPreserve.start shouldBe 1L
        withoutPreserve.increment shouldBe 1L
    }
})
