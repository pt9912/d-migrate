package dev.dmigrate.driver.postgresql

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
class PostgresCapabilitiesTest : FunSpec({

    test("der ServiceLoader findet diesen Anbieter unter seinem Dialekt") {
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.POSTGRESQL)
            .shouldBeSameInstanceAs(DialectReadCapabilityLookup.forDialect(DatabaseDialect.POSTGRESQL))
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.POSTGRESQL).dialect shouldBe DatabaseDialect.POSTGRESQL
    }

    test("der Anbieter beantwortet alle Fragen seines Dialekts") {
        // PostgreSQL kennt `OWNED BY`, `CREATE OR REPLACE TRIGGER` (ab 14) und Routinen nativ; sein Raumbezug ist PostGIS.
        val provider = PostgresCapabilities
        provider.sequenceCapability().supportsOwnedBy shouldBe true
        provider.triggerCapability().enabled shouldBe true
        provider.routineCapability(null).function.enabled shouldBe true
        provider.defaultSpatialProfile() shouldBe SpatialProfile.POSTGIS
    }
})
