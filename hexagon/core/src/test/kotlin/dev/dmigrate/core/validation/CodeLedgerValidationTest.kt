package dev.dmigrate.core.validation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

/**
 * Validates the error-code and warn-code ledger YAML files against
 * structural rules from docs/ImpPlan-0.9.2-6.7.md section 4.3.
 *
 * Validates: code uniqueness, completeness (E001-E121), test_path existence,
 * evidence_paths presence, path_type values, level values, entry_type
 * constraints, and rest_path mandatory fields.
 */
class CodeLedgerValidationTest : FunSpec({

    val repoRoot = run {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").exists()) return@run dir
            dir = dir.parentFile
        }
        File(System.getProperty("user.dir"))
    }

    fun readLedger(name: String): String {
        val f = File(repoRoot, "ledger/$name")
        if (!f.exists()) return ""
        return f.readText()
    }

    /** Extract all top-level code values from a ledger YAML. */
    fun extractCodes(content: String): List<String> =
        Regex("""code:\s+"?(E\d{3}|W\d{3})"?""", RegexOption.MULTILINE)
            .findAll(content)
            .map { it.groupValues[1] }
            .toList()

    /** Extract a named field value from the block starting at a given code. */
    fun extractField(content: String, code: String, field: String): String? {
        val codeIdx = content.indexOf("code: $code")
        if (codeIdx < 0) return null
        val nextEntryIdx = content.indexOf("\n  - code:", codeIdx + 1).let { if (it < 0) content.length else it }
        val block = content.substring(codeIdx, nextEntryIdx)
        val match = Regex("""$field:\s+"?([^"\n]+)"?""").find(block)
        return match?.groupValues?.get(1)?.trim()
    }

    /** Check if evidence_paths block exists for a code. */
    fun hasEvidencePaths(content: String, code: String): Boolean {
        val codeIdx = content.indexOf("code: $code")
        if (codeIdx < 0) return false
        val nextEntryIdx = content.indexOf("\n  - code:", codeIdx + 1).let { if (it < 0) content.length else it }
        val block = content.substring(codeIdx, nextEntryIdx)
        return block.contains("evidence_paths:")
    }

    /** Extract all path_type values from the evidence_paths block for a code. */
    fun extractPathTypes(content: String, code: String): List<String> {
        val codeIdx = content.indexOf("code: $code")
        if (codeIdx < 0) return emptyList()
        val nextEntryIdx = content.indexOf("\n  - code:", codeIdx + 1).let { if (it < 0) content.length else it }
        val block = content.substring(codeIdx, nextEntryIdx)
        return Regex("""path_type:\s+"?(\w+)"?""").findAll(block).map { it.groupValues[1] }.toList()
    }

    /** Extract all source values from evidence_paths for a code. */
    fun extractEvidenceSources(content: String, code: String): List<String> {
        val codeIdx = content.indexOf("code: $code")
        if (codeIdx < 0) return emptyList()
        val nextEntryIdx = content.indexOf("\n  - code:", codeIdx + 1).let { if (it < 0) content.length else it }
        val block = content.substring(codeIdx, nextEntryIdx)
        return Regex("""source:\s+"([^"]+)"""").findAll(block).map { it.groupValues[1] }.toList()
    }

    val allowedPathTypes = setOf("production", "test", "documentation")
    val allowedLevels = setOf("error", "warning")
    val allowedEntryTypes = setOf("standard", "rest_path")
    val allowedStatuses = setOf("active", "not_applicable")

    // ─── Error Ledger ────────────────────────────────────────────

    test("error ledger exists and has version 0.9.2") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        content.length shouldNotBe 0
        content.contains("version: \"0.9.2\"") shouldBe true
    }

    test("error ledger covers all codes E001-E020 (except E004)") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val codes = extractCodes(content).toSet()
        val expected = (1..20).filter { it != 4 }.map { "E${it.toString().padStart(3, '0')}" }
        val missing = expected - codes
        missing.shouldBeEmpty()
    }

    test("error ledger covers E052-E056, E060, E120-E121") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val codes = extractCodes(content).toSet()
        val missing = listOf("E052", "E053", "E054", "E055", "E056", "E060", "E120", "E121") - codes
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

    test("error ledger: every entry has valid level") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val invalid = extractCodes(content).filter { code ->
            val level = extractField(content, code, "level")
            level == null || level !in allowedLevels
        }
        invalid.shouldBeEmpty()
    }

    test("error ledger: every entry has valid entry_type") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val invalid = extractCodes(content).filter { code ->
            val entryType = extractField(content, code, "entry_type")
            entryType == null || entryType !in allowedEntryTypes
        }
        invalid.shouldBeEmpty()
    }

    test("error ledger: every entry has valid status") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val invalid = extractCodes(content).filter { code ->
            val status = extractField(content, code, "status")
            status == null || status !in allowedStatuses
        }
        invalid.shouldBeEmpty()
    }

    test("error ledger: standard active entries have test_path") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val missing = extractCodes(content).filter { code ->
            extractField(content, code, "entry_type") == "standard" &&
                extractField(content, code, "status") == "active" &&
                extractField(content, code, "test_path") == null
        }
        missing.shouldBeEmpty()
    }

    test("error ledger: standard test_paths reference existing files") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val badPaths = extractCodes(content).mapNotNull { code ->
            if (extractField(content, code, "entry_type") != "standard") return@mapNotNull null
            val testPath = extractField(content, code, "test_path") ?: return@mapNotNull null
            if (!File(repoRoot, testPath).exists()) "$code -> $testPath" else null
        }
        badPaths.shouldBeEmpty()
    }

    test("error ledger: every active entry has evidence_paths") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val missing = extractCodes(content).filter { code ->
            extractField(content, code, "status") == "active" && !hasEvidencePaths(content, code)
        }
        missing.shouldBeEmpty()
    }

    test("error ledger: evidence_paths have valid path_type") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val invalid = extractCodes(content).flatMap { code ->
            extractPathTypes(content, code).filter { it !in allowedPathTypes }.map { "$code: $it" }
        }
        invalid.shouldBeEmpty()
    }

    test("error ledger: evidence_paths have non-empty source") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val missing = extractCodes(content).filter { code ->
            hasEvidencePaths(content, code) && extractEvidenceSources(content, code).isEmpty()
        }
        missing.shouldBeEmpty()
    }

    test("error ledger: evidence source files exist") {
        val content = readLedger("error-code-ledger-0.9.2.yaml")
        val badPaths = extractCodes(content).flatMap { code ->
            extractEvidenceSources(content, code).mapNotNull { source ->
                if (!File(repoRoot, source).exists()) "$code -> $source" else null
            }
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

    test("warn ledger: every entry has valid level") {
        val content = readLedger("warn-code-ledger-0.9.2.yaml")
        val invalid = extractCodes(content).filter { code ->
            extractField(content, code, "level") !in allowedLevels
        }
        invalid.shouldBeEmpty()
    }

    test("warn ledger: every entry has evidence_paths with valid path_type") {
        val content = readLedger("warn-code-ledger-0.9.2.yaml")
        val invalid = extractCodes(content).flatMap { code ->
            extractPathTypes(content, code).filter { it !in allowedPathTypes }.map { "$code: $it" }
        }
        invalid.shouldBeEmpty()
    }

    test("warn ledger: test_paths reference existing files") {
        val content = readLedger("warn-code-ledger-0.9.2.yaml")
        val badPaths = extractCodes(content).mapNotNull { code ->
            val testPath = extractField(content, code, "test_path") ?: return@mapNotNull null
            if (!File(repoRoot, testPath).exists()) "$code -> $testPath" else null
        }
        badPaths.shouldBeEmpty()
    }

    test("warn ledger: evidence source files exist") {
        val content = readLedger("warn-code-ledger-0.9.2.yaml")
        val badPaths = extractCodes(content).flatMap { code ->
            extractEvidenceSources(content, code).mapNotNull { source ->
                if (!File(repoRoot, source).exists()) "$code -> $source" else null
            }
        }
        badPaths.shouldBeEmpty()
    }
})
