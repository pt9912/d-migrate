package dev.dmigrate.cli.commands

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class McpServerStateConfigResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-server-state-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    test("returns null when config and env do not enable server-state persistence") {
        McpServerStateConfigResolver(
            configPath = tempConfig("connections: {}\n"),
            envLookup = { null },
        ).resolve() shouldBe null
    }

    test("reads server-state config from YAML including nested hikari and migrations blocks") {
        val cfg = tempConfig(
            """
            server:
              state:
                jdbcUrl: jdbc:postgresql://localhost:5432/dmigrate_state
                username: dmigrate
                password: ${'$'}{SERVER_STATE_PASSWORD}
                hikari:
                  maximumPoolSize: 4
                  connectionTimeoutMs: 2500
                migrations:
                  auto: true
            """.trimIndent(),
        )

        val state = McpServerStateConfigResolver(
            configPath = cfg,
            envLookup = { name -> if (name == "SERVER_STATE_PASSWORD") "secret" else null },
        ).resolve()

        state shouldBe McpServerStateConfig(
            jdbcUrl = "jdbc:postgresql://localhost:5432/dmigrate_state",
            username = "dmigrate",
            password = "secret",
            maximumPoolSize = 4,
            connectionTimeoutMs = 2500,
            migrationsAuto = true,
        )
    }

    test("env overrides YAML per field and keeps defaults for absent optional values") {
        val cfg = tempConfig(
            """
            server:
              state:
                jdbcUrl: jdbc:postgresql://yaml/db
                hikari:
                  maximumPoolSize: 2
            """.trimIndent(),
        )

        val state = McpServerStateConfigResolver(
            configPath = cfg,
            envLookup = { name ->
                when (name) {
                    "D_MIGRATE_SERVER_STATE_JDBC_URL" -> "jdbc:postgresql://env/db"
                    "D_MIGRATE_SERVER_STATE_HIKARI_MAXIMUM_POOL_SIZE" -> "8"
                    "D_MIGRATE_SERVER_STATE_MIGRATIONS_AUTO" -> "yes"
                    else -> null
                }
            },
        ).resolve()

        state shouldBe McpServerStateConfig(
            jdbcUrl = "jdbc:postgresql://env/db",
            username = null,
            password = null,
            maximumPoolSize = 8,
            connectionTimeoutMs = McpServerStateConfig.DEFAULT_CONNECTION_TIMEOUT_MS,
            migrationsAuto = true,
        )
    }

    test("requires jdbcUrl when any server-state env value is present") {
        val failure = shouldThrow<McpServerStateConfigError> {
            McpServerStateConfigResolver(
                configPath = null,
                envLookup = { name ->
                    if (name == "D_MIGRATE_SERVER_STATE_USERNAME") "dmigrate" else null
                },
            ).resolve()
        }

        failure.message shouldContain "server.state.jdbcUrl is required"
    }
})
