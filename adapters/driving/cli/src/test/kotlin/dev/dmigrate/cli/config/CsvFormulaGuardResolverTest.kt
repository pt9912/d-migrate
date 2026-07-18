package dev.dmigrate.cli.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Audit-Follow-up #6 (CWE-1236): CLI-Flag > config `export.csv.formula_guard` >
 * konservativer Default `false`. Spiegelt [ReverseAutoincrementResolverTest]:
 * die Präferenz ist optional und darf einen Export nie blockieren.
 */
class CsvFormulaGuardResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-formula-guard-test-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    val noConfig = Path.of("/tmp/dmigrate-no-such-${System.nanoTime()}.yaml")
    fun resolver(configPathFromCli: Path? = null, defaultConfigPath: Path = noConfig) =
        CsvFormulaGuardResolver(
            configPathFromCli = configPathFromCli,
            envLookup = { null },
            defaultConfigPath = defaultConfigPath,
        )

    test("flag true → guard on") {
        resolver().resolve(true) shouldBe true
    }

    test("flag false → guard off") {
        resolver().resolve(false) shouldBe false
    }

    test("no flag, no config → conservative default false") {
        resolver().resolve(null) shouldBe false
    }

    test("no flag, config export.csv.formula_guard true → on") {
        val cfg = tempConfig("export:\n  csv:\n    formula_guard: true\n")
        resolver(configPathFromCli = cfg).resolve(null) shouldBe true
    }

    test("no flag, config export.csv.formula_guard false → off") {
        val cfg = tempConfig("export:\n  csv:\n    formula_guard: false\n")
        resolver(configPathFromCli = cfg).resolve(null) shouldBe false
    }

    test("flag false overrides config true") {
        val cfg = tempConfig("export:\n  csv:\n    formula_guard: true\n")
        resolver(configPathFromCli = cfg).resolve(false) shouldBe false
    }

    test("flag true overrides config false") {
        val cfg = tempConfig("export:\n  csv:\n    formula_guard: false\n")
        resolver(configPathFromCli = cfg).resolve(true) shouldBe true
    }

    test("config present but no export.csv block → default false") {
        val cfg = tempConfig("database:\n  default: pg\n")
        resolver(configPathFromCli = cfg).resolve(null) shouldBe false
    }

    test("export block without csv sub-block → default false") {
        val cfg = tempConfig("export:\n  default_format: csv\n")
        resolver(configPathFromCli = cfg).resolve(null) shouldBe false
    }

    test("non-boolean formula_guard in config → conservative default false") {
        val cfg = tempConfig("export:\n  csv:\n    formula_guard: yes-please\n")
        resolver(configPathFromCli = cfg).resolve(null) shouldBe false
    }

    // Die Präferenz ist optional — ein explizit angegebener, aber fehlender
    // --config-Pfad blockiert den Export NICHT (bewusst lenient), sondern fällt
    // zum Default.
    test("explicit but missing --config is lenient → default false (no throw)") {
        val missing = Path.of("/tmp/dmigrate-missing-cli-${System.nanoTime()}.yaml")
        resolver(configPathFromCli = missing).resolve(null) shouldBe false
    }

    test("flag short-circuits the config read entirely") {
        val missing = Path.of("/tmp/dmigrate-missing-cli-${System.nanoTime()}.yaml")
        resolver(configPathFromCli = missing).resolve(true) shouldBe true
    }
})
