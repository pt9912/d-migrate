package dev.dmigrate.connection

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Default-[KeychainBackend] (ADR 0040): **native-frei** per Shell-out auf das OS-CLI-Tool.
 *
 * - macOS: `security find-generic-password -s <service> [-a <account>] -w` (exit 44 = nicht gefunden).
 * - Linux: `secret-tool lookup service <service> [account <account>]` (exit 1 = nicht gefunden).
 *
 * Das **Secret kommt über stdout** — nie in den Prozess-Args (kein `ps`-Leak; Service/Account in den
 * Args sind nicht geheim). Ausführung mit **Timeout** (kein Hängen an einem interaktiven Unlock-Prompt
 * im nicht-interaktiven Lauf); stderr wird verworfen, stdin geschlossen. Jeder Fehler → fail-closed
 * [KeychainLookup.Unavailable]. Weder Wert noch Args werden geloggt.
 *
 * [osName]/[commandExists]/[runCommand] sind für deterministische Tests injizierbar (kein echtes
 * OS-Keychain im CI). Windows ist hier **nicht** abgedeckt (`isAvailable()=false`) — das ist der
 * opt-in-`keychain-native`-Folge-Slice (ADR 0040).
 */
class ShelloutKeychainBackend(
    osName: String = System.getProperty("os.name").orEmpty(),
    private val commandExists: (String) -> Boolean = ::isOnPath,
    private val runCommand: (List<String>) -> CommandOutcome = { runProcess(it, TIMEOUT_SECONDS) },
) : KeychainBackend {

    private enum class Os { MACOS, LINUX, OTHER }

    private val os: Os = when {
        osName.startsWith("Mac", ignoreCase = true) || osName.contains("OS X", ignoreCase = true) -> Os.MACOS
        osName.startsWith("Linux", ignoreCase = true) -> Os.LINUX
        else -> Os.OTHER
    }

    private val tool: String? = when (os) {
        Os.MACOS -> "security"
        Os.LINUX -> "secret-tool"
        Os.OTHER -> null
    }

    override fun isAvailable(): Boolean = tool != null && commandExists(tool)

    override fun lookup(service: String, account: String?): KeychainLookup {
        val cmd = commandFor(service, account)
            ?: return KeychainLookup.Unavailable("no keychain shell-out backend for this OS")
        return when (val outcome = runCommand(cmd)) {
            is CommandOutcome.SpawnFailed -> KeychainLookup.Unavailable(outcome.detail)
            is CommandOutcome.Completed -> interpret(outcome)
        }
    }

    private fun commandFor(service: String, account: String?): List<String>? {
        val t = tool ?: return null
        return when (os) {
            Os.MACOS -> buildList {
                add(t); add("find-generic-password"); add("-s"); add(service)
                if (account != null) { add("-a"); add(account) }
                add("-w")
            }
            Os.LINUX -> buildList {
                add(t); add("lookup"); add("service"); add(service)
                if (account != null) { add("account"); add(account) }
            }
            Os.OTHER -> null
        }
    }

    private fun interpret(outcome: CommandOutcome.Completed): KeychainLookup = when {
        outcome.exitCode == 0 -> {
            // BOM/Trim macht der Provider; hier reicht ein Leer-Check auf den Roh-stdout.
            if (outcome.stdout.isBlank()) KeychainLookup.NotFound else KeychainLookup.Found(outcome.stdout)
        }
        os == Os.MACOS && outcome.exitCode == MACOS_NOT_FOUND_EXIT -> KeychainLookup.NotFound
        os == Os.LINUX && outcome.exitCode == LINUX_NOT_FOUND_EXIT -> KeychainLookup.NotFound
        else -> KeychainLookup.Unavailable("keychain tool exited with ${outcome.exitCode}")
    }

    /** Ausgang eines Kommando-Laufs — [Completed] mit Exit/stdout, oder [SpawnFailed] (Tool fehlt/Timeout/I/O). */
    sealed interface CommandOutcome {
        data class Completed(val exitCode: Int, val stdout: String) : CommandOutcome
        data class SpawnFailed(val detail: String) : CommandOutcome
    }

    companion object {
        const val TIMEOUT_SECONDS: Long = 10
        private const val MACOS_NOT_FOUND_EXIT: Int = 44
        private const val LINUX_NOT_FOUND_EXIT: Int = 1

        /** Sucht [command] in den `PATH`-Verzeichnissen (kein Prozess-Spawn). */
        private fun isOnPath(command: String): Boolean {
            val path = System.getenv("PATH") ?: return false
            return path.split(File.pathSeparatorChar).any { dir ->
                dir.isNotBlank() && File(dir, command).let { it.isFile && it.canExecute() }
            }
        }

        /**
         * Führt [cmd] aus: stdin geschlossen, stderr verworfen (kein Blockieren, kein Secret-Leak
         * über stderr), stdout gelesen. Timeout → `destroyForcibly` + [CommandOutcome.SpawnFailed].
         * Das Secret liegt in stdout, nicht in den Args.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun runProcess(cmd: List<String>, timeoutSeconds: Long): CommandOutcome {
            val process = try {
                ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (e: IOException) {
                return CommandOutcome.SpawnFailed("keychain tool not launchable: ${e.message ?: "I/O error"}")
            }
            return try {
                process.outputStream.close()
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return CommandOutcome.SpawnFailed("keychain lookup timed out after ${timeoutSeconds}s")
                }
                val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
                CommandOutcome.Completed(process.exitValue(), stdout)
            } catch (e: Exception) {
                process.destroyForcibly()
                CommandOutcome.SpawnFailed("keychain lookup failed: ${e.message ?: e::class.simpleName}")
            }
        }
    }
}
