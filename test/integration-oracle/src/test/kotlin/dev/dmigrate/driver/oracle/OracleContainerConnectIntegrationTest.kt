package dev.dmigrate.driver.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import java.sql.DriverManager

// Slice-0-Spike (ADR 0052): belegt Container-Start + Treiber-Connect, bevor ab
// Slice 1 echte Port-Implementierungen dagegen getestet werden.
class OracleContainerConnectIntegrationTest : FunSpec({

    // Explizit auf 23ai gepinnt (ADR 0052) statt der gleitenden
    // "slim-faststart"-Tags: die liefern zum Aufnahmezeitpunkt bereits
    // "26ai" aus, und der Versions-Banner selbst wurde in "Oracle AI
    // Database" umbenannt -- die Assertion unten prüft deshalb nur noch auf
    // "Oracle", nicht auf einen versions-/branding-abhängigen Teilstring.
    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")

    beforeSpec {
        container.start()
    }

    test("SELECT banner FROM v\$version antwortet mit einer Oracle-Instanz") {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT banner FROM v\$version WHERE ROWNUM = 1").use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldContain "Oracle"
                }
            }
        }
    }
})
