package dev.dmigrate.integration

import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.postgresql.PostgresDriver
import dev.dmigrate.migration.MigrationTool
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.DirectoryResourceAccessor
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.sql.DriverManager

private val IntegrationTag = NamedTag("integration")

/**
 * Phase E: Liquibase runtime validation against PostgreSQL.
 *
 * Generates a Liquibase XML changelog via the production export path,
 * then runs Liquibase update + rollback against a real PostgreSQL
 * container and verifies the expected schema effect.
 */
class LiquibaseRuntimeTest : FunSpec({

    tags(IntegrationTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("liquibase_test")
        .withUsername("test")
        .withPassword("test")

    beforeSpec {
        DatabaseDriverRegistry.register(PostgresDriver())
        container.start()
    }

    afterSpec {
        container.stop()
    }

    test("Liquibase applies generated changelog against PostgreSQL") {
        val tempDir = Files.createTempDirectory("liquibase-runtime")
        val outputDir = tempDir.resolve("changelog")

        val exitCode = RuntimeTestSupport.exportArtifacts(
            tool = MigrationTool.LIQUIBASE,
            dialect = "postgresql",
            version = "1.0",
            outputDir = outputDir,
            generateRollback = true,
        )
        exitCode shouldBe 0

        // Find the generated changelog file
        val changelogFile = outputDir.toFile().listFiles()!!
            .first { it.name.endsWith(".xml") }

        val conn = DriverManager.getConnection(
            container.jdbcUrl, container.username, container.password
        )
        val database = DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(JdbcConnection(conn))

        val liquibase = Liquibase(
            changelogFile.name,
            DirectoryResourceAccessor(outputDir.toFile()),
            database,
        )

        // Apply migration
        liquibase.update()

        // Verify schema effect
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

        // Validate rollback
        liquibase.rollback(1, null)

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(
                "SELECT count(*) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name IN ('users', 'orders')"
            )
            rs.next()
            rs.getInt(1) shouldBe 0
        }

        conn.close()
        tempDir.toFile().deleteRecursively()
    }
})
