package dev.dmigrate.cli.config

import dev.dmigrate.cli.i18n.UnicodeNormalizationMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.Locale

class I18nSettingsResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-i18n-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    test("locale priority is D_MIGRATE_LANG over LC_ALL, LANG, config, and system") {
        val cfg = tempConfig(
            """
            i18n:
              default_locale: de_DE
            """.trimIndent()
        )
        val resolver = I18nSettingsResolver(
            configPathFromCli = cfg,
            envLookup = {
                when (it) {
                    "D_MIGRATE_LANG" -> "en_US.UTF-8"
                    "LC_ALL" -> "de_DE.UTF-8"
                    "LANG" -> "de"
                    else -> null
                }
            },
            systemLocaleProvider = { Locale.of("fr", "FR") },
        )

        resolver.resolve().locale shouldBe Locale.of("en", "US")
    }

    test("config-path priority: CLI config wins over D_MIGRATE_CONFIG for i18n settings") {
        val cliCfg = tempConfig(
            """
            i18n:
              default_locale: de_DE
            """.trimIndent()
        )
        val envCfg = tempConfig(
            """
            i18n:
              default_locale: en_US
            """.trimIndent()
        )
        val resolver = I18nSettingsResolver(
            configPathFromCli = cliCfg,
            envLookup = { name -> if (name == "D_MIGRATE_CONFIG") envCfg.toString() else null },
            defaultConfigPath = tempConfig("i18n:\n  default_locale: fr_FR\n"),
            systemLocaleProvider = { Locale.of("it", "IT") },
        )

        resolver.resolve().locale shouldBe Locale.of("de", "DE")
    }

    test("config-path priority: D_MIGRATE_CONFIG wins over default config path for i18n settings") {
        val envCfg = tempConfig(
            """
            i18n:
              default_locale: en_US
            """.trimIndent()
        )
        val defaultCfg = tempConfig(
            """
            i18n:
              default_locale: de_DE
            """.trimIndent()
        )
        val resolver = I18nSettingsResolver(
            envLookup = { name -> if (name == "D_MIGRATE_CONFIG") envCfg.toString() else null },
            defaultConfigPath = defaultCfg,
            systemLocaleProvider = { Locale.of("it", "IT") },
        )

        resolver.resolve().locale shouldBe Locale.of("en", "US")
    }

    test("config-path priority: default config path is used when CLI and ENV are absent") {
        val defaultCfg = tempConfig(
            """
            i18n:
              default_locale: de_DE.UTF-8
            """.trimIndent()
        )
        val resolver = I18nSettingsResolver(
            envLookup = { null },
            defaultConfigPath = defaultCfg,
            systemLocaleProvider = { Locale.of("it", "IT") },
        )

        resolver.resolve().locale shouldBe Locale.of("de", "DE")
    }

    test("locale falls back to config when env vars are absent") {
        val cfg = tempConfig(
            """
            i18n:
              default_locale: de_DE.UTF-8
            """.trimIndent()
        )
        val resolver = I18nSettingsResolver(
            configPathFromCli = cfg,
            envLookup = { null },
            systemLocaleProvider = { Locale.of("fr", "FR") },
        )

        resolver.resolve().locale shouldBe Locale.of("de", "DE")
    }

    test("locale falls back to system locale when config is absent") {
        val missingDefault = Path.of("/tmp/dmigrate-i18n-missing.yaml")
        Files.deleteIfExists(missingDefault)
        val resolver = I18nSettingsResolver(
            configPathFromCli = null,
            envLookup = { null },
            defaultConfigPath = missingDefault,
            systemLocaleProvider = { Locale.of("fr", "FR") },
        )

        resolver.resolve().locale shouldBe Locale.of("fr", "FR")
    }

    test("blank system locale falls back to english root path") {
        val missingDefault = Path.of("/tmp/dmigrate-i18n-missing-blank.yaml")
        Files.deleteIfExists(missingDefault)
        val resolver = I18nSettingsResolver(
            configPathFromCli = null,
            envLookup = { null },
            defaultConfigPath = missingDefault,
            systemLocaleProvider = { Locale.ROOT },
        )

        resolver.resolve().locale shouldBe Locale.ENGLISH
    }

    test("C, C.UTF-8, and POSIX are accepted as english aliases") {
        listOf("C", "C.UTF-8", "POSIX").forEach { raw ->
            val resolver = I18nSettingsResolver(
                envLookup = { name -> if (name == "D_MIGRATE_LANG") raw else null },
                systemLocaleProvider = { Locale.of("fr", "FR") },
            )
            resolver.resolve().locale shouldBe Locale.ENGLISH
        }
    }

    test("timezone comes from config before system default") {
        val cfg = tempConfig(
            """
            i18n:
              default_timezone: Europe/Berlin
            """.trimIndent()
        )
        val resolver = I18nSettingsResolver(
            configPathFromCli = cfg,
            envLookup = { null },
            systemTimezoneProvider = { ZoneId.of("UTC") },
        )

        resolver.resolve().timezone shouldBe ZoneId.of("Europe/Berlin")
    }

    test("D_MIGRATE_TIMEZONE is ignored in 0.8.0") {
        val missingDefault = Path.of("/tmp/dmigrate-i18n-missing-tz.yaml")
        Files.deleteIfExists(missingDefault)
        val resolver = I18nSettingsResolver(
            envLookup = {
                when (it) {
                    "D_MIGRATE_TIMEZONE" -> "Europe/Berlin"
                    else -> null
                }
            },
            defaultConfigPath = missingDefault,
            systemTimezoneProvider = { ZoneId.of("UTC") },
        )

        resolver.resolve().timezone shouldBe ZoneId.of("UTC")
    }

    test("normalization comes from config and falls back to NFC") {
        val cfg = tempConfig(
            """
            i18n:
              normalize_unicode: NFKC
            """.trimIndent()
        )
        val configured = I18nSettingsResolver(configPathFromCli = cfg, envLookup = { null })
        configured.resolve().normalization shouldBe UnicodeNormalizationMode.NFKC

        val missingDefault = Path.of("/tmp/dmigrate-i18n-missing-normalization.yaml")
        Files.deleteIfExists(missingDefault)
        val fallback = I18nSettingsResolver(
            envLookup = { null },
            defaultConfigPath = missingDefault,
        )
        fallback.resolve().normalization shouldBe UnicodeNormalizationMode.NFC
    }

    test("invalid locale from env fails with a clear local config error") {
        val resolver = I18nSettingsResolver(
            envLookup = { name -> if (name == "D_MIGRATE_LANG") "english_germany" else null },
        )

        val ex = shouldThrow<ConfigResolveException> { resolver.resolve() }
        ex.message!! shouldContain "D_MIGRATE_LANG is invalid"
    }

    test("invalid timezone from config fails clearly") {
        val cfg = tempConfig(
            """
            i18n:
              default_timezone: Mars/Olympus
            """.trimIndent()
        )
        val resolver = I18nSettingsResolver(configPathFromCli = cfg, envLookup = { null })

        val ex = shouldThrow<ConfigResolveException> { resolver.resolve() }
        ex.message!! shouldContain "i18n.default_timezone"
        ex.message!! shouldContain "Mars/Olympus"
    }

    test("invalid normalization from config fails clearly") {
        val cfg = tempConfig(
            """
            i18n:
              normalize_unicode: nfkc
            """.trimIndent()
        )
        val resolver = I18nSettingsResolver(configPathFromCli = cfg, envLookup = { null })

        val ex = shouldThrow<ConfigResolveException> { resolver.resolve() }
        ex.message!! shouldContain "i18n.normalize_unicode"
        ex.message!! shouldContain "nfkc"
    }

    test("broken YAML fails clearly") {
        val cfg = tempConfig("i18n:\n  default_locale: {broken\n")
        val resolver = I18nSettingsResolver(configPathFromCli = cfg, envLookup = { null })

        val ex = shouldThrow<ConfigResolveException> { resolver.resolve() }
        ex.message!! shouldContain "Failed to parse"
    }

    test("i18n section and values must be mappings and strings") {
        val notMapping = tempConfig("i18n: true\n")
        shouldThrow<ConfigResolveException> {
            I18nSettingsResolver(configPathFromCli = notMapping, envLookup = { null }).resolve()
        }.message!! shouldContain "i18n in"

        val badValue = tempConfig(
            """
            i18n:
              default_locale:
                lang: de
            """.trimIndent()
        )
        shouldThrow<ConfigResolveException> {
            I18nSettingsResolver(configPathFromCli = badValue, envLookup = { null }).resolve()
        }.message!! shouldContain "i18n.default_locale"
    }

    test("explicit D_MIGRATE_CONFIG path must exist") {
        val resolver = I18nSettingsResolver(
            envLookup = { name -> if (name == "D_MIGRATE_CONFIG") "/nope-i18n.yaml" else null },
        )

        val ex = shouldThrow<ConfigResolveException> { resolver.resolve() }
        ex.message!! shouldContain "D_MIGRATE_CONFIG points to non-existent file"
    }
})
