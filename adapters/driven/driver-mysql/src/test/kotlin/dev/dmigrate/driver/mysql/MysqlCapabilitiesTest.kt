package dev.dmigrate.driver.mysql

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
class MysqlCapabilitiesTest : FunSpec({

    test("der ServiceLoader findet diesen Anbieter unter seinem Dialekt") {
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.MYSQL)
            .shouldBeSameInstanceAs(DialectReadCapabilityLookup.forDialect(DatabaseDialect.MYSQL))
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.MYSQL).dialect shouldBe DatabaseDialect.MYSQL
    }

    test("der Anbieter beantwortet alle Fragen seines Dialekts") {
        // MySQL hat keine Ownership, kein `CREATE OR REPLACE TRIGGER`, und ohne Version gilt die konservative Oracle-MySQL-Lesart.
        val provider = MysqlCapabilities
        provider.sequenceCapability().supportsOwnedBy shouldBe false
        provider.triggerCapability().enabled shouldBe false
        // Ohne gelesene Version gilt Oracle MySQL — es kennt kein CREATE OR REPLACE.
        provider.routineCapability(null).function.enabled shouldBe false
        provider.defaultSpatialProfile() shouldBe SpatialProfile.NATIVE
    }
})
