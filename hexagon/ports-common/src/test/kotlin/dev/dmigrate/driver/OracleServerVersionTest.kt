package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class OracleServerVersionTest : FunSpec({

    test("die Release-Nummer wird an ihrer ersten Stelle gelesen, nicht am Banner") {
        val version = OracleServerVersion.parse("23.26.3.0.0")
        version?.major shouldBe 23
        version?.raw shouldBe "23.26.3.0.0"
    }

    test("ein Bannertext ist keine Release-Nummer") {
        OracleServerVersion.parse("Oracle AI Database 26ai Free Release 23.26.3.0.0").shouldBeNull()
        OracleServerVersion.parse("").shouldBeNull()
        OracleServerVersion.parse("23c").shouldBeNull()
    }

    test("die IF-EXISTS-Klausel beginnt mit der 23er-Linie") {
        OracleServerVersion.parse("23.0.0.0.0")?.supportsDropIfExists shouldBe true
        OracleServerVersion.parse("19.0.0.0.0")?.supportsDropIfExists shouldBe false
        OracleServerVersion.parse("12.2.0.1.0")?.supportsDropIfExists shouldBe false
    }
})
