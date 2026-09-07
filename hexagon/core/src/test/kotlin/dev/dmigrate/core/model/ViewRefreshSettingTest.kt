package dev.dmigrate.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Die kanonische Form ist der Grund, warum ein Migrate-Lauf konvergiert:
 * gleichbedeutende Schreibweisen muessen im Modell gleich sein.
 */
class ViewRefreshSettingTest : FunSpec({

    test("equivalent spellings collapse to the same canonical form") {
        val equivalent = listOf("on_demand", "on demand", "force on demand", "FORCE ON DEMAND", " force  on  demand ")
        // Alle sind die Voreinstellung -- und die ist keine Angabe.
        equivalent.forEach { ViewRefreshSetting.canonical(it).shouldBeNull() }
        ViewRefreshSetting.canonical(null).shouldBeNull()
    }

    test("a non-default setting keeps its two parts in one spelling") {
        ViewRefreshSetting.canonical("COMPLETE") shouldBe "complete on demand"
        ViewRefreshSetting.canonical("complete on demand") shouldBe "complete on demand"
        ViewRefreshSetting.canonical("on_commit") shouldBe "force on commit"
        ViewRefreshSetting.canonical("fast on commit") shouldBe "fast on commit"
    }

    test("never binds method and trigger together") {
        // Ein Ausloeser zu `never` ergaebe keinen Sinn; beide Schreibweisen
        // meinen dasselbe.
        ViewRefreshSetting.canonical("never") shouldBe "never"
        ViewRefreshSetting.canonical("complete on never") shouldBe "never"
        ViewRefreshSetting.canonical("never on commit") shouldBe "never"
    }

    test("an unreadable setting is kept verbatim, not dropped") {
        // Verworfen waere es von „keine Angabe" nicht mehr zu unterscheiden,
        // und der Dialekt koennte es nicht melden.
        ViewRefreshSetting.canonical("hourly") shouldBe "hourly"
        ViewRefreshSetting.canonical("complete on tuesday") shouldBe "complete on tuesday"
        ViewRefreshSetting.parse("hourly").shouldBeNull()
    }

    test("a missing setting parses to the default rather than to nothing") {
        ViewRefreshSetting.parse(null) shouldBe ViewRefreshSetting.DEFAULT
    }
})
