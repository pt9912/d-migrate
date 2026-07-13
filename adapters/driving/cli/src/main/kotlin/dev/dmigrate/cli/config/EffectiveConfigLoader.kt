package dev.dmigrate.cli.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * LN-005: geladene effektive `.d-migrate.yaml`. [root] ist die YAML-Wurzel-Map
 * oder `null` (keine Datei / kein Map); [path] ist der effektive Pfad (für
 * aussagekräftige Fehlermeldungen der Key-Validierung).
 */
internal data class LoadedConfig(val root: Map<*, *>?, val path: Path)

/**
 * LN-005: **gemeinsamer** Loader für die effektive `.d-migrate.yaml` — ersetzt die
 * zuvor je Resolver (`PipelineCheckpointResolver`/`PipelineTuningResolver`/
 * `ParquetExportConfigResolver`) kopierte Pfad-Auflösungs- + YAML-Lade-Boilerplate.
 *
 * Pfad-Priorität: `--config` > `D_MIGRATE_CONFIG` > Default. Fehlt eine CLI-/ENV-
 * angegebene Datei → [ConfigResolveException]; fehlt die Default-Datei → `root = null`.
 */
internal fun loadEffectiveConfig(
    configPathFromCli: Path?,
    envLookup: (String) -> String? = System::getenv,
    defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
): LoadedConfig {
    val effective = EffectiveConfigPathResolver(
        configPathFromCli = configPathFromCli,
        envLookup = envLookup,
        defaultConfigPath = defaultConfigPath,
    ).resolve()

    if (!Files.isRegularFile(effective.path)) {
        return when (effective.source) {
            EffectiveConfigSource.DEFAULT -> LoadedConfig(root = null, path = effective.path)
            EffectiveConfigSource.CLI ->
                throw ConfigResolveException("Config file not found: ${effective.path}")
            EffectiveConfigSource.ENV ->
                throw ConfigResolveException(
                    "D_MIGRATE_CONFIG points to non-existent file: ${effective.path}"
                )
        }
    }

    val parsed: Any? = try {
        val settings = LoadSettings.builder().build()
        Files.newInputStream(effective.path).use { input ->
            Load(settings).loadFromInputStream(input)
        }
    } catch (t: Throwable) {
        throw ConfigResolveException(
            "Failed to parse ${effective.path}: ${t.message ?: t::class.simpleName}",
            cause = t,
        )
    }

    return LoadedConfig(root = parsed as? Map<*, *>, path = effective.path)
}

/**
 * LN-005 (#4): strenge Positive-**Ganzzahl**-Prüfung eines YAML-Werts (Int-Bereich).
 * Verhindert stille Coercion — `1.9`, `"5"` o. Ä. werden **abgelehnt** (nicht auf `1`
 * gekürzt), ein Wert `> Int.MAX_VALUE` ebenso (statt via `toInt()` zu wrappen).
 */
internal fun requirePositiveIntConfig(raw: Any?, keyPath: String, source: Path): Int {
    val value = requirePositiveLongConfig(raw, keyPath, source)
    if (value > Int.MAX_VALUE) {
        throw ConfigResolveException("$keyPath in $source must be <= ${Int.MAX_VALUE}, got $value")
    }
    return value.toInt()
}

/**
 * LN-005 (#4): strenge Positive-**Ganzzahl**-Prüfung eines YAML-Werts (Long-Bereich).
 * Akzeptiert nur ganzzahlige YAML-Literale (Int/Long/BigInteger); Fließkomma, String
 * usw. → [ConfigResolveException].
 */
internal fun requirePositiveLongConfig(raw: Any?, keyPath: String, source: Path): Long {
    val value: Long = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        is Short -> raw.toLong()
        is Byte -> raw.toLong()
        is BigInteger ->
            if (raw.bitLength() < Long.SIZE_BITS) raw.toLong()
            else throw ConfigResolveException("$keyPath in $source is out of range, got $raw")
        else -> throw ConfigResolveException(
            "$keyPath in $source must be a positive integer, got: $raw"
        )
    }
    if (value <= 0L) {
        throw ConfigResolveException("$keyPath in $source must be > 0, got $value")
    }
    return value
}
