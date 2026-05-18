package dev.dmigrate.core.diff.migration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * F.4 Sub-Slice A.1: structural pins for [RenameSupport]. The sealed
 * surface is consumed by Mapper-/Planner-phase policy code; the test
 * makes the three branches and their payloads visible in case a
 * future tranche adds a fourth case or changes the field set.
 */
class RenameSupportTest : FunSpec({

    test("Native is a singleton data object") {
        val a: RenameSupport = RenameSupport.Native
        val b: RenameSupport = RenameSupport.Native
        a shouldBe b
        a shouldBe RenameSupport.Native
    }

    test("DropCreateFallback carries rationale and is value-comparable") {
        val first = RenameSupport.DropCreateFallback("MySQL has no ALTER TRIGGER RENAME")
        val second = RenameSupport.DropCreateFallback("MySQL has no ALTER TRIGGER RENAME")
        val third = RenameSupport.DropCreateFallback("SQLite has no view rename")
        first shouldBe second
        first shouldNotBe third
        first.rationale shouldBe "MySQL has no ALTER TRIGGER RENAME"
    }

    test("Blocked carries code + message and is value-comparable") {
        val first = RenameSupport.Blocked(code = "MV_RENAME_OUT_OF_SCOPE", message = "Materialized views are not part of F.4")
        val second = RenameSupport.Blocked(code = "MV_RENAME_OUT_OF_SCOPE", message = "Materialized views are not part of F.4")
        first shouldBe second
        first.code shouldBe "MV_RENAME_OUT_OF_SCOPE"
        first.message shouldBe "Materialized views are not part of F.4"
    }

    test("when-exhaustiveness over the sealed hierarchy") {
        // Compile-time-guard via an exhaustive `when` — if a fourth
        // RenameSupport variant is added, this test stops compiling.
        fun classify(s: RenameSupport): String = when (s) {
            RenameSupport.Native -> "native"
            is RenameSupport.DropCreateFallback -> "fallback"
            is RenameSupport.Blocked -> "blocked"
        }
        classify(RenameSupport.Native) shouldBe "native"
        classify(RenameSupport.DropCreateFallback("x")) shouldBe "fallback"
        classify(RenameSupport.Blocked("X", "y")) shouldBe "blocked"

        // Smart-cast pin: branching on `is Blocked` yields access to
        // the data-class fields.
        val s: RenameSupport = RenameSupport.Blocked("C", "M")
        s.shouldBeInstanceOf<RenameSupport.Blocked>()
        s.code shouldBe "C"
    }
})
