package dev.dmigrate.core.version

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class VersionInfoTest : FunSpec({

    test("PRODUCT_VERSION matches a semver-ish project version (placeholder resolved)") {
        // Muss exakt dem Akzeptanz-Pattern aus build.gradle.kts's
        // normalizedReleaseVersion (line 15) entsprechen — sonst blockiert
        // dieser Test einen Release, den der Root-Build bereits zugelassen
        // hat (z. B. `1.0.0+sha.abc` oder `0.9.8-rc-1`).
        VersionInfo.PRODUCT_VERSION shouldNotBe "unknown"
        VersionInfo.PRODUCT_VERSION shouldMatch Regex("""\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?""")
    }

    test("PRODUCT_VERSION is stable across reads (lazy init)") {
        val first = VersionInfo.PRODUCT_VERSION
        val second = VersionInfo.PRODUCT_VERSION
        second shouldBe first
    }
})
