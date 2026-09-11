package dev.dmigrate.driver.mssql

import dev.dmigrate.test.containers.newMssqlContainer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.DriverManager

// Slice-0-Spike (ADR 0047): belegt Container-Start + Treiber-Connect, bevor ab
// Slice 1 echte Port-Implementierungen dagegen getestet werden.
class MssqlContainerConnectIntegrationTest : FunSpec({

    val container = newMssqlContainer()

    beforeSpec {
        container.start()
    }

    test("SELECT @@VERSION antwortet mit SQL Server 2025") {
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT @@VERSION").use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldContain "Microsoft SQL Server 2025"
                }
            }
        }
    }
})
