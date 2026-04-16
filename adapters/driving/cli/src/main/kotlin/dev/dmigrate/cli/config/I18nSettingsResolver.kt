package dev.dmigrate.cli.config

import dev.dmigrate.cli.i18n.ResolvedI18nSettings
import dev.dmigrate.cli.i18n.UnicodeNormalizationMode
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class I18nSettingsResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
    private val systemLocaleProvider: () -> Locale = Locale::getDefault,
    private val systemTimezoneProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    fun resolve(): ResolvedI18nSettings {
        val configPath = EffectiveConfigPathResolver(
            configPathFromCli = configPathFromCli,
            envLookup = envLookup,
            defaultConfigPath = defaultConfigPath,
        ).resolve()
        val config = loadConfig(configPath)
        return ResolvedI18nSettings(
            locale = resolveLocale(config),
            timezone = resolveTimezone(config),
            normalization = resolveNormalization(config),
        )
    }

    private fun resolveLocale(config: ParsedI18nConfig): Locale {
        val envCandidates = listOf(
            EnvLocaleCandidate("D_MIGRATE_LANG", envLookup("D_MIGRATE_LANG")),
            EnvLocaleCandidate("LC_ALL", envLookup("LC_ALL")),
            EnvLocaleCandidate("LANG", envLookup("LANG")),
        )

        envCandidates.firstOrNull { !it.value.isNullOrBlank() }?.let { candidate ->
            return parseLocale(candidate.value!!, candidate.name)
        }

        config.defaultLocale?.let { return parseLocale(it, "i18n.default_locale in ${config.sourcePath}") }

        val systemLocale = runCatching(systemLocaleProvider).getOrElse { Locale.ENGLISH }
        if (systemLocale.language.isNotBlank()) return systemLocale
        return Locale.ENGLISH
    }

    private fun resolveTimezone(config: ParsedI18nConfig): ZoneId {
        config.defaultTimezone?.let { value ->
            return try {
                ZoneId.of(value.trim())
            } catch (_: Exception) {
                throw ConfigResolveException(
                    "i18n.default_timezone in ${config.sourcePath} is invalid: '$value'"
                )
            }
        }
        return runCatching(systemTimezoneProvider).getOrElse { ZoneOffset.UTC }
    }

    private fun resolveNormalization(config: ParsedI18nConfig): UnicodeNormalizationMode {
        val value = config.normalizeUnicode ?: return UnicodeNormalizationMode.NFC
        return try {
            UnicodeNormalizationMode.valueOf(value.trim())
        } catch (_: IllegalArgumentException) {
            throw ConfigResolveException(
                "i18n.normalize_unicode in ${config.sourcePath} is invalid: '$value'"
            )
        }
    }

    private fun parseLocale(raw: String, source: String): Locale {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw ConfigResolveException("$source must not be blank")
        }

        val withoutModifier = trimmed.substringBefore('@')
        val base = withoutModifier.substringBefore('.')
        val upperBase = base.uppercase(Locale.ROOT)
        if (upperBase == "C" || upperBase == "POSIX") return Locale.ENGLISH

        val normalized = base.replace('-', '_')
        val parts = normalized.split('_').filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 2) {
            throw ConfigResolveException("$source is invalid: '$raw'")
        }

        val language = parts[0]
        if (!LANGUAGE_PATTERN.matches(language)) {
            throw ConfigResolveException("$source is invalid: '$raw'")
        }

        val country = parts.getOrNull(1).orEmpty()
        if (country.isNotEmpty() && !COUNTRY_PATTERN.matches(country)) {
            throw ConfigResolveException("$source is invalid: '$raw'")
        }

        val normalizedLanguage = language.lowercase(Locale.ROOT)
        val normalizedCountry = country.uppercase(Locale.ROOT)
        return if (normalizedCountry.isEmpty()) {
            Locale.of(normalizedLanguage)
        } else {
            Locale.of(normalizedLanguage, normalizedCountry)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadConfig(configPath: EffectiveConfigPath): ParsedI18nConfig {
        if (!Files.isRegularFile(configPath.path)) {
            return when (configPath.source) {
                EffectiveConfigSource.DEFAULT ->
                    ParsedI18nConfig(sourcePath = configPath.path)

                EffectiveConfigSource.CLI ->
                    throw ConfigResolveException("Config file not found: ${configPath.path}")

                EffectiveConfigSource.ENV ->
                    throw ConfigResolveException(
                        "D_MIGRATE_CONFIG points to non-existent file: ${configPath.path}"
                    )
            }
        }

        val parsed: Any? = try {
            val settings = LoadSettings.builder().build()
            Files.newInputStream(configPath.path).use { input ->
                Load(settings).loadFromInputStream(input)
            }
        } catch (t: Throwable) {
            throw ConfigResolveException(
                "Failed to parse ${configPath.path}: ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }

        val root = parsed as? Map<String, Any?>
            ?: throw ConfigResolveException("Failed to parse ${configPath.path}: top-level YAML must be a mapping")

        val i18nRaw = root["i18n"] ?: return ParsedI18nConfig(sourcePath = configPath.path)
        val i18n = i18nRaw as? Map<String, Any?>
            ?: throw ConfigResolveException("i18n in ${configPath.path} must be a mapping")

        return ParsedI18nConfig(
            sourcePath = configPath.path,
            defaultLocale = readOptionalString(i18n, "default_locale", configPath.path),
            defaultTimezone = readOptionalString(i18n, "default_timezone", configPath.path),
            normalizeUnicode = readOptionalString(i18n, "normalize_unicode", configPath.path),
        )
    }

    private fun readOptionalString(values: Map<String, Any?>, key: String, configPath: Path): String? {
        val value = values[key] ?: return null
        return when (value) {
            is String -> value
            else -> throw ConfigResolveException(
                "i18n.$key in $configPath must be a string, got ${value::class.simpleName}"
            )
        }
    }

    private data class ParsedI18nConfig(
        val sourcePath: Path,
        val defaultLocale: String? = null,
        val defaultTimezone: String? = null,
        val normalizeUnicode: String? = null,
    )

    private data class EnvLocaleCandidate(val name: String, val value: String?)

    companion object {
        private val LANGUAGE_PATTERN = Regex("[A-Za-z]{2,3}")
        private val COUNTRY_PATTERN = Regex("[A-Za-z]{2}|\\d{3}")
    }
}
