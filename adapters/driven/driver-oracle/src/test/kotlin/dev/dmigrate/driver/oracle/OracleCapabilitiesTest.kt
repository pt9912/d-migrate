package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectReadCapabilityLookup
import dev.dmigrate.driver.SpatialProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * Die Faehigkeiten dieses Dialekts liegen seit dem Capability-Slice hier statt
 * in einer geteilten Tabelle im Hexagon. Geprueft wird, was dabei still
 * brechen kann: die Verdrahtung ueber den `ServiceLoader` und die Zuordnung
 * Anbieter → Dialekt.
 *
 * Ein vertauschter `META-INF/services`-Eintrag faellt sonst nirgends auf — die
 * Antworten waeren plausibel, nur fuer den falschen Server.
 */
class OracleCapabilitiesTest : FunSpec({

    test("der ServiceLoader findet diesen Anbieter unter seinem Dialekt") {
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.ORACLE)
            .shouldBeSameInstanceAs(DialectReadCapabilityLookup.forDialect(DatabaseDialect.ORACLE))
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.ORACLE).dialect shouldBe DatabaseDialect.ORACLE
    }

    test("der Anbieter beantwortet alle Fragen seines Dialekts") {
        // Oracle kennt `CREATE OR REPLACE` fuer Trigger und Routinen nativ, SDO_GEOMETRY und als einziger Bitmap-Indizes.
        val provider = OracleCapabilities
        provider.triggerCapability().enabled shouldBe true
        provider.routineCapability(null).function.enabled shouldBe true
        provider.defaultSpatialProfile() shouldBe SpatialProfile.NATIVE
        provider.capabilities(null).supportsBitmapIndexes shouldBe true
    }
})
