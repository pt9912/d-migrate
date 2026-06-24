package dev.dmigrate.driver

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PartitionLiteralGuardTest : FunSpec({

    test("safe literals are returned unchanged") {
        PartitionLiteralGuard.ensureSafe("'2022-01-01 00:00:00+00'", "p0") shouldBe "'2022-01-01 00:00:00+00'"
        PartitionLiteralGuard.ensureSafe("MAXVALUE", "p0") shouldBe "MAXVALUE"
        PartitionLiteralGuard.ensureSafe("100", "p0") shouldBe "100"
    }

    test("a statement terminator is rejected") {
        shouldThrow<IllegalArgumentException> { PartitionLiteralGuard.ensureSafe("'x'); DROP TABLE t", "p0") }
    }

    test("a line comment is rejected") {
        shouldThrow<IllegalArgumentException> { PartitionLiteralGuard.ensureSafe("1 -- comment", "p0") }
    }

    test("a block comment opener is rejected") {
        shouldThrow<IllegalArgumentException> { PartitionLiteralGuard.ensureSafe("1 /* x */", "p0") }
    }
})
