package dev.dmigrate.driver.sqlite

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
class SqliteCapabilitiesTest : FunSpec({

    test("der ServiceLoader findet diesen Anbieter unter seinem Dialekt") {
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.SQLITE)
            .shouldBeSameInstanceAs(DialectReadCapabilityLookup.forDialect(DatabaseDialect.SQLITE))
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.SQLITE).dialect shouldBe DatabaseDialect.SQLITE
    }

    test("der Anbieter beantwortet alle Fragen seines Dialekts") {
        // SQLite kennt keine benutzerdefinierten Routinen und kein `CREATE OR REPLACE TRIGGER`; Raumbezug nur ueber SpatiaLite.
        val provider = SqliteCapabilities
        provider.triggerCapability().enabled shouldBe false
        provider.routineCapability(null).function.enabled shouldBe false
        provider.defaultSpatialProfile() shouldBe SpatialProfile.NONE
        provider.allowedSpatialProfiles() shouldBe setOf(SpatialProfile.SPATIALITE, SpatialProfile.NONE)
    }
})
