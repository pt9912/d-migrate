package dev.dmigrate.integration

import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.postgresql.PostgresDriver
import dev.dmigrate.migration.MigrationTool
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files

private val IntegrationTag = NamedTag("integration")

/**
 * Phase E: Flyway runtime validation against PostgreSQL.
 *
 * Generates Flyway artifacts via the production export path, then runs
 * Flyway.migrate() against a real PostgreSQL container and verifies
 * the expected schema effect.
 */
class FlywayRuntimeTest : FunSpec({

    tags(IntegrationTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("flyway_test")
        .withUsername("test")
        .withPassword("test")

    beforeSpec {
        DatabaseDriverRegistry.register(PostgresDriver())
        container.start()
    }

    afterSpec {
        container.stop()
    }

    // Note on Flyway Undo: The U-prefix undo migration is generated correctly
    // by the exporter (tested in FlywayMigrationExporterTest), but Flyway Undo
    // requires Flyway Teams or Enterprise edition. The open-source Flyway API
    // used here does not support undo, so this test validates only the forward
    // V-migration. The undo artifact's content correctness is covered by the
    // renderer unit tests in Phase C.

    test("Flyway applies generated V-migration against PostgreSQL") {
        val tempDir = Files.createTempDirectory("flyway-runtime")
        val outputDir = tempDir.resolve("migrations")

        val exitCode = RuntimeTestSupport.exportArtifacts(
            tool = MigrationTool.FLYWAY,
            dialect = "postgresql",
            version = "1",
            outputDir = outputDir,
            generateRollback = false,
        )
        exitCode shouldBe 0

        val flyway = Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("filesystem:${outputDir.toAbsolutePath()}")
            .load()

        val result = flyway.migrate()
        result.success shouldBe true

        // Verify schema effect
        container.createConnection("").use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT table_name FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name IN ('users', 'orders') " +
                    "ORDER BY table_name"
                )
                val tables = mutableListOf<String>()
                while (rs.next()) tables += rs.getString("table_name")
                tables shouldBe listOf("orders", "users")
            }
        }

        tempDir.toFile().deleteRecursively()
    }
})
