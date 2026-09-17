package dev.dmigrate.cli.config

import dev.dmigrate.driver.AutoIncrementSyntaxReverse
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ReversePreferences
import dev.dmigrate.driver.SqliteAutoincrementReverse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Die Praeferenz `serial`/`identity` fuer Auto-Increment-Spalten
 * (`spec/dialect-preference-mechanism.md`): je Dialekt CLI-Flag >
 * `reverse.<dialekt>.autoincrement_syntax` > Default.
 */
class ReverseAutoIncrementSyntaxResolverTest : FunSpec({

    fun tempConfig(content: String): Path =
        Files.createTempFile("dmigrate-reverse-syntax-test-", ".yaml").also { Files.writeString(it, content) }

    val noConfig = Path.of("/tmp/dmigrate-no-such-${System.nanoTime()}.yaml")

    fun resolver(configPathFromCli: Path? = null, env: Map<String, String> = emptyMap()) =
        ReverseAutoIncrementSyntaxResolver(
            configPathFromCli = configPathFromCli,
            envLookup = { env[it] },
            defaultConfigPath = noConfig,
        )

    val both = """
        reverse:
          mysql:
            autoincrement_syntax: identity
          sqlite:
            autoincrement_syntax: serial
    """.trimIndent()

    test("no flag, no config: nothing declared, every dialect reads the default") {
        resolver().resolve().shouldBeEmpty()
        ReversePreferences(autoIncrementSyntax = resolver().resolve())
            .autoIncrementSyntaxFor(DatabaseDialect.MYSQL) shouldBe AutoIncrementSyntaxReverse.SERIAL
    }

    test("the config declares per dialect") {
        resolver(tempConfig(both)).resolve() shouldBe mapOf(
            DatabaseDialect.MYSQL to AutoIncrementSyntaxReverse.IDENTITY,
            DatabaseDialect.SQLITE to AutoIncrementSyntaxReverse.SERIAL,
        )
    }

    test("a flag beats the config of its own dialect only") {
        resolver(tempConfig(both)).resolve(
            mapOf(DatabaseDialect.MYSQL to "serial", DatabaseDialect.SQLITE to null),
        ) shouldBe mapOf(
            DatabaseDialect.MYSQL to AutoIncrementSyntaxReverse.SERIAL,
            DatabaseDialect.SQLITE to AutoIncrementSyntaxReverse.SERIAL,
        )
        resolver(tempConfig(both)).resolve(mapOf(DatabaseDialect.SQLITE to "identity")) shouldBe mapOf(
            DatabaseDialect.MYSQL to AutoIncrementSyntaxReverse.IDENTITY,
            DatabaseDialect.SQLITE to AutoIncrementSyntaxReverse.IDENTITY,
        )
    }

    test("the flag short-circuits a missing explicit config") {
        resolver(Path.of("/tmp/dmigrate-missing-${System.nanoTime()}.yaml"))
            .resolve(mapOf(DatabaseDialect.MYSQL to "identity")) shouldBe
            mapOf(DatabaseDialect.MYSQL to AutoIncrementSyntaxReverse.IDENTITY)
    }

    test("only MySQL and SQLite have the key; other dialects are never declared") {
        val cfg = tempConfig("reverse:\n  postgresql:\n    autoincrement_syntax: identity\n")
        resolver(cfg).resolve(mapOf(DatabaseDialect.MSSQL to "identity")).shouldBeEmpty()
    }

    test("lenient like the width key: an unknown value, a missing block or a broken file declare nothing") {
        resolver(tempConfig("reverse:\n  mysql:\n    autoincrement_syntax: identiy\n")).resolve().shouldBeEmpty()
        resolver(tempConfig("database:\n  default: pg\n")).resolve().shouldBeEmpty()
        resolver(tempConfig("reverse: [unclosed\n")).resolve().shouldBeEmpty()
        resolver(Path.of("/tmp/dmigrate-missing-${System.nanoTime()}.yaml")).resolve().shouldBeEmpty()
    }

    test("values are read case-insensitively and trimmed") {
        resolver(tempConfig("reverse:\n  mysql:\n    autoincrement_syntax: ' IDENTITY '\n")).resolve() shouldBe
            mapOf(DatabaseDialect.MYSQL to AutoIncrementSyntaxReverse.IDENTITY)
    }

    test("the config path follows D_MIGRATE_CONFIG when no --config is given") {
        val cfg = tempConfig(both)
        resolver(env = mapOf("D_MIGRATE_CONFIG" to cfg.toString())).resolve()[DatabaseDialect.MYSQL] shouldBe
            AutoIncrementSyntaxReverse.IDENTITY
    }

    context("ReversePreferencesResolver — alle Praeferenzen eines Laufs") {

        test("the config alone yields width and syntax, as compare and mcp serve see them") {
            val cfg = tempConfig(
                "reverse:\n  sqlite:\n    autoincrement_width: 64\n  mysql:\n    autoincrement_syntax: identity\n",
            )
            ReversePreferencesResolver(cfg, { null }, noConfig).resolve() shouldBe ReversePreferences(
                sqliteAutoincrement = SqliteAutoincrementReverse.BIGINTEGER_IDENTITY,
                autoIncrementSyntax = mapOf(DatabaseDialect.MYSQL to AutoIncrementSyntaxReverse.IDENTITY),
            )
        }

        test("without a config the preferences are the defaults") {
            ReversePreferencesResolver(null, { null }, noConfig).resolve() shouldBe ReversePreferences()
        }

        test("flags reach both parts") {
            ReversePreferencesResolver(null, { null }, noConfig).resolve(
                sqliteWidthFlag = 64,
                syntaxFlags = mapOf(DatabaseDialect.SQLITE to "identity"),
            ) shouldBe ReversePreferences(
                sqliteAutoincrement = SqliteAutoincrementReverse.BIGINTEGER_IDENTITY,
                autoIncrementSyntax = mapOf(DatabaseDialect.SQLITE to AutoIncrementSyntaxReverse.IDENTITY),
            )
        }
    }
})
