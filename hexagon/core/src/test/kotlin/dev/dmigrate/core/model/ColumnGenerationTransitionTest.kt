package dev.dmigrate.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ColumnGenerationTransitionTest : FunSpec({

    val computed = ColumnGeneration.Computed("a * 2", stored = true)
    val virtual = ColumnGeneration.Computed("a * 2", stored = false)
    val identity = ColumnGeneration.Identity(IdentityMode.ALWAYS)

    test("two computed sides are an expression change") {
        ColumnGenerationTransition.of(computed, virtual) shouldBe
            ColumnGenerationTransition.COMPUTED_EXPRESSION
    }

    test("nothing to computed is an addition, computed to nothing a removal") {
        ColumnGenerationTransition.of(null, computed) shouldBe ColumnGenerationTransition.COMPUTED_ADDED
        ColumnGenerationTransition.of(computed, null) shouldBe ColumnGenerationTransition.COMPUTED_DROPPED
    }

    test("identity on either side is an identity matter, not an expression one") {
        ColumnGenerationTransition.of(null, identity) shouldBe ColumnGenerationTransition.IDENTITY
        ColumnGenerationTransition.of(identity, null) shouldBe ColumnGenerationTransition.IDENTITY
        ColumnGenerationTransition.of(identity, ColumnGeneration.Identity(IdentityMode.BY_DEFAULT)) shouldBe
            ColumnGenerationTransition.IDENTITY
    }

    /**
     * Der Tausch berechnet ↔ Identity faellt bewusst in [ColumnGenerationTransition.IDENTITY]:
     * er ist keine Ausdrucksaenderung, und die Meldung darf nicht so klingen.
     */
    test("swapping computed for identity is an identity matter in both directions") {
        ColumnGenerationTransition.of(computed, identity) shouldBe ColumnGenerationTransition.IDENTITY
        ColumnGenerationTransition.of(identity, computed) shouldBe ColumnGenerationTransition.IDENTITY
    }

    /** Ohne Aenderung wird die Operation nie erzeugt; die Einteilung bleibt trotzdem definiert. */
    test("null on both sides classifies as a removal, not as an expression change") {
        ColumnGenerationTransition.of(null, null) shouldBe ColumnGenerationTransition.COMPUTED_DROPPED
    }
})
