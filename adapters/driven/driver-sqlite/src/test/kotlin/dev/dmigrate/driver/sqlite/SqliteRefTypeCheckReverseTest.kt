package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SqliteRefTypeCheckReverseTest : FunSpec({

    val extract = { exprs: List<String>, taken: Set<String> ->
        SqliteRefTypeCheckReverse.extract("t", listOf("id", "e"), exprs, taken)
    }

    test("genau ein Wertelisten-CHECK je Spalte wird ein Constraint mit festem Praefix") {
        val result = extract(listOf("e IN ('a', 'b')"), emptySet())

        result.constraints.single().name shouldBe "__dm_enum_t_e"
        result.constraints.single().expression shouldBe "e IN ('a', 'b')"
        result.remaining shouldBe emptyList()
    }

    test("ein Ausdruck, der keine Werteliste ist, bleibt uebrig") {
        val result = extract(listOf("id > 0"), emptySet())

        result.constraints shouldBe emptyList()
        result.remaining shouldBe listOf("id > 0")
    }

    test("ein belegter Name verhindert den Constraint") {
        val result = extract(listOf("e IN ('a')"), setOf("__dm_enum_t_e"))

        result.constraints shouldBe emptyList()
        result.remaining shouldBe listOf("e IN ('a')")
    }

    test("zwei Wertelisten auf einer Spalte bleiben beide uebrig") {
        val result = extract(listOf("e IN ('a')", "e IN ('b')"), emptySet())

        result.constraints shouldBe emptyList()
        result.remaining shouldBe listOf("e IN ('a')", "e IN ('b')")
    }

    test("columnOf findet die Spalte nur fuer einen synthetischen Constraint") {
        val synthetic = ConstraintDefinition("__dm_enum_t_e", ConstraintType.CHECK, expression = "e IN ('a')")
        val named = ConstraintDefinition("ck_t_e", ConstraintType.CHECK, expression = "e IN ('a')")

        val foreign = ConstraintDefinition("__dm_enum_irgendwas", ConstraintType.CHECK, expression = "e IN ('a')")

        SqliteRefTypeCheckReverse.columnOf("t", synthetic, listOf("id", "e")) shouldBe "e"
        SqliteRefTypeCheckReverse.columnOf("t", named, listOf("id", "e")) shouldBe null
        SqliteRefTypeCheckReverse.columnOf("t", synthetic, listOf("id")) shouldBe null
        // Nur der Name, den extract vergibt -- ein gleich beginnender, vom Nutzer benannter CHECK nicht.
        SqliteRefTypeCheckReverse.columnOf("t", foreign, listOf("id", "e")) shouldBe null
        SqliteRefTypeCheckReverse.columnOf("other", synthetic, listOf("id", "e")) shouldBe null
    }
})
