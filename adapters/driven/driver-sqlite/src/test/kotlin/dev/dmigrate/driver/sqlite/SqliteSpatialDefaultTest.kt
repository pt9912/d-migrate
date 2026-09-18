package dev.dmigrate.driver.sqlite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * P7 — SpatiaLites Fuellwert an einer registrierten Geometriespalte ist kein
 * Anwender-Default.
 *
 * `AddGeometryColumn(…, 1)` legt `"<spalte>" <TYP> NOT NULL DEFAULT ''` an
 * (gemessen an SpatiaLite 5.1.0); `PRAGMA table_info` meldet `dflt_value =
 * ''`. Kaeme er als `default: ""` ins Modell, blockte der **zweite**
 * `schema generate` genau die Tabelle, die der erste angelegt hat — ein
 * Default ist selbst ein `E052`-Ausloeser.
 */
class SqliteSpatialDefaultTest : FunSpec({

    test("an einer registrierten Geometriespalte faellt das leere Literal weg") {
        SqliteSpatialDefault.userDefault("''", isRegisteredGeometry = true).shouldBeNull()
    }

    // Gegenprobe 1: an einer gewoehnlichen Spalte ist `DEFAULT ''` ein
    // Anwender-Default und kommt unveraendert mit.
    test("an einer gewoehnlichen Spalte bleibt das leere Literal stehen") {
        SqliteSpatialDefault.userDefault("''", isRegisteredGeometry = false) shouldBe "''"
    }

    // Gegenprobe 2: ein anderer Default an einer Geometriespalte kann nicht
    // von SpatiaLite stammen und bleibt.
    test("ein anderer Default an einer Geometriespalte bleibt stehen") {
        SqliteSpatialDefault.userDefault("'x'", isRegisteredGeometry = true) shouldBe "'x'"
    }

    test("kein Default bleibt kein Default") {
        SqliteSpatialDefault.userDefault(null, isRegisteredGeometry = true).shouldBeNull()
        SqliteSpatialDefault.userDefault(null, isRegisteredGeometry = false).shouldBeNull()
    }
})
