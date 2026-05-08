package dev.dmigrate.driver.connection

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Phase E0.7.1: [PoolSettings] trägt die Cancel-Reaktions-Schranken aus
 * implementation-plan-0.9.6 §4.1. Default ist `30000ms` (obere Schranke);
 * `0` deaktiviert; negative Werte sind Konstruktionsfehler.
 */
class PoolSettingsTest : FunSpec({

    test("defaults match the spec — statementTimeoutMs and networkTimeoutMs both 30_000") {
        val settings = PoolSettings()
        settings.statementTimeoutMs shouldBe 30_000
        settings.networkTimeoutMs shouldBe 30_000
    }

    test("explicit zero disables the timeout") {
        val settings = PoolSettings(statementTimeoutMs = 0, networkTimeoutMs = 0)
        settings.statementTimeoutMs shouldBe 0
        settings.networkTimeoutMs shouldBe 0
    }

    test("custom positive values pass validation") {
        val settings = PoolSettings(statementTimeoutMs = 5_000, networkTimeoutMs = 12_000)
        settings.statementTimeoutMs shouldBe 5_000
        settings.networkTimeoutMs shouldBe 12_000
    }

    test("negative statementTimeoutMs is a construction error") {
        val ex = shouldThrow<IllegalArgumentException> {
            PoolSettings(statementTimeoutMs = -1)
        }
        ex.message?.shouldContain("statementTimeoutMs")
        ex.message?.shouldContain("-1")
    }

    test("negative networkTimeoutMs is a construction error") {
        val ex = shouldThrow<IllegalArgumentException> {
            PoolSettings(networkTimeoutMs = -1)
        }
        ex.message?.shouldContain("networkTimeoutMs")
        ex.message?.shouldContain("-1")
    }

    test("Int.MIN_VALUE is rejected") {
        shouldThrow<IllegalArgumentException> {
            PoolSettings(statementTimeoutMs = Int.MIN_VALUE)
        }
        shouldThrow<IllegalArgumentException> {
            PoolSettings(networkTimeoutMs = Int.MIN_VALUE)
        }
    }

    test("existing fields keep their defaults — no regression") {
        val settings = PoolSettings()
        settings.maximumPoolSize shouldBe 10
        settings.minimumIdle shouldBe 2
        settings.connectionTimeoutMs shouldBe 10_000L
        settings.idleTimeoutMs shouldBe 300_000L
        settings.maxLifetimeMs shouldBe 600_000L
        settings.keepaliveTimeMs shouldBe 60_000L
    }
})
