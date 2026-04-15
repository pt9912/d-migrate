package dev.dmigrate.integration

import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.sqlite.SqliteDriver
import dev.dmigrate.migration.MigrationTool
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.writeText

private val IntegrationTag = NamedTag("integration")

/**
 * Phase E: Knex runtime validation against SQLite.
 *
 * Generates a Knex CommonJS migration via the production export path,
 * creates a minimal Knex project, runs `npx knex migrate:latest` and
 * `migrate:rollback`, and verifies the expected schema effect.
 *
 * Requires Node.js and npx in the test environment.
 * Run via `./scripts/test-integration-docker.sh` which provisions
 * all required runtimes.
 */
class KnexRuntimeTest : FunSpec({

    tags(IntegrationTag)

    beforeSpec {
        DatabaseDriverRegistry.register(SqliteDriver())
    }

    test("Knex applies generated migration against SQLite") {
        val tempDir = Files.createTempDirectory("knex-runtime")
        val projectDir = tempDir.resolve("knexproject")
        val migrationsDir = projectDir.resolve("migrations")

        // Generate export artifact
        val exitCode = RuntimeTestSupport.exportArtifacts(
            tool = MigrationTool.KNEX,
            dialect = "sqlite",
            version = "20260414120000",
            outputDir = migrationsDir,
            generateRollback = true,
        )
        exitCode shouldBe 0

        val dbPath = projectDir.resolve("test.sqlite3")

        // Create minimal Knex project
        projectDir.resolve("knexfile.js").writeText("""
            const path = require('path');
            module.exports = {
                client: 'better-sqlite3',
                connection: { filename: path.resolve(__dirname, 'test.sqlite3') },
                useNullAsDefault: true,
                migrations: { directory: './migrations' },
            };
        """.trimIndent())

        projectDir.resolve("package.json").writeText("""
            {
                "private": true,
                "dependencies": {
                    "knex": "^3.1.0",
                    "better-sqlite3": "^11.0.0"
                },
                "pnpm": {
                    "onlyBuiltDependencies": ["better-sqlite3"]
                }
            }
        """.trimIndent())

        // Install dependencies
        val install = ProcessBuilder("pnpm", "install")
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .start()
        val installOutput = install.inputStream.bufferedReader().readText()
        val installExit = install.waitFor()
        io.kotest.assertions.withClue("pnpm install failed:\n$installOutput") {
            installExit shouldBe 0
        }

        // Run migrate:latest
        val migrateUp = ProcessBuilder("pnpm", "exec", "knex", "migrate:latest")
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .start()
        val upOutput = migrateUp.inputStream.bufferedReader().readText()
        val upExit = migrateUp.waitFor()
        io.kotest.assertions.withClue("knex migrate:latest failed:\n$upOutput") {
            upExit shouldBe 0
        }

        // Verify schema effect via JDBC
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('users', 'orders') ORDER BY name"
                )
                val tables = mutableListOf<String>()
                while (rs.next()) tables += rs.getString("name")
                tables shouldBe listOf("orders", "users")
            }
        }

        // Run migrate:rollback
        val migrateDown = ProcessBuilder("pnpm", "exec", "knex", "migrate:rollback")
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .start()
        val downOutput = migrateDown.inputStream.bufferedReader().readText()
        val downExit = migrateDown.waitFor()
        if (downExit != 0) {
            println("knex migrate:rollback output: $downOutput")
        }
        downExit shouldBe 0

        // Verify tables are gone
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('users', 'orders')"
                )
                rs.next()
                rs.getInt(1) shouldBe 0
            }
        }

        tempDir.toFile().deleteRecursively()
    }
})
