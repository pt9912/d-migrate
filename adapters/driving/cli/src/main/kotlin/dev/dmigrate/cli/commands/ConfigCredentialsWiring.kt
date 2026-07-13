package dev.dmigrate.cli.commands

import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.cliAuditRecorder
import dev.dmigrate.connection.AesGcmCredentialStore
import dev.dmigrate.driver.connection.CredentialStorePort
import java.nio.file.Path
import java.nio.file.Paths

internal data class CredentialSetOptions(
    val name: String,
    val user: String,
    val configPath: Path?,
    val baseDir: Path = defaultCredentialBaseDir(),
)

internal data class CredentialListOptions(
    val configPath: Path?,
    val baseDir: Path = defaultCredentialBaseDir(),
)

/**
 * Wiring für `config credentials set`/`list` (LN-025 Slice 1). Baut den [CredentialStorePort] mit einem
 * lazily beschafften Master-Secret, führt den [CredentialCommandRunner] aus und auditiert **nur** `set`
 * (zustandsändernd; `list` ist read-only). Fail-closed (Exit 7), wenn kein Master-Secret verfügbar ist.
 */
internal object ConfigCredentialsWiring {

    fun executeSet(
        options: CredentialSetOptions,
        masterSecretResolver: MasterSecretResolver,
        dbPasswordProvider: () -> CharArray,
        stdout: (String) -> Unit = ::println,
        stderr: (String) -> Unit = { System.err.println(it) },
        recorder: CliAuditRecorder = cliAuditRecorder(options.configPath),
        storeFactory: (Path, () -> CharArray) -> CredentialStorePort = ::defaultStore,
    ): Int = recorder.record("config.credentials.set", listOf(options.name)) {
        var secret: CharArray? = null
        val store = storeFactory(options.baseDir) { secret?.copyOf() ?: CharArray(0) }
        secret = masterSecretResolver.resolve(isNewStore = !store.isInitialized())
            ?: return@record failClosed(stderr)
        try {
            CredentialCommandRunner(store, stdout, stderr).set(options.name, options.user, dbPasswordProvider())
        } finally {
            secret.fill(' ')
        }
    }

    fun executeList(
        options: CredentialListOptions,
        masterSecretResolver: MasterSecretResolver,
        stdout: (String) -> Unit = ::println,
        stderr: (String) -> Unit = { System.err.println(it) },
        storeFactory: (Path, () -> CharArray) -> CredentialStorePort = ::defaultStore,
    ): Int {
        var secret: CharArray? = null
        val store = storeFactory(options.baseDir) { secret?.copyOf() ?: CharArray(0) }
        val runner = CredentialCommandRunner(store, stdout, stderr)
        // Leerer/fehlender Store → leere Liste ohne Master-Secret (Akzeptanz: Exit 0).
        if (!store.isInitialized()) return runner.list()
        secret = masterSecretResolver.resolve(isNewStore = false) ?: return failClosed(stderr)
        return try {
            runner.list()
        } finally {
            secret.fill(' ')
        }
    }

    private fun failClosed(stderr: (String) -> Unit): Int {
        stderr(
            "Error: no master secret available — set ${MasterSecretResolver.ENV_VAR} " +
                "or run in an interactive terminal.",
        )
        return 7
    }

    private fun defaultStore(baseDir: Path, secretProvider: () -> CharArray): CredentialStorePort =
        AesGcmCredentialStore(baseDir = baseDir, masterSecretProvider = secretProvider)
}

internal fun defaultCredentialBaseDir(): Path =
    Paths.get(System.getProperty("user.home"), AesGcmCredentialStore.DEFAULT_DIR_NAME)
