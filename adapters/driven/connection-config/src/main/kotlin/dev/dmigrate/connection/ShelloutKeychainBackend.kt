package dev.dmigrate.connection

import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Default-[KeychainBackend] (ADR 0040): **native-frei** per Shell-out auf das OS-Werkzeug.
 *
 * - macOS: `security find-generic-password -s <service> [-a <account>] -w` (exit 44 = nicht gefunden).
 * - Linux: `secret-tool lookup service <service> [account <account>]` (exit 1 = nicht gefunden).
 * - Windows: `powershell.exe -EncodedCommand <konstantes Skript>`, das die Win32-`CredReadW`-API
 *   aufruft (der Credential-Manager kennt **kein** CLI, das ein Secret ausgibt — `cmdkey /list` zeigt
 *   Passwörter bewusst nicht). Weiterhin native-frei: **kein JNA**, keine neue Dependency. Der
 *   Credential-Manager-`TargetName` ist `<service>` bzw. `<service>/<account>` (mit Account); er wird
 *   dem Skript über die **Environment-Variable** `DM_KEYCHAIN_TARGET` übergeben — also **nicht** in den
 *   Skript-Text interpoliert (keine PowerShell-Injection) und **nicht** in die Prozess-Args (`ps`).
 *   exit 2 = nicht gefunden, exit 0 + stdout = gefunden, sonst Unavailable.
 *
 * Das **Secret kommt über stdout** — nie in den Prozess-Args (kein `ps`-Leak; Service/Account in den
 * Args sind nicht geheim). Ausführung mit **Timeout** (kein Hängen an einem interaktiven Unlock-Prompt
 * im nicht-interaktiven Lauf); stderr wird verworfen, stdin geschlossen. Jeder Fehler → fail-closed
 * [KeychainLookup.Unavailable]. Weder Wert noch Args werden geloggt.
 *
 * [osName]/[commandExists]/[runCommand] sind für deterministische Tests injizierbar (kein echtes
 * OS-Keychain im CI). Der echte Windows-Round-Trip (Blob-Kodierung, `CredReadW`-Verhalten) ist hier
 * **nicht** CI-verifizierbar — wie bei macOS/Linux deckt der Unit-Test nur Kommando-Konstruktion und
 * Ergebnis-Mapping ab; der Round-Trip wird manuell verifiziert.
 */
class ShelloutKeychainBackend(
    osName: String = System.getProperty("os.name").orEmpty(),
    private val commandExists: (String) -> Boolean = ::isOnPath,
    private val runCommand: (List<String>, Map<String, String>) -> CommandOutcome =
        { cmd, env -> runProcess(cmd, env, TIMEOUT_SECONDS) },
) : KeychainBackend {

    private enum class Os { MACOS, LINUX, WINDOWS, OTHER }

    private val os: Os = when {
        osName.startsWith("Mac", ignoreCase = true) || osName.contains("OS X", ignoreCase = true) -> Os.MACOS
        osName.startsWith("Linux", ignoreCase = true) -> Os.LINUX
        osName.startsWith("Windows", ignoreCase = true) -> Os.WINDOWS
        else -> Os.OTHER
    }

    private val tool: String? = when (os) {
        Os.MACOS -> "security"
        Os.LINUX -> "secret-tool"
        Os.WINDOWS -> "powershell.exe"
        Os.OTHER -> null
    }

    override fun isAvailable(): Boolean = tool != null && commandExists(tool)

    override fun lookup(service: String, account: String?): KeychainLookup {
        val cmd = commandFor(service, account)
            ?: return KeychainLookup.Unavailable("no keychain shell-out backend for this OS")
        return when (val outcome = runCommand(cmd, envFor(service, account))) {
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
            // Ziel-Name geht über die Env-Var (siehe envFor) — nicht in Args/Skript. Skript ist konstant.
            Os.WINDOWS -> listOf(t, "-NoProfile", "-NonInteractive", "-EncodedCommand", POWERSHELL_ENCODED)
            Os.OTHER -> null
        }
    }

    /** Nur Windows braucht einen Env-Kanal (der `CredReadW`-TargetName); sonst leer. */
    private fun envFor(service: String, account: String?): Map<String, String> =
        if (os == Os.WINDOWS) mapOf(ENV_TARGET to windowsTarget(service, account)) else emptyMap()

    private fun windowsTarget(service: String, account: String?): String =
        if (account != null) "$service/$account" else service

    private fun interpret(outcome: CommandOutcome.Completed): KeychainLookup = when {
        outcome.exitCode == 0 -> {
            // BOM/Trim macht der Provider; hier reicht ein Leer-Check auf den Roh-stdout.
            if (outcome.stdout.isBlank()) KeychainLookup.NotFound else KeychainLookup.Found(outcome.stdout)
        }
        os == Os.MACOS && outcome.exitCode == MACOS_NOT_FOUND_EXIT -> KeychainLookup.NotFound
        os == Os.LINUX && outcome.exitCode == LINUX_NOT_FOUND_EXIT -> KeychainLookup.NotFound
        os == Os.WINDOWS && outcome.exitCode == WINDOWS_NOT_FOUND_EXIT -> KeychainLookup.NotFound
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
        private const val WINDOWS_NOT_FOUND_EXIT: Int = 2

        /** Name der Environment-Variable, über die der Windows-`CredReadW`-TargetName reingegeben wird. */
        const val ENV_TARGET: String = "DM_KEYCHAIN_TARGET"

        /**
         * Konstantes PowerShell-Skript für den Windows-Lookup: liest den TargetName aus
         * `$env:DM_KEYCHAIN_TARGET` (nie interpoliert → keine Injection), ruft `CredReadW` (Typ 1 =
         * GENERIC), schreibt den Credential-Blob (Unicode) nach stdout. exit 0 = gefunden, 2 = nicht
         * gefunden, 3 = Fehler. `\$` ist ein literales PowerShell-`$` (Kotlin-escaped), `\"` ein `"`.
         */
        private val POWERSHELL_SCRIPT: String = listOf(
            "\$ErrorActionPreference='Stop'",
            "try {",
            "  Add-Type -Namespace DmKc -Name Native -MemberDefinition @'",
            "[DllImport(\"advapi32.dll\", CharSet=CharSet.Unicode, SetLastError=true)]",
            "public static extern bool CredReadW(string target, int type, int flags, out IntPtr cred);",
            "[DllImport(\"advapi32.dll\")] public static extern void CredFree(IntPtr cred);",
            "[StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct CREDENTIAL {",
            "  public uint Flags; public uint Type; public IntPtr TargetName; public IntPtr Comment;",
            "  public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;",
            "  public uint CredentialBlobSize; public IntPtr CredentialBlob; public uint Persist;",
            "  public uint AttributeCount; public IntPtr Attributes; public IntPtr TargetAlias;",
            "  public IntPtr UserName; }",
            "'@",
            "  \$t = \$env:DM_KEYCHAIN_TARGET",
            "  \$p = [IntPtr]::Zero",
            "  if (-not [DmKc.Native]::CredReadW(\$t, 1, 0, [ref]\$p)) { exit 2 }",
            "  try {",
            "    \$c = [Runtime.InteropServices.Marshal]::PtrToStructure(\$p, [Type]([DmKc.Native+CREDENTIAL]))",
            "    if (\$c.CredentialBlobSize -le 0) { exit 2 }",
            "    \$b = New-Object byte[] \$c.CredentialBlobSize",
            "    [Runtime.InteropServices.Marshal]::Copy(\$c.CredentialBlob, \$b, 0, \$c.CredentialBlobSize)",
            "    [Console]::Out.Write([Text.Encoding]::Unicode.GetString(\$b))",
            "  } finally { [DmKc.Native]::CredFree(\$p) }",
            "  exit 0",
            "} catch { exit 3 }",
        ).joinToString("\n")

        /** `-EncodedCommand` erwartet Base64 der UTF-16LE-Bytes — umgeht Windows-Argv-Quoting komplett. */
        private val POWERSHELL_ENCODED: String =
            Base64.getEncoder().encodeToString(POWERSHELL_SCRIPT.toByteArray(Charsets.UTF_16LE))

        /** Sucht [command] in den `PATH`-Verzeichnissen (kein Prozess-Spawn). */
        private fun isOnPath(command: String): Boolean {
            val path = System.getenv("PATH") ?: return false
            return path.split(File.pathSeparatorChar).any { dir ->
                dir.isNotBlank() && File(dir, command).let { it.isFile && it.canExecute() }
            }
        }

        /**
         * Führt [cmd] aus: [env] auf die Prozess-Umgebung gelegt (Windows-TargetName, secret-frei),
         * stdin geschlossen, stderr verworfen (kein Blockieren, kein Secret-Leak über stderr), stdout
         * gelesen. Timeout → `destroyForcibly` + [CommandOutcome.SpawnFailed]. Das Secret liegt in
         * stdout, nicht in den Args.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private fun runProcess(cmd: List<String>, env: Map<String, String>, timeoutSeconds: Long): CommandOutcome {
            val process = try {
                ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { pb -> if (env.isNotEmpty()) pb.environment().putAll(env) }
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
