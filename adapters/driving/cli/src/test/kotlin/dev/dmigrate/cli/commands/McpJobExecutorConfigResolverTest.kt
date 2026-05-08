package dev.dmigrate.cli.commands

import dev.dmigrate.server.application.job.JobExecutorConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class McpJobExecutorConfigResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-job-executor-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    test("returns Sync default when neither config nor env set the mode") {
        val cfg = tempConfig("connections: {}\n")
        val outcome = McpJobExecutorConfigResolver(cfg, envLookup = { null }).resolve()
        outcome.config shouldBe JobExecutorConfig.SYNC_DEFAULT
        outcome.isAsync shouldBe false
    }

    test("returns Sync explicitly when mode = sync (env or YAML)") {
        val cfg = tempConfig(
            """
            server:
              jobs:
                executor:
                  mode: sync
            """.trimIndent(),
        )
        val outcome = McpJobExecutorConfigResolver(cfg, envLookup = { null }).resolve()
        outcome.config shouldBe JobExecutorConfig.SYNC_DEFAULT
    }

    test("reads Async block from YAML with custom values") {
        val cfg = tempConfig(
            """
            server:
              jobs:
                executor:
                  mode: async
                  async:
                    coreThreads: 8
                    maxThreads: 16
                    queueCapacity: 64
                    keepAliveSeconds: 30
                    retryAfterMillis: 250
                    shutdownTimeoutMillis: 5000
                    threadNamePrefix: ops-worker
            """.trimIndent(),
        )

        val outcome = McpJobExecutorConfigResolver(cfg, envLookup = { null }).resolve()
        val async = outcome.config.shouldBeInstanceOf<JobExecutorConfig.Async>()
        async.coreThreads shouldBe 8
        async.maxThreads shouldBe 16
        async.queueCapacity shouldBe 64
        async.keepAliveSeconds shouldBe 30L
        async.retryAfter shouldBe Duration.ofMillis(250)
        async.shutdownTimeout shouldBe Duration.ofMillis(5000)
        async.threadNamePrefix shouldBe "ops-worker"
    }

    test("env overrides YAML per field; missing values fall back to Async-Defaults") {
        val cfg = tempConfig(
            """
            server:
              jobs:
                executor:
                  mode: sync
                  async:
                    coreThreads: 2
                    maxThreads: 16
                    queueCapacity: 1024
            """.trimIndent(),
        )
        val env = mapOf(
            "D_MIGRATE_SERVER_JOBS_EXECUTOR_MODE" to "async",
            "D_MIGRATE_SERVER_JOBS_EXECUTOR_CORE_THREADS" to "8",
            "D_MIGRATE_SERVER_JOBS_EXECUTOR_RETRY_AFTER_MILLIS" to "750",
        )

        val outcome = McpJobExecutorConfigResolver(cfg, envLookup = { env[it] }).resolve()
        val async = outcome.config.shouldBeInstanceOf<JobExecutorConfig.Async>()
        // Env-override greift fuer coreThreads, mode, retryAfter; YAML-Werte
        // fuer maxThreads/queueCapacity bleiben; alle anderen Felder fallen
        // auf den Async-Default zurueck.
        async.coreThreads shouldBe 8
        async.maxThreads shouldBe 16
        async.queueCapacity shouldBe 1024
        async.retryAfter shouldBe Duration.ofMillis(750)
        async.shutdownTimeout shouldBe JobExecutorConfig.Async.DEFAULT_SHUTDOWN_TIMEOUT
    }

    test("rejects unknown mode") {
        val cfg = tempConfig(
            """
            server:
              jobs:
                executor:
                  mode: chaos
            """.trimIndent(),
        )
        val ex = shouldThrow<McpJobExecutorConfigError> {
            McpJobExecutorConfigResolver(cfg, envLookup = { null }).resolve()
        }
        ex.message!! shouldContainIgnoringCase "must be 'sync' or 'async'"
    }

    test("rejects non-numeric env override") {
        val ex = shouldThrow<McpJobExecutorConfigError> {
            McpJobExecutorConfigResolver(
                configPath = null,
                envLookup = { name ->
                    when (name) {
                        "D_MIGRATE_SERVER_JOBS_EXECUTOR_MODE" -> "async"
                        "D_MIGRATE_SERVER_JOBS_EXECUTOR_CORE_THREADS" -> "abc"
                        else -> null
                    }
                },
            ).resolve()
        }
        ex.message!! shouldContainIgnoringCase "must be an integer"
    }

    test("returns Sync default when configPath is null and env empty") {
        val outcome = McpJobExecutorConfigResolver(configPath = null, envLookup = { null }).resolve()
        outcome.config shouldBe JobExecutorConfig.SYNC_DEFAULT
    }
})

private infix fun String.shouldContainIgnoringCase(needle: String) {
    if (!this.lowercase().contains(needle.lowercase())) {
        error("expected '$this' to contain (case-insensitive) '$needle'")
    }
}
