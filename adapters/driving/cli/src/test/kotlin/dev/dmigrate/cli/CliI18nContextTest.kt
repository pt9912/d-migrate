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

    test("--lang is rejected in 0.8.0 with local error exit 7") {
        var commandRan = false
        val cli = DMigrate(
            envLookup = { null },
            systemLocaleProvider = { Locale.ENGLISH },
            systemTimezoneProvider = { ZoneId.of("UTC") },
        ).subcommands(CaptureContextCommand { commandRan = true })

        val ex = shouldThrow<ProgramResult> {
            cli.parse(listOf("--lang", "de", "capture"))
        }

        ex.statusCode shouldBe 7
        commandRan shouldBe false
    }
})
