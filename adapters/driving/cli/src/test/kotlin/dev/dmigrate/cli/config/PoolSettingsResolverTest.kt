package dev.dmigrate.cli.config

import dev.dmigrate.driver.connection.PoolSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class PoolSettingsResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-pool-test-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    fun resolverFor(file: Path) = PoolSettingsResolver(configPathFromCli = file)

    test("default config path does not exist — returns PoolSettings defaults") {
        val settings = PoolSettingsResolver(
            defaultConfigPath = Path.of("/tmp/does-not-exist-${System.nanoTime()}.yaml"),
            envLookup = { null },
        ).resolve()
        settings shouldBe PoolSettings()
    }

    test("no database section — returns defaults") {
        val file = tempConfig("pipeline:\n  chunk_size: 100\n")
        resolverFor(file).resolve() shouldBe PoolSettings()
    }

    test("database without pool section — returns defaults") {
        val file = tempConfig(
            """
            database:
              default_source: local_pg
            """.trimIndent()
        )
        resolverFor(file).resolve() shouldBe PoolSettings()
    }

    test("all five documented keys — parsed into PoolSettings, safety timeouts untouched") {
        val file = tempConfig(
            """
            database:
              pool:
                max_size: 20
                min_idle: 5
                connection_timeout_ms: 15000
                idle_timeout_ms: 120000
                max_lifetime_ms: 900000
            """.trimIndent()
        )
        resolverFor(file).resolve() shouldBe PoolSettings(
            maximumPoolSize = 20,
            minimumIdle = 5,
            connectionTimeoutMs = 15_000,
            idleTimeoutMs = 120_000,
            maxLifetimeMs = 900_000,
            // keepalive/statement/network timeouts keep their defaults (D1: not user-tunable here)
        )
    }

    test("partial pool section — unset keys keep defaults") {
        val file = tempConfig(
            """
            database:
              pool:
                max_size: 4
            """.trimIndent()
        )
        val defaults = PoolSettings()
        resolverFor(file).resolve() shouldBe defaults.copy(maximumPoolSize = 4)
    }

    test("keepalive/statement/network keys are NOT read from this section") {
        // These YAML keys are not part of the documented schema; the resolver ignores
        // unknown keys and never touches the safety-critical timeout fields.
        val file = tempConfig(
            """
            database:
              pool:
                keepalive_ms: 1
                statement_timeout_ms: 1
                network_timeout_ms: 1
            """.trimIndent()
        )
        resolverFor(file).resolve() shouldBe PoolSettings()
    }

    test("float max_size — rejected (no silent coercion)") {
        val file = tempConfig("database:\n  pool:\n    max_size: 1.5\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message shouldContain "database.pool.max_size"
    }

    test("string min_idle — rejected") {
        val file = tempConfig("database:\n  pool:\n    min_idle: \"two\"\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message shouldContain "database.pool.min_idle"
    }

    test("zero max_size — rejected (must be > 0)") {
        val file = tempConfig("database:\n  pool:\n    max_size: 0\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message shouldContain "> 0"
    }

    test("negative connection_timeout_ms — rejected") {
        val file = tempConfig("database:\n  pool:\n    connection_timeout_ms: -1\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message shouldContain "database.pool.connection_timeout_ms"
    }

    test("min_idle greater than max_size — rejected (D3 cross-field, no silent coercion)") {
        val file = tempConfig(
            """
            database:
              pool:
                max_size: 4
                min_idle: 8
            """.trimIndent()
        )
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message shouldContain "min_idle"
        ex.message shouldContain "max_size"
    }

    test("min_idle exceeds default max_size when max_size unset — rejected") {
        // max_size defaults to 10; min_idle 15 > 10 must fail loudly, not coerce.
        val file = tempConfig("database:\n  pool:\n    min_idle: 15\n")
        shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
    }
})
