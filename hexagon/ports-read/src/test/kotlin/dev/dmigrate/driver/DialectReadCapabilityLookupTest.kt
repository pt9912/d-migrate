package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Die zweite Capability-Naht — fuer die Faehigkeiten, deren Werttypen in
 * diesem Modul wohnen (Sequenzen, Routinen, Trigger, Raumbezug).
 *
 * Geprueft wird dasselbe wie beim Gegenstueck in `ports-common`: dass die
 * Anbieter ohne Bootstrap gefunden werden, dass jeder fuer genau seinen
 * Dialekt spricht, dass ein eingetragener vorgeht — und dass ein fehlender
 * benannt scheitert, statt einen Wert zu erfinden.
 */
class DialectReadCapabilityLookupTest : FunSpec({

    test("die Anbieter werden ohne Registry und ohne Bootstrap gefunden") {
        DatabaseDialect.entries.forEach { dialect ->
            DialectReadCapabilityLookup.forDialect(dialect).dialect shouldBe dialect
        }
    }

    test("jeder Anbieter antwortet fuer seinen eigenen Dialekt") {
        // Ein vertauschter META-INF/services-Eintrag faellt sonst nirgends auf.
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.POSTGRESQL)
            .sequenceCapability().supportsOwnedBy shouldBe true
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.MYSQL)
            .sequenceCapability().supportsOwnedBy shouldBe false
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.ORACLE)
            .triggerCapability().enabled shouldBe true
        DialectReadCapabilityLookup.forDialect(DatabaseDialect.SQLITE)
            .defaultSpatialProfile() shouldBe SpatialProfile.NONE
    }

    test("die MySQL-Familie wird erst durch die Version entschieden") {
        // `MYSQL` steht fuer zwei Server: Oracle MySQL kennt
        // `CREATE OR REPLACE FUNCTION` nicht, MariaDB schon. Ohne gelesene
        // Version gilt die konservative Lesart — ginge die Version auf dem Weg
        // verloren, bekaeme ein MariaDB-Ziel still den Drop+Create-Rueckfall.
        val provider = DialectReadCapabilityLookup.forDialect(DatabaseDialect.MYSQL)
        provider.routineCapability(null).function.enabled shouldBe false
        provider.routineCapability(MysqlServerVersion(8, 4, 0)).function.enabled shouldBe false
        provider.routineCapability(MysqlServerVersion(11, 4, 0, vendor = "MariaDB")).function.enabled shouldBe true
    }

    test("die Weiterleitungen im Hexagon holen dieselbe Antwort wie der Anbieter") {
        // `SequenceCapabilityDefaults` & Co. sind seit dem Slice nur noch
        // Weiterleitungen. Faende eine davon den falschen Anbieter, waere das
        // an ihrem Ergebnis nicht zu sehen — hier schon.
        val dialect = DatabaseDialect.ORACLE
        val provider = DialectReadCapabilityLookup.forDialect(dialect)
        SequenceCapabilityDefaults.forDialect(dialect) shouldBe provider.sequenceCapability()
        TriggerCapabilityDefaults.forDialect(dialect) shouldBe provider.triggerCapability()
        RoutineCapabilityDefaults.forDialect(dialect) shouldBe provider.routineCapability(null)
        SpatialProfilePolicy.defaultFor(dialect) shouldBe provider.defaultSpatialProfile()
        SpatialProfilePolicy.allowedFor(dialect) shouldBe provider.allowedSpatialProfiles()
    }

    test("ein eingetragener Anbieter geht dem gefundenen vor und laesst sich zuruecknehmen") {
        val echt = DialectReadCapabilityLookup.forDialect(DatabaseDialect.SQLITE)
        DialectReadCapabilityLookup.register(
            object : DialectReadCapabilityProvider by echt {
                override fun defaultSpatialProfile() = SpatialProfile.SPATIALITE
            }
        )
        try {
            SpatialProfilePolicy.defaultFor(DatabaseDialect.SQLITE) shouldBe SpatialProfile.SPATIALITE
        } finally {
            DialectReadCapabilityLookup.unregister(DatabaseDialect.SQLITE)
        }
        SpatialProfilePolicy.defaultFor(DatabaseDialect.SQLITE) shouldBe SpatialProfile.NONE
    }

    test("ein fehlender Anbieter scheitert benannt, statt einen Wert zu erfinden") {
        // Der Zweig ist hier nicht erreichbar — dieses Modul fuehrt alle fuenf
        // Treiber auf dem Test-Klassenpfad. Geprueft wird sein Produkt.
        val meldung = DialectReadCapabilityLookup.missingProviderMessage(DatabaseDialect.ORACLE, emptySet())
        meldung shouldContain "ORACLE"
        meldung shouldContain "classpath"
        meldung shouldContain "none"
    }
})
