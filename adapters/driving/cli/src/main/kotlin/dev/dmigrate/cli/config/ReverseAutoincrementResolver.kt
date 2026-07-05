package dev.dmigrate.cli.config

import dev.dmigrate.driver.SqliteAutoincrementReverse
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the SQLite AUTOINCREMENT-width reverse preference
 * (reverse-preferences slice): CLI flag > config
 * `reverse.sqlite.autoincrement_width` > conservative default (32-bit
 * `identifier`).
 *
 * The surface vocabulary is dialect-neutral *width* (`32` | `64`) — it decouples
 * the stable config/CLI contract from internal neutral-type names; this resolver
 * maps it onto the internal [SqliteAutoincrementReverse]. Path resolution follows
 * the same `--config` > `D_MIGRATE_CONFIG` > default precedence as the other
 * config sections (mirrors [PipelineCheckpointResolver]). A missing or
 * unparseable config, or an unrecognised width, yields the conservative default —
 * the preference is optional and never blocks the reverse.
 */
class ReverseAutoincrementResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    /** CLI flag width (32|64|null) overrides config; both absent → default. */
    fun resolve(flagWidth: Int?): SqliteAutoincrementReverse =
        widthToPreference(flagWidth ?: configWidth())

    private fun configWidth(): Int? {
        val effective = EffectiveConfigPathResolver(
            configPathFromCli = configPathFromCli,
            envLookup = envLookup,
            defaultConfigPath = defaultConfigPath,
        ).resolve()
        if (!Files.isRegularFile(effective.path)) return null
        val parsed: Any? = try {
            val settings = LoadSettings.builder().build()
            Files.newInputStream(effective.path).use { input -> Load(settings).loadFromInputStream(input) }
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
            // Optional preference: a broken config surfaces through the other
            // config resolvers / connection resolution, not here.
            return null
        }
        val root = parsed as? Map<*, *> ?: return null
        val reverse = root["reverse"] as? Map<*, *> ?: return null
        val sqlite = reverse["sqlite"] as? Map<*, *> ?: return null
        return (sqlite["autoincrement_width"] as? Number)?.toInt()
    }

    private fun widthToPreference(width: Int?): SqliteAutoincrementReverse = when (width) {
        64 -> SqliteAutoincrementReverse.BIGINTEGER_IDENTITY
        // 32, null, or any unrecognised width → conservative 32-bit contract.
        else -> SqliteAutoincrementReverse.IDENTIFIER
    }
}
