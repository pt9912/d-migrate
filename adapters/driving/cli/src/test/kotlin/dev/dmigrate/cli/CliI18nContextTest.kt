package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.i18n.UnicodeNormalizationMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.Locale

class CliI18nContextTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-cli-i18n-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    class CaptureContextCommand(private val sink: (CliContext) -> Unit) : CliktCommand(name = "capture") {
        override fun run() {
            val root = currentContext.parent?.command as DMigrate
            sink(root.cliContext())
        }
    }

    test("CliContext carries resolved locale, timezone, and normalization") {
        val cfg = tempConfig(
            """
            i18n:
              default_locale: de_DE.UTF-8
              default_timezone: Europe/Berlin
              normalize_unicode: NFKD
            """.trimIndent()
        )

        lateinit var captured: CliContext
        val cli = DMigrate(
            envLookup = { null },
            defaultConfigPath = cfg,
            systemLocaleProvider = { Locale.of("fr", "FR") },
            systemTimezoneProvider = { ZoneId.of("UTC") },
        ).subcommands(CaptureContextCommand { captured = it })

        cli.parse(listOf("capture"))

        captured.locale shouldBe Locale.of("de", "DE")
        captured.timezone shouldBe ZoneId.of("Europe/Berlin")
        captured.normalization shouldBe UnicodeNormalizationMode.NFKD
    }

    // ────────────────────────────────────────────────────────────
    // 0.9.0 Phase A (docs/ImpPlan-0.9.0-A.md §4.1 / §4.2 / §4.5):
    // --lang ist jetzt ein aktiver Override mit Produktsprachen-
    // Validierung; Unsupported-Werte mappen auf Exit 2.
    // ────────────────────────────────────────────────────────────

    test("Phase A §4.1: --lang de gewinnt gegen System-Locale") {
        lateinit var captured: CliContext
        val cli = DMigrate(
            envLookup = { null },
            systemLocaleProvider = { Locale.of("fr", "FR") },
            systemTimezoneProvider = { ZoneId.of("UTC") },
        ).subcommands(CaptureContextCommand { captured = it })

        cli.parse(listOf("--lang", "de", "capture"))

        captured.locale shouldBe Locale.of("de")
    }

    test("Phase A §4.1: --lang en-US wird kanonisch aufgeloest und gewinnt gegen Env") {
        lateinit var captured: CliContext
        val cli = DMigrate(
            envLookup = { if (it == "D_MIGRATE_LANG") "de" else null },
            systemLocaleProvider = { Locale.of("fr") },
            systemTimezoneProvider = { ZoneId.of("UTC") },
        ).subcommands(CaptureContextCommand { captured = it })

        cli.parse(listOf("--lang", "en-US", "capture"))

        captured.locale shouldBe Locale.of("en", "US")
    }

    test("Phase A §4.2 / §4.5: unsupported --lang fr endet mit Exit 2") {
        var commandRan = false
        val cli = DMigrate(
            envLookup = { null },
            systemLocaleProvider = { Locale.ENGLISH },
            systemTimezoneProvider = { ZoneId.of("UTC") },
        ).subcommands(CaptureContextCommand { commandRan = true })

        val ex = shouldThrow<ProgramResult> {
            cli.parse(listOf("--lang", "fr", "capture"))
        }

        ex.statusCode shouldBe 2
        commandRan shouldBe false
    }

    test("Phase A §4.2: generischer Env-Locale (fr) bleibt ohne --lang zulaessig") {
        lateinit var captured: CliContext
        val cli = DMigrate(
            envLookup = { if (it == "LANG") "fr_FR.UTF-8" else null },
            systemLocaleProvider = { Locale.ENGLISH },
            systemTimezoneProvider = { ZoneId.of("UTC") },
        ).subcommands(CaptureContextCommand { captured = it })

        cli.parse(listOf("capture"))

        // Allgemeiner Locale-Pfad ist unveraendert — MessageResolver
        // faellt zur Laufzeit auf das Root-Bundle zurueck.
        captured.locale shouldBe Locale.of("fr", "FR")
    }
})
