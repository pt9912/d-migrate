package dev.dmigrate.connection

import dev.dmigrate.connection.ShelloutKeychainBackend.CommandOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Base64

class ShelloutKeychainBackendTest : FunSpec({

    class Capture {
        var cmd: List<String>? = null
        var env: Map<String, String>? = null
    }

    fun backend(
        os: String,
        toolPresent: Boolean = true,
        outcome: CommandOutcome = CommandOutcome.Completed(0, "url"),
        capture: Capture = Capture(),
    ) = ShelloutKeychainBackend(
        osName = os,
        commandExists = { toolPresent },
        runCommand = { cmd, env -> capture.cmd = cmd; capture.env = env; outcome },
    )

    // --- isAvailable ---

    test("isAvailable: macOS + tool on PATH → true") {
        backend("Mac OS X", toolPresent = true).isAvailable() shouldBe true
    }
    test("isAvailable: Linux + tool on PATH → true") {
        backend("Linux", toolPresent = true).isAvailable() shouldBe true
    }
    test("isAvailable: Windows + powershell on PATH → true (native-frei via Shell-out, ADR 0040)") {
        backend("Windows 11", toolPresent = true).isAvailable() shouldBe true
    }
    test("isAvailable: tool NOT on PATH → false (fail-closed)") {
        backend("Linux", toolPresent = false).isAvailable() shouldBe false
    }
    test("isAvailable: unsupported OS → false (fail-closed)") {
        backend("SunOS", toolPresent = true).isAvailable() shouldBe false
    }

    // --- command construction (secret is on stdout, never in args) ---

    test("macOS: security find-generic-password -s <service> -a <account> -w") {
        val cap = Capture()
        backend("Mac OS X", capture = cap).lookup("pg-prod", "app")
        cap.cmd shouldBe listOf("security", "find-generic-password", "-s", "pg-prod", "-a", "app", "-w")
    }
    test("macOS: omits -a when account is null") {
        val cap = Capture()
        backend("Mac OS X", capture = cap).lookup("pg-prod", null)
        cap.cmd shouldBe listOf("security", "find-generic-password", "-s", "pg-prod", "-w")
    }
    test("Linux: secret-tool lookup service <service> account <account>") {
        val cap = Capture()
        backend("Linux", capture = cap).lookup("pg-prod", "app")
        cap.cmd shouldBe listOf("secret-tool", "lookup", "service", "pg-prod", "account", "app")
    }
    test("Linux: omits account when null") {
        val cap = Capture()
        backend("Linux", capture = cap).lookup("pg-prod", null)
        cap.cmd shouldBe listOf("secret-tool", "lookup", "service", "pg-prod")
    }

    // --- Windows: PowerShell + constant encoded script, target via env (no injection, no ps-leak) ---

    test("Windows: powershell.exe with a constant -EncodedCommand (5 argv elements)") {
        val cap = Capture()
        backend("Windows 11", capture = cap).lookup("pg-prod", null)
        cap.cmd!!.subList(0, 4) shouldBe listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand")
        cap.cmd!!.size shouldBe 5
    }
    test("Windows: target goes via DM_KEYCHAIN_TARGET env (service only)") {
        val cap = Capture()
        backend("Windows 11", capture = cap).lookup("pg-prod", null)
        cap.env shouldBe mapOf(ShelloutKeychainBackend.ENV_TARGET to "pg-prod")
    }
    test("Windows: account folds into the target name (service/account)") {
        val cap = Capture()
        backend("Windows 11", capture = cap).lookup("pg-prod", "app")
        cap.env shouldBe mapOf(ShelloutKeychainBackend.ENV_TARGET to "pg-prod/app")
    }
    test("Windows: encoded script reads the env var and never interpolates the service (injection-safe)") {
        val cap = Capture()
        backend("Windows 11", capture = cap).lookup("evil; Remove-Item C:\\", null)
        val script = String(Base64.getDecoder().decode(cap.cmd!![4]), Charsets.UTF_16LE)
        script shouldContain "CredReadW"
        script shouldContain "\$env:DM_KEYCHAIN_TARGET"
        // The (hostile) service name must NOT appear in the script text — it only rides the env var.
        script shouldNotContain "Remove-Item"
        script shouldNotContain "evil"
    }

    // --- exit-code mapping ---

    test("exit 0 + stdout → Found(value)") {
        backend("Linux", outcome = CommandOutcome.Completed(0, "postgresql://h/db"))
            .lookup("s", null).shouldBeInstanceOf<KeychainLookup.Found>().value shouldBe "postgresql://h/db"
    }
    test("exit 0 + blank stdout → NotFound") {
        backend("Linux", outcome = CommandOutcome.Completed(0, "  \n")).lookup("s", null) shouldBe KeychainLookup.NotFound
    }
    test("macOS exit 44 → NotFound") {
        backend("Mac OS X", outcome = CommandOutcome.Completed(44, "")).lookup("s", null) shouldBe KeychainLookup.NotFound
    }
    test("Linux exit 1 → NotFound") {
        backend("Linux", outcome = CommandOutcome.Completed(1, "")).lookup("s", null) shouldBe KeychainLookup.NotFound
    }
    test("Windows exit 2 → NotFound") {
        backend("Windows 11", outcome = CommandOutcome.Completed(2, "")).lookup("s", null) shouldBe KeychainLookup.NotFound
    }
    test("Windows exit 0 + stdout → Found") {
        backend("Windows 11", outcome = CommandOutcome.Completed(0, "postgresql://h/db"))
            .lookup("s", null).shouldBeInstanceOf<KeychainLookup.Found>().value shouldBe "postgresql://h/db"
    }
    test("Windows exit 3 (script error) → Unavailable (fail-closed)") {
        backend("Windows 11", outcome = CommandOutcome.Completed(3, ""))
            .lookup("s", null).shouldBeInstanceOf<KeychainLookup.Unavailable>()
    }
    test("other non-zero exit → Unavailable (fail-closed)") {
        backend("Linux", outcome = CommandOutcome.Completed(2, ""))
            .lookup("s", null).shouldBeInstanceOf<KeychainLookup.Unavailable>()
    }
    test("SpawnFailed (tool missing / timeout) → Unavailable, detail passed through") {
        backend("Mac OS X", outcome = CommandOutcome.SpawnFailed("keychain lookup timed out after 10s"))
            .lookup("s", null).shouldBeInstanceOf<KeychainLookup.Unavailable>()
            .detail shouldContain "timed out"
    }
    test("unsupported OS lookup → Unavailable, no command spawned") {
        val cap = Capture()
        backend("SunOS", capture = cap).lookup("s", null).shouldBeInstanceOf<KeychainLookup.Unavailable>()
        cap.cmd shouldBe null
    }
})
