package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Die Naht, durch die „kann das Ziel das?" geht, seit die Werte im
 * Treibermodul liegen.
 *
 * Geprueft wird, was der Umbau **neu** riskiert — nicht die Werte selbst, die
 * stehen in [DialectCapabilitiesTest]: dass die Anbieter ohne Bootstrap
 * gefunden werden, dass ein Test einen eigenen setzen kann, und dass ein
 * fehlender Anbieter benannt scheitert statt einen Wert zu erfinden.
 */
class DialectCapabilityLookupTest : FunSpec({

    test("die Anbieter werden ohne Registry und ohne Bootstrap gefunden") {
        // Kein RuntimeBootstrap.initialize(), kein DatabaseDriverRegistry.loadAll():
        // der Lookup laedt selbst ueber den ServiceLoader. Genau das war der Grund,
        // ihn nicht an die Registry zu haengen — sie wird erst beim Start gefuellt.
        DatabaseDialect.entries.forEach { dialect ->
            DialectCapabilityLookup.forDialect(dialect).supportsViews shouldBe true
        }
    }

    test("jeder Anbieter spricht fuer genau den Dialekt, unter dem er gefunden wird") {
        // Ein vertauschter Eintrag im META-INF/services-File faellt sonst nirgends
        // auf: die Antworten waeren plausibel, nur fuer den falschen Server.
        DialectCapabilityLookup.forDialect(DatabaseDialect.MSSQL).batchSeparator shouldBe "GO"
        DialectCapabilityLookup.forDialect(DatabaseDialect.POSTGRESQL).batchSeparator shouldBe null
        DialectCapabilityLookup.forDialect(DatabaseDialect.ORACLE).supportsBitmapIndexes shouldBe true
        DialectCapabilityLookup.forDialect(DatabaseDialect.SQLITE).supportsPartitioning shouldBe false
    }

    test("ein eingetragener Anbieter geht dem gefundenen vor und laesst sich zuruecknehmen") {
        val echt = DialectCapabilityLookup.forDialect(DatabaseDialect.MYSQL)
        echt.supportsSequences shouldBe false

        DialectCapabilityLookup.register(
            object : DialectCapabilityProvider {
                override val dialect = DatabaseDialect.MYSQL
                override fun capabilities(serverVersion: ServerVersion?) =
                    echt.copy(supportsSequences = true)
            }
        )
        try {
            DialectCapabilityLookup.forDialect(DatabaseDialect.MYSQL).supportsSequences shouldBe true
        } finally {
            DialectCapabilityLookup.unregister(DatabaseDialect.MYSQL)
        }

        DialectCapabilityLookup.forDialect(DatabaseDialect.MYSQL).supportsSequences shouldBe false
    }

    test("die Zielversion erreicht den Anbieter, statt unterwegs verloren zu gehen") {
        // Die versionsabhaengigen Faehigkeiten sind der Grund, warum der Port
        // `ServerVersion?` nimmt statt nur den Dialekt. Ginge der Wert auf dem Weg
        // verloren, antwortete jede Frage still fuer die neueste gemessene Version.
        val alt = DialectCapabilityLookup.forTarget(DatabaseDialect.POSTGRESQL, PostgresServerVersion(16, 0))
        val neu = DialectCapabilityLookup.forTarget(DatabaseDialect.POSTGRESQL, PostgresServerVersion(18, 0))
        alt.supportsVirtualComputedColumns shouldBe false
        neu.supportsVirtualComputedColumns shouldBe true
    }

    test("ein fehlender Anbieter scheitert benannt, statt einen Wert zu erfinden") {
        // Der teuerste denkbare Fehler waere ein erfundener Vorgabewert: er waere
        // fuer jeden echten Dialekt falsch und faele nirgends auf. Deshalb ein
        // Fehlschlag, der den Dialekt nennt und sagt, was zu tun ist.
        //
        // Der Zweig selbst ist hier nicht erreichbar — dieses Modul fuehrt alle
        // fuenf Treiber auf dem Test-Klassenpfad. Geprueft wird deshalb sein
        // Produkt: die Meldung.
        val meldung = DialectCapabilityLookup.missingProviderMessage(DatabaseDialect.MYSQL, emptySet())
        meldung shouldContain "MYSQL"
        meldung shouldContain "classpath"
        meldung shouldContain "none"

        val mitBestand =
            DialectCapabilityLookup.missingProviderMessage(DatabaseDialect.MYSQL, setOf(DatabaseDialect.SQLITE))
        mitBestand shouldContain "SQLITE"
    }
})
