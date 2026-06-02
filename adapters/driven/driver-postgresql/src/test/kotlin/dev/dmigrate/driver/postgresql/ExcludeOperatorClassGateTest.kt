package dev.dmigrate.driver.postgresql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * F.5 Sub-Slice F: pins which EXCLUDE element shapes the first
 * F.5 tranche treats as round-trippable. The gate must not regress
 * the existing Sub-Slice B happy paths (`col WITH op` and
 * `col WITH op, col2 WITH op2`) while rejecting custom operator
 * classes and other non-whitelisted tokens before the renderer emits
 * non-round-trippable DDL.
 */
class ExcludeOperatorClassGateTest : FunSpec({

    test("blank / null expression is the upstream's concern — gate says Allowed") {
        ExcludeOperatorClassGate.verdict(null).shouldBeInstanceOf<ExcludeOperatorClassGate.Verdict.Allowed>()
        ExcludeOperatorClassGate.verdict("").shouldBeInstanceOf<ExcludeOperatorClassGate.Verdict.Allowed>()
        ExcludeOperatorClassGate.verdict("   ").shouldBeInstanceOf<ExcludeOperatorClassGate.Verdict.Allowed>()
    }

    test("single bare column WITH operator is allowed") {
        ExcludeOperatorClassGate.isAllowed("room WITH =") shouldBe true
    }

    test("multiple bare-column elements are allowed") {
        ExcludeOperatorClassGate.isAllowed("room WITH =, during WITH &&") shouldBe true
    }

    test("parenthesised expression element is allowed") {
        ExcludeOperatorClassGate.isAllowed("(room + 1) WITH =") shouldBe true
    }

    test("quoted identifier element is allowed") {
        ExcludeOperatorClassGate.isAllowed("\"My Column\" WITH =") shouldBe true
    }

    test("custom operator class between column and WITH is blocked") {
        val v = ExcludeOperatorClassGate.verdict("room my_int4_ops WITH =")
        v.shouldBeInstanceOf<ExcludeOperatorClassGate.Verdict.Blocked>()
    }

    test("ASC / DESC ordering token is blocked") {
        val v = ExcludeOperatorClassGate.verdict("room DESC WITH =")
        v.shouldBeInstanceOf<ExcludeOperatorClassGate.Verdict.Blocked>()
    }

    test("COLLATE token is blocked") {
        val v = ExcludeOperatorClassGate.verdict("name COLLATE \"C\" WITH =")
        v.shouldBeInstanceOf<ExcludeOperatorClassGate.Verdict.Blocked>()
    }

    test("missing WITH clause is blocked") {
        val v = ExcludeOperatorClassGate.verdict("room")
        v.shouldBeInstanceOf<ExcludeOperatorClassGate.Verdict.Blocked>()
    }

    test("missing operator after WITH is blocked") {
        val v = ExcludeOperatorClassGate.verdict("room WITH ")
        v.shouldBeInstanceOf<ExcludeOperatorClassGate.Verdict.Blocked>()
    }

    test("nested commas inside parens do not split elements") {
        // Two elements: `(a, b)` and `c`. Both heads are well-formed.
        ExcludeOperatorClassGate.isAllowed("(a + b) WITH =, c WITH &&") shouldBe true
    }

    test("a single bad element among many flips the whole verdict") {
        val v = ExcludeOperatorClassGate.verdict("room WITH =, during my_opclass WITH &&")
        v.shouldBeInstanceOf<ExcludeOperatorClassGate.Verdict.Blocked>()
    }

    test("depth-tracking: nested commas reset cleanly so a later bad element is still detected") {
        // `(a, b)` is a single element; the comma after `)` is the
        // top-level separator. The second element `room my_opclass WITH &&`
        // must still surface as Blocked even though it follows a
        // depth>0 comma boundary.
        val v = ExcludeOperatorClassGate.verdict("(a + b) WITH =, room my_opclass WITH &&")
        v.shouldBeInstanceOf<ExcludeOperatorClassGate.Verdict.Blocked>()
    }
})
