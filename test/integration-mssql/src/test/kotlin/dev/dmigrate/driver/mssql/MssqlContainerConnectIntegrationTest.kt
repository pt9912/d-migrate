package dev.dmigrate.driver.mssql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager

// Slice-0-Spike (ADR 0047): belegt Container-Start + Treiber-Connect, bevor ab
// Slice 1 echte Port-Implementierungen dagegen getestet werden.
class MssqlContainerConnectIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        // Das Image startet nur mit akzeptierter Microsoft-EULA
        // (ACCEPT_EULA=Y, siehe docs/user/quality.md).
        .acceptLicense()
        // mssql-jdbc >= 10 setzt encrypt=true als Default; der Container hat
        // nur ein Self-Signed-Zertifikat.
        .withUrlParam("encrypt", "false")

    beforeSpec {
        container.start()
    }

    test("SELECT @@VERSION antwortet mit SQL Server 2022") {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT @@VERSION").use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldContain "Microsoft SQL Server 2022"
                }
            }
        }
    }
})
