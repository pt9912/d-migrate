package dev.dmigrate.cli.commands

import dev.dmigrate.server.application.job.JobExecutorConfig
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

/**
 * LF-012 / LN-011 / LN-017 / LN-027 host-/CLI-Aufloesung des `server.jobs.executor`-
 * Bereichs aus der MCP-Server-Config + Env-Overrides. Liefert die
 * adapter-neutrale [JobExecutorConfig] (Sync oder Async), die der
 * Bootstrap an [dev.dmigrate.server.application.job.JobExecutorFactory]
 * weiterreicht.
 *
 * Aufloesung pro Feld: `D_MIGRATE_SERVER_JOBS_EXECUTOR_*`-Env-Override
 * gewinnt vor `server.jobs.executor.*` aus dem effektiven YAML-Config.
 * Default ist [JobExecutorConfig.SYNC_DEFAULT] — kein Async-Setup,
 * Bestands-MVP-Verhalten unveraendert.
 */
internal data class McpJobExecutorResolution(val config: JobExecutorConfig) {
    val isAsync: Boolean get() = config is JobExecutorConfig.Async
}

internal class McpJobExecutorConfigError(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal class McpJobExecutorConfigResolver(
    private val configPath: Path?,
    private val envLookup: (String) -> String? = System::getenv,
) {

    fun resolve(): McpJobExecutorResolution {
        val yaml = configPath?.let(::loadYamlExecutor).orEmpty()
        val mode = resolveMode(yaml) ?: return McpJobExecutorResolution(JobExecutorConfig.SYNC_DEFAULT)

        if (mode == "sync") return McpJobExecutorResolution(JobExecutorConfig.SYNC_DEFAULT)

        if (mode != "async") {
            throw McpJobExecutorConfigError(
                "server.jobs.executor.mode must be 'sync' or 'async', got '$mode'",
            )
        }

        val async = JobExecutorConfig.Async.DEFAULT
        return McpJobExecutorResolution(
            JobExecutorConfig.Async(
                coreThreads = resolveInt(
                    "D_MIGRATE_SERVER_JOBS_EXECUTOR_CORE_THREADS",
                    yaml,
                    "async.coreThreads",
                    async.coreThreads,
                ),
                maxThreads = resolveInt(
                    "D_MIGRATE_SERVER_JOBS_EXECUTOR_MAX_THREADS",
                    yaml,
                    "async.maxThreads",
                    async.maxThreads,
                ),
                queueCapacity = resolveInt(
                    "D_MIGRATE_SERVER_JOBS_EXECUTOR_QUEUE_CAPACITY",
                    yaml,
                    "async.queueCapacity",
                    async.queueCapacity,
                ),
                keepAliveSeconds = resolveLong(
                    "D_MIGRATE_SERVER_JOBS_EXECUTOR_KEEP_ALIVE_SECONDS",
                    yaml,
                    "async.keepAliveSeconds",
                    async.keepAliveSeconds,
                ),
                retryAfter = Duration.ofMillis(
                    resolveLong(
                        "D_MIGRATE_SERVER_JOBS_EXECUTOR_RETRY_AFTER_MILLIS",
                        yaml,
                        "async.retryAfterMillis",
                        async.retryAfter.toMillis(),
                    ),
                ),
                shutdownTimeout = Duration.ofMillis(
                    resolveLong(
                        "D_MIGRATE_SERVER_JOBS_EXECUTOR_SHUTDOWN_TIMEOUT_MILLIS",
                        yaml,
                        "async.shutdownTimeoutMillis",
                        async.shutdownTimeout.toMillis(),
                    ),
                ),
                threadNamePrefix = resolveString(
                    "D_MIGRATE_SERVER_JOBS_EXECUTOR_THREAD_NAME_PREFIX",
                    yaml,
                    "async.threadNamePrefix",
                ) ?: async.threadNamePrefix,
            ),
        )
    }

    private fun resolveMode(yaml: Map<String, Any?>): String? {
        val raw = envLookup("D_MIGRATE_SERVER_JOBS_EXECUTOR_MODE")?.takeIf { it.isNotBlank() }
            ?: yaml["mode"] ?: return null
        return when (raw) {
            is String -> raw.trim().lowercase(Locale.ROOT)
            else -> throw McpJobExecutorConfigError(
                "server.jobs.executor.mode must be a string, got ${raw::class.simpleName}",
            )
        }
    }

    private fun loadYamlExecutor(path: Path): Map<String, Any?> {
        if (!Files.isRegularFile(path)) return emptyMap()
        val root = try {
            Files.newInputStream(path).use { input ->
                Load(LoadSettings.builder().build()).loadFromInputStream(input)
            }
        } catch (cause: Throwable) {
            throw McpJobExecutorConfigError(
                "failed to parse server-jobs config at $path: ${cause.message ?: cause::class.simpleName}",
                cause,
            )
        } as? Map<*, *> ?: throw McpJobExecutorConfigError(
            "server-jobs config at $path: top-level YAML must be a mapping",
        )
        val server = root["server"] as? Map<*, *> ?: return emptyMap()
        val jobs = server["jobs"] as? Map<*, *> ?: return emptyMap()
        val executor = jobs["executor"] as? Map<*, *> ?: return emptyMap()
        return flattenExecutor(executor, path)
    }

    private fun flattenExecutor(executor: Map<*, *>, path: Path): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((keyRaw, value) in executor) {
            val key = keyRaw as? String ?: throw McpJobExecutorConfigError(
                "server.jobs.executor in $path must use string keys",
            )
            when (key) {
                "async" -> {
                    val nested = value as? Map<*, *> ?: throw McpJobExecutorConfigError(
                        "server.jobs.executor.async in $path must be a mapping",
                    )
                    for ((nestedKeyRaw, nestedValue) in nested) {
                        val nestedKey = nestedKeyRaw as? String ?: throw McpJobExecutorConfigError(
                            "server.jobs.executor.async in $path must use string keys",
                        )
                        result["async.$nestedKey"] = nestedValue
                    }
                }
                else -> result[key] = value
            }
        }
        return result
    }

    private fun resolveString(env: String, yaml: Map<String, Any?>, key: String): String? {
        envLookup(env)?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = yaml[key] ?: return null
        val text = raw as? String ?: throw McpJobExecutorConfigError(
            "server.jobs.executor.$key must be a string, got ${raw::class.simpleName}",
        )
        return text
    }

    private fun resolveInt(env: String, yaml: Map<String, Any?>, key: String, default: Int): Int {
        val raw = envLookup(env)?.takeIf { it.isNotBlank() } ?: yaml[key] ?: return default
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        } ?: throw McpJobExecutorConfigError("server.jobs.executor.$key must be an integer")
    }

    private fun resolveLong(env: String, yaml: Map<String, Any?>, key: String, default: Long): Long {
        val raw = envLookup(env)?.takeIf { it.isNotBlank() } ?: yaml[key] ?: return default
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        } ?: throw McpJobExecutorConfigError("server.jobs.executor.$key must be an integer")
    }
}
