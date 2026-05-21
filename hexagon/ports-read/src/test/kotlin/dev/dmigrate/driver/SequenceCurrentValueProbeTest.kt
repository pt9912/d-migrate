package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * 0.9.7 preserve-current-value Sub-Slice A: pins the sealed shape of
 * [SequenceCurrentValueProbeResult] so all four outcomes
 * (`Read`, `Failed`, `NotFound`, `NotApplicable`) stay constructible
 * and structurally distinct.
 *
 * The planner-side gate (Sub-Slice D) does an exhaustive `when` on
 * these subtypes without an `else` branch — a regression that
 * collapses two of them into one (e.g. dropping `NotApplicable` in
 * favour of `Failed`) would silently shift SQLite from
 * `SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT` to
 * `SEQUENCE_PRESERVE_PROBE_FAILED`. This test fails loud on that.
 */
class SequenceCurrentValueProbeTest : FunSpec({

    test("Read: all five fields constructible; default matchedRows = 1; equality structural") {
        val a = SequenceCurrentValueProbeResult.Read(value = 42L)
        a.value shouldBe 42L
        a.matchedRows shouldBe 1
        a.isCalled shouldBe null
        a.managedBy shouldBe null
        a.formatVersion shouldBe null

        val b = SequenceCurrentValueProbeResult.Read(value = 42L)
        a shouldBe b

        val mysqlShaped = SequenceCurrentValueProbeResult.Read(
            value = 1000L,
            matchedRows = 1,
            isCalled = null,
            managedBy = "d-migrate",
            formatVersion = 1,
        )
        mysqlShaped.managedBy shouldBe "d-migrate"
        mysqlShaped.formatVersion shouldBe 1

        val pgShaped = SequenceCurrentValueProbeResult.Read(
            value = 42L,
            isCalled = true,
        )
        pgShaped.isCalled shouldBe true
        pgShaped.managedBy shouldBe null

        mysqlShaped shouldNotBe pgShaped
    }

    test("Failed: code + message carry through equality") {
        val a = SequenceCurrentValueProbeResult.Failed(
            code = "DMG_SEQUENCES_ROW_COUNT",
            message = "expected 1 row, got 2",
        )
        val b = SequenceCurrentValueProbeResult.Failed(
            code = "DMG_SEQUENCES_ROW_COUNT",
            message = "expected 1 row, got 2",
        )
        val differentCode = SequenceCurrentValueProbeResult.Failed(
            code = "PROBE_QUERY_FAILED",
            message = "expected 1 row, got 2",
        )
        a shouldBe b
        a shouldNotBe differentCode
    }

    test("NotFound is a singleton data object") {
        val a: SequenceCurrentValueProbeResult = SequenceCurrentValueProbeResult.NotFound
        val b: SequenceCurrentValueProbeResult = SequenceCurrentValueProbeResult.NotFound
        a shouldBeSameInstanceAs b
    }

    test("NotApplicable is a singleton data object distinct from NotFound") {
        val a: SequenceCurrentValueProbeResult = SequenceCurrentValueProbeResult.NotApplicable
        val b: SequenceCurrentValueProbeResult = SequenceCurrentValueProbeResult.NotApplicable
        a shouldBeSameInstanceAs b
        // Distinct routing — Sub-Slice D maps NotFound to
        // SEQUENCE_PRESERVE_NOT_FOUND (info), NotApplicable to
        // SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT (blocker).
        // If a refactor merged them, this comparison would still
        // pass — so additionally pin that they are different
        // sealed subclasses.
        a::class shouldNotBe SequenceCurrentValueProbeResult.NotFound::class
    }

    test("exhaustive when across all four subtypes — sealed-class shape guard") {
        // Pins the planner-side contract: the four outcomes are
        // structurally exhaustive and the planner can do a `when`
        // without an `else` branch. Compile failure here means a
        // new subtype was added without updating downstream
        // gate consumers.
        val cases: List<SequenceCurrentValueProbeResult> = listOf(
            SequenceCurrentValueProbeResult.Read(value = 1L),
            SequenceCurrentValueProbeResult.Failed("X", "msg"),
            SequenceCurrentValueProbeResult.NotFound,
            SequenceCurrentValueProbeResult.NotApplicable,
        )
        val labels: List<String> = cases.map { c ->
            when (c) {
                is SequenceCurrentValueProbeResult.Read -> "read"
                is SequenceCurrentValueProbeResult.Failed -> "failed"
                SequenceCurrentValueProbeResult.NotFound -> "notFound"
                SequenceCurrentValueProbeResult.NotApplicable -> "notApplicable"
            }
        }
        labels shouldBe listOf("read", "failed", "notFound", "notApplicable")
    }
})
