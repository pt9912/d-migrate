package dev.dmigrate.core.validation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * Validates the error-code and warn-code ledger YAML files against
 * structural rules from docs/ImpPlan-0.9.2-6.7.md section 4.3.
 *
 * This test does NOT use a YAML parser — it validates via line-based
 * assertions to avoid adding a YAML dependency to hexagon:core.
 */
class CodeLedgerValidationTest : FunSpec({

    val repoRoot = run {
        var dir = File(System.getProperty("user.dir"))
        // Walk upward from the working directory to find the repo root
        // (identified by the presence of settings.gradle.kts)
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").exists()) return@run dir
            dir = dir.parentFile
        }
        File(System.getProperty("user.dir"))
    }

    fun readLedger(name: String): String {
        val f = File(repoRoot, "ledger/$name")
        if (!f.exists()) {
            System.err.println("[CodeLedgerValidationTest] Ledger not found: ${f.absolutePath} (repoRoot=$repoRoot)")
            return ""
        }
        return f.readText()
    }

    fun extractCodes(content: String): List<String> =
        Regex("""code:\s+"?(E\d{3}|W\d{3})"?""", RegexOption.MULTILINE)
            .findAll(content)
            .map { it.groupValues[1] }
            .toList()

    fun extractField(content: String, code: String, field: String): String? {
        val codeIdx = content.indexOf("code: $code")
        if (codeIdx < 0) return null
        val block = content.substring(codeIdx, minOf(content.length, codeIdx + 500))
        val match = Regex("""$field:\s+"?([^"\n]+)"?""").find(block)
        return match?.groupValues?.get(1)?.trim()
    }

    // ─── Error Ledger ────────────────────────────────────────────

    test("error ledger exists and has version") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        content.length shouldNotBe 0
        content.contains("version:") shouldBe true
    }

    test("error ledger covers all codes E001-E020 (except E004)") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val codes = extractCodes(content)
        val expected = (1..20).filter { it != 4 }.map { "E${it.toString().padStart(3, '0')}" }
        val missing = expected - codes.toSet()
        missing.shouldBeEmpty()
    }

    test("error ledger covers E052-E056, E060, E120-E121") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val codes = extractCodes(content).toSet()
        val expected = listOf("E052", "E053", "E054", "E055", "E056", "E060", "E120", "E121")
        val missing = expected - codes
        missing.shouldBeEmpty()
    }

    test("error ledger E004 is not_applicable") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        extractField(content, "E004", "status") shouldBe "not_applicable"
    }

    test("error ledger has no duplicate codes") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val codes = extractCodes(content)
        codes.size shouldBe codes.toSet().size
    }

    test("error ledger standard entries have test_path") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val codes = extractCodes(content)
        val missingTestPath = codes.filter { code ->
            val entryType = extractField(content, code, "entry_type")
            val status = extractField(content, code, "status")
            entryType == "standard" && status == "active" &&
                extractField(content, code, "test_path") == null
        }
        missingTestPath.shouldBeEmpty()
    }

    test("error ledger standard test_paths reference existing files") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val codes = extractCodes(content)
        val badPaths = mutableListOf<String>()
        for (code in codes) {
            val entryType = extractField(content, code, "entry_type")
            if (entryType != "standard") continue
            val testPath = extractField(content, code, "test_path") ?: continue
            val file = File(repoRoot, testPath)
            if (!file.exists()) badPaths += "$code -> $testPath"
        }
        badPaths.shouldBeEmpty()
    }

    // ─── Warn Ledger ─────────────────────────────────────────────

    test("warn ledger exists and covers W113 and W120") {
        val content = readLedger("warn-code-ledger-0.9.2.yaml")
        content shouldNotBe ""
        val codes = extractCodes(content).toSet()
        codes.contains("W113") shouldBe true
        codes.contains("W120") shouldBe true
    }

    test("warn ledger has no duplicate codes") {
        val content = readLedger("warn-code-ledger-0.9.2.yaml")
        val codes = extractCodes(content)
        codes.size shouldBe codes.toSet().size
    }

    test("warn ledger test_paths reference existing files") {
        val content = readLedger("warn-code-ledger-0.9.2.yaml")
        val codes = extractCodes(content)
        val badPaths = mutableListOf<String>()
        for (code in codes) {
            val testPath = extractField(content, code, "test_path") ?: continue
            val file = File(repoRoot, testPath)
            if (!file.exists()) badPaths += "$code -> $testPath"
        }
        badPaths.shouldBeEmpty()
    }
})
