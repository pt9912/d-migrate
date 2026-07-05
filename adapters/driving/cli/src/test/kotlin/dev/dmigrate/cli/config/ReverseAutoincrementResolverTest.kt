package dev.dmigrate.cli.config

import dev.dmigrate.driver.SqliteAutoincrementReverse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * AP3 of the reverse-preferences slice: CLI flag > config
 * `reverse.sqlite.autoincrement_width` > conservative default (32-bit).
 */
class ReverseAutoincrementResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-reverse-pref-test-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    val noConfig = Path.of("/tmp/dmigrate-no-such-${System.nanoTime()}.yaml")
    fun resolver(configPathFromCli: Path? = null, defaultConfigPath: Path = noConfig) =
        ReverseAutoincrementResolver(
            configPathFromCli = configPathFromCli,
            envLookup = { null },
            defaultConfigPath = defaultConfigPath,
        )

    test("flag 64 → BIGINTEGER_IDENTITY") {
        resolver().resolve(64) shouldBe SqliteAutoincrementReverse.BIGINTEGER_IDENTITY
    }

    test("flag 32 → IDENTIFIER") {
        resolver().resolve(32) shouldBe SqliteAutoincrementReverse.IDENTIFIER
    }

    test("no flag, no config → conservative default IDENTIFIER") {
        resolver().resolve(null) shouldBe SqliteAutoincrementReverse.IDENTIFIER
    }

    test("no flag, config width 64 → BIGINTEGER_IDENTITY") {
        val cfg = tempConfig("reverse:\n  sqlite:\n    autoincrement_width: 64\n")
        resolver(configPathFromCli = cfg).resolve(null) shouldBe SqliteAutoincrementReverse.BIGINTEGER_IDENTITY
    }

    test("flag overrides config (flag 32 beats config 64)") {
        val cfg = tempConfig("reverse:\n  sqlite:\n    autoincrement_width: 64\n")
        resolver(configPathFromCli = cfg).resolve(32) shouldBe SqliteAutoincrementReverse.IDENTIFIER
    }

    test("config present but no reverse block → default") {
        val cfg = tempConfig("database:\n  default: pg\n")
        resolver(configPathFromCli = cfg).resolve(null) shouldBe SqliteAutoincrementReverse.IDENTIFIER
    }

    test("unrecognised width in config → conservative default") {
        val cfg = tempConfig("reverse:\n  sqlite:\n    autoincrement_width: 16\n")
        resolver(configPathFromCli = cfg).resolve(null) shouldBe SqliteAutoincrementReverse.IDENTIFIER
    }
})
