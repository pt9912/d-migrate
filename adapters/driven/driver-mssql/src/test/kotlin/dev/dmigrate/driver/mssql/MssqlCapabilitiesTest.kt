package dev.dmigrate.driver.mssql

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
class MssqlCapabilitiesTest : FunSpec({

    test("der ServiceLoader findet diesen Anbieter unter seinem Dialekt") {
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.MSSQL)
            .shouldBeSameInstanceAs(DialectReadCapabilityLookup.forDialect(DatabaseDialect.MSSQL))
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.MSSQL).dialect shouldBe DatabaseDialect.MSSQL
    }

    test("der Anbieter beantwortet alle Fragen seines Dialekts") {
        // SQL Server faellt bei Triggern und Routinen auf Drop+Create zurueck; sein Raumbezug ist nativ, und Skripte brauchen `GO`.
        val provider = MssqlCapabilities
        provider.triggerCapability().enabled shouldBe false
        provider.routineCapability(null).function.enabled shouldBe false
        provider.defaultSpatialProfile() shouldBe SpatialProfile.NATIVE
        provider.capabilities(null).batchSeparator shouldBe "GO"
    }
})
