package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.data.OracleEmptyString
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Die Oberflaechen-Schreibweise der Schreib-Praeferenz
 * (`dialect-preference-mechanism.md`).
 *
 * Der Kern ist die Strenge: ein nicht erkannter Wert ist KEIN stiller
 * Rueckfall auf den Default. Ohne das `literal:`-Praefix waere jeder
 * Tippfehler ein gueltiger Ersatztext und landete unbemerkt in der Spalte.
 */
class OracleEmptyStringPreferenceTest : FunSpec({

    test("error is the keyword for the conservative default") {
        OracleEmptyString.parse("error") shouldBe OracleEmptyString.Error
    }

    test("literal: carries the substitute text verbatim, including spaces") {
        OracleEmptyString.parse("literal: ") shouldBe OracleEmptyString.Literal(" ")
        OracleEmptyString.parse("literal:n/a") shouldBe OracleEmptyString.Literal("n/a")
        // Auch ein leerer Ersatztext ist eine Aussage -- und faellt beim
        // Schreiben in dieselbe Oracle-Falle. Das Parsen erfindet hier nichts.
        OracleEmptyString.parse("literal:") shouldBe OracleEmptyString.Literal("")
    }

    test("anything else is unrecognised, not a silent default") {
        // Ein Tippfehler im Schluesselwort waere ohne Praefix ein gueltiger
        // Ersatztext gewesen -- genau die Ueberraschung, die der Mechanismus
        // verhindern soll.
        OracleEmptyString.parse("eror").shouldBeNull()
        OracleEmptyString.parse("ERROR").shouldBeNull()
        OracleEmptyString.parse(" ").shouldBeNull()
        OracleEmptyString.parse("null").shouldBeNull()
    }
})
