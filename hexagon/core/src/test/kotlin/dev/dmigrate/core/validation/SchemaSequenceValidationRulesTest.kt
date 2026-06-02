package dev.dmigrate.core.validation

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SchemaSequenceValidationRulesTest : FunSpec({

    fun schemaWith(sequences: Map<String, SequenceDefinition>): SchemaDefinition =
        SchemaDefinition(name = "T", version = "1", sequences = sequences)

    fun errorsFor(sequence: SequenceDefinition): List<ValidationError> =
        SchemaSequenceValidationRules.validate(schemaWith(mapOf("s" to sequence))).errors

    // ── increment value ────────────────────────────────────────────

    test("increment = 0 → E125") {
        val e = errorsFor(SequenceDefinition(increment = 0L)).single()
        e.code shouldBe "E125"
        e.objectPath shouldBe "sequences.s.increment"
        e.message shouldContain "increment must not be 0"
    }

    test("increment = Long.MIN_VALUE → E125") {
        val e = errorsFor(SequenceDefinition(increment = Long.MIN_VALUE))
            .first { it.message.contains("Long.MIN_VALUE") }
        e.code shouldBe "E125"
        e.objectPath shouldBe "sequences.s.increment"
    }

    test("increment = 1 (default) passes") {
        errorsFor(SequenceDefinition()).shouldBeEmpty()
    }

    test("increment = -1 passes (descending sequence)") {
        errorsFor(SequenceDefinition(start = 0L, increment = -1L)).shouldBeEmpty()
    }

    test("increment = Long.MAX_VALUE passes when range fits") {
        // min=0, max=MAX_VALUE, |inc|=MAX_VALUE, max - min = MAX_VALUE ⇒ fits exactly.
        errorsFor(SequenceDefinition(start = 0L, increment = Long.MAX_VALUE, minValue = 0L, maxValue = Long.MAX_VALUE))
            .shouldBeEmpty()
    }

    // ── range consistency ──────────────────────────────────────────

    test("min_value > max_value → E125 range-empty") {
        val errors = errorsFor(SequenceDefinition(start = 5L, minValue = 10L, maxValue = 1L))
        errors.any { it.code == "E125" && it.message.contains("range is empty") } shouldBe true
        // Range-inconsistent sequences skip the increment-fits-range check
        // (the range diagnostic explains the failure; an additional
        // |inc| > range error would be noise).
        errors.none { it.message.contains("exceeds the range") } shouldBe true
    }

    test("start < min_value → E125 outside-range on start") {
        val errors = errorsFor(SequenceDefinition(start = 0L, minValue = 1L, maxValue = 100L))
        val e = errors.single { it.objectPath == "sequences.s.start" }
        e.code shouldBe "E125"
        e.message shouldContain "outside the declared range"
    }

    test("start > max_value → E125 outside-range on start") {
        val errors = errorsFor(SequenceDefinition(start = 200L, minValue = 1L, maxValue = 100L))
        errors.single { it.objectPath == "sequences.s.start" }.message shouldContain "outside the declared range"
    }

    test("start at boundary (min) passes") {
        errorsFor(SequenceDefinition(start = 1L, minValue = 1L, maxValue = 100L)).shouldBeEmpty()
    }

    test("start at boundary (max) passes") {
        errorsFor(SequenceDefinition(start = 100L, minValue = 1L, maxValue = 100L)).shouldBeEmpty()
    }

    test("null min_value defaults to Long.MIN_VALUE (no false positive on negative start)") {
        errorsFor(SequenceDefinition(start = -1_000L, increment = 1L, minValue = null, maxValue = -1L))
            .shouldBeEmpty()
    }

    test("null max_value defaults to Long.MAX_VALUE (huge positive start is fine)") {
        errorsFor(SequenceDefinition(start = 1_000_000L, minValue = 1L, maxValue = null)).shouldBeEmpty()
    }

    // ── |increment| in range ───────────────────────────────────────

    test("|increment| exceeds range → E125") {
        // range = [1, 5] has width 4, increment 10 cannot fit.
        val errors = errorsFor(SequenceDefinition(start = 1L, increment = 10L, minValue = 1L, maxValue = 5L))
        val e = errors.single { it.message.contains("exceeds the range") }
        e.code shouldBe "E125"
        e.objectPath shouldBe "sequences.s.increment"
    }

    test("|negative increment| exceeds range → E125") {
        val errors = errorsFor(SequenceDefinition(start = 5L, increment = -10L, minValue = 1L, maxValue = 5L))
        errors.any { it.message.contains("exceeds the range") } shouldBe true
    }

    test("|increment| equals range width is allowed (boundary case)") {
        // range [1, 5], width = 4, increment = 4 is the largest valid value.
        errorsFor(SequenceDefinition(start = 1L, increment = 4L, minValue = 1L, maxValue = 5L)).shouldBeEmpty()
    }

    // ── isIncrementInRange direct boundary checks (§3.6) ───────────

    test("isIncrementInRange — overflow-safe extremes") {
        // min=MIN_VALUE, max=MAX_VALUE → any non-zero, non-MIN_VALUE inc fits.
        SchemaSequenceValidationRules.isIncrementInRange(1L, Long.MIN_VALUE, Long.MAX_VALUE) shouldBe true
        SchemaSequenceValidationRules.isIncrementInRange(-1L, Long.MIN_VALUE, Long.MAX_VALUE) shouldBe true
        SchemaSequenceValidationRules.isIncrementInRange(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE) shouldBe true

        // Long.MIN_VALUE is rejected regardless of bounds.
        SchemaSequenceValidationRules.isIncrementInRange(Long.MIN_VALUE, 0L, 100L) shouldBe false

        // Tight range near MIN_VALUE: max-inc would underflow → false.
        SchemaSequenceValidationRules.isIncrementInRange(
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 5L,
        ) shouldBe false

        // Tight range near MAX_VALUE with negative inc: min-inc would overflow → false.
        SchemaSequenceValidationRules.isIncrementInRange(
            -Long.MAX_VALUE, Long.MAX_VALUE - 5L, Long.MAX_VALUE,
        ) shouldBe false

        // Worked example from plan §3.6.
        SchemaSequenceValidationRules.isIncrementInRange(50L, -100L, -1L) shouldBe true
        SchemaSequenceValidationRules.isIncrementInRange(-3L, -5L, Long.MAX_VALUE) shouldBe true
    }

    // ── multiple violations / multiple sequences ──────────────────

    test("multiple violations on one sequence emit multiple E125 entries with distinct paths") {
        // increment = 0 AND start outside the (empty) range.
        val errors = errorsFor(SequenceDefinition(start = -10L, increment = 0L, minValue = 1L, maxValue = 5L))
        errors.map { it.code }.toSet() shouldBe setOf("E125")
        errors.map { it.objectPath }.shouldContain("sequences.s.increment")
        errors.map { it.objectPath }.shouldContain("sequences.s.start")
    }

    test("each invalid sequence is reported independently") {
        val errors = SchemaSequenceValidationRules.validate(
            schemaWith(
                mapOf(
                    "good" to SequenceDefinition(),
                    "bad_inc" to SequenceDefinition(increment = 0L),
                    "bad_range" to SequenceDefinition(start = 50L, minValue = 100L, maxValue = 1L),
                ),
            ),
        ).errors

        errors.map { it.objectPath }.any { it.startsWith("sequences.bad_inc") } shouldBe true
        errors.map { it.objectPath }.any { it.startsWith("sequences.bad_range") } shouldBe true
        errors.none { it.objectPath.startsWith("sequences.good") } shouldBe true
    }

    test("empty schema (no sequences) → no errors") {
        SchemaSequenceValidationRules.validate(SchemaDefinition(name = "T", version = "1"))
            .errors.shouldBeEmpty()
    }
})
