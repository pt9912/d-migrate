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
 * Phase E: Django runtime validation against SQLite.
 *
 * Generates a Django RunSQL migration via the production export path,
 * creates a minimal Django project, runs `python manage.py migrate`,
 * and verifies the expected schema effect.
 *
 * Requires Python 3 with Django installed in the test environment.
 * Run via `./scripts/test-integration-docker.sh` which provisions
 * all required runtimes.
 */
class DjangoRuntimeTest : FunSpec({

    tags(IntegrationTag)

    beforeSpec {
        DatabaseDriverRegistry.register(SqliteDriver())
    }

    test("Django applies generated RunSQL migration against SQLite") {
        val tempDir = Files.createTempDirectory("django-runtime")
        val projectDir = tempDir.resolve("myproject")
        val appDir = projectDir.resolve("myapp")
        val migrationsDir = appDir.resolve("migrations")

        // Generate export artifact
        val exitCode = RuntimeTestSupport.exportArtifacts(
            tool = MigrationTool.DJANGO,
            dialect = "sqlite",
            version = "0001",
            outputDir = migrationsDir,
            generateRollback = true,
        )
        exitCode shouldBe 0

        // Create minimal Django project
        migrationsDir.resolve("__init__.py").writeText("")
        appDir.resolve("__init__.py").writeText("")

        val dbPath = projectDir.resolve("db.sqlite3")
        projectDir.resolve("settings.py").writeText("""
            DATABASES = {
                'default': {
                    'ENGINE': 'django.db.backends.sqlite3',
                    'NAME': '${dbPath.toAbsolutePath().toString().replace("\\", "\\\\")}',
                }
            }
            INSTALLED_APPS = ['myapp']
            DEFAULT_AUTO_FIELD = 'django.db.models.BigAutoField'
        """.trimIndent())

        projectDir.resolve("manage.py").writeText("""
            import os, sys
            os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'settings')
            sys.path.insert(0, '${projectDir.toAbsolutePath().toString().replace("\\", "\\\\")}')
            from django.core.management import execute_from_command_line
            execute_from_command_line(sys.argv)
        """.trimIndent())

        // Run migrate
        val migrate = ProcessBuilder("python3", "manage.py", "migrate", "--run-syncdb")
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .start()
        val migrateOutput = migrate.inputStream.bufferedReader().readText()
        val migrateExit = migrate.waitFor()

        if (migrateExit != 0) {
            println("Django migrate output: $migrateOutput")
        }
        migrateExit shouldBe 0

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

        // Run reverse migration
        val reverse = ProcessBuilder("python3", "manage.py", "migrate", "myapp", "zero")
            .directory(projectDir.toFile())
            .redirectErrorStream(true)
            .start()
        val reverseOutput = reverse.inputStream.bufferedReader().readText()
        val reverseExit = reverse.waitFor()

        if (reverseExit != 0) {
            println("Django reverse output: $reverseOutput")
        }
        reverseExit shouldBe 0

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
