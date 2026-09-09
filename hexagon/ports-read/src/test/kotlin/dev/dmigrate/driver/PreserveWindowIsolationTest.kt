package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Was ein Preserve-Fenster zusichert. Die drei Stufen unterscheiden sich
 * darin, ob ueberhaupt geschuetzt wird — nicht darin, wie.
 */
class PreserveWindowIsolationTest : FunSpec({

    test("nur NONE laesst das Fenster ungeschuetzt") {
        PreserveWindowIsolation.NONE.guardsWindow shouldBe false
        PreserveWindowIsolation.SERIALIZED.guardsWindow shouldBe true
        PreserveWindowIsolation.ATOMIC.guardsWindow shouldBe true
    }
})
