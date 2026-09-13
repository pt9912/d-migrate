package dev.dmigrate.driver

import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Der Default von [DatabaseDriver.capabilities].
 *
 * Er ist da, damit eine Attrappe keine Werte erfinden muss, die sie nicht
 * kennt — und genau das ist die Zusage, die hier gehalten werden muss: er
 * liefert die **echte** Antwort des Dialekts oder einen benannten
 * Fehlschlag, nie eine ausgedachte.
 *
 * Dieses Modul fuehrt bewusst keinen Treiber auf dem Test-Klassenpfad (sein
 * `loadAll`-Test prueft, dass der ServiceLoader **seine** Attrappe findet, und
 * ein echter Treiber gewaenne dagegen). Beide Seiten sind hier also erreichbar.
 */
class DatabaseDriverCapabilitiesTest : FunSpec({

    test("ein Treiber ohne eigene Antwort bekommt die des eingetragenen Anbieters") {
        val erwartet = DialectCapabilities(
            supportsViews = true,
            supportsFunctions = false,
            supportsProcedures = false,
            supportsTriggers = false,
            supportsSequences = false,
            supportsCustomTypes = false,
            supportsPartitioning = false,
            batchSeparator = "GO",
        )
        DialectCapabilityLookup.register(
            object : DialectCapabilityProvider {
                override val dialect = DatabaseDialect.MSSQL
                override fun capabilities(serverVersion: ServerVersion?) = erwartet
            }
        )
        try {
            CapabilityStubDriver(DatabaseDialect.MSSQL).capabilities(null) shouldBe erwartet
        } finally {
            DialectCapabilityLookup.unregister(DatabaseDialect.MSSQL)
        }
    }

    test("ohne Anbieter scheitert die Frage benannt, statt etwas zu erfinden") {
        // Kein Treibermodul auf dem Test-Klassenpfad dieses Moduls, also findet
        // der ServiceLoader nichts. Ein erfundener Vorgabewert waere hier der
        // teuerste Ausgang: plausibel, still und fuer jeden echten Dialekt falsch.
        val fehler = shouldThrow<IllegalStateException> {
            CapabilityStubDriver(DatabaseDialect.ORACLE).capabilities(null)
        }
        fehler.message!! shouldContain "ORACLE"
    }
})

/**
 * Attrappe, die [DatabaseDriver.capabilities] **nicht** ueberschreibt — genau
 * der Fall, fuer den der Default existiert. Jeder andere Port wirft; dieser
 * Test ruft keinen davon.
 */
private class CapabilityStubDriver(override val dialect: DatabaseDialect) : DatabaseDriver {
    override fun ddlGenerator(): DdlGenerator = error("no DdlGenerator")
    override fun dataReader(): DataReader = error("no DataReader")
    override fun tableLister(): TableLister = error("no TableLister")
    override fun dataWriter(): DataWriter = error("no DataWriter")
    override fun urlBuilder(): JdbcUrlBuilder = error("no JdbcUrlBuilder")
    override fun schemaReader(): SchemaReader = error("no SchemaReader")
}
