package dev.dmigrate.cli.config

import dev.dmigrate.driver.SqliteAutoincrementReverse
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
 * maps it onto the internal [SqliteAutoincrementReverse]. The config is read
 * through [ReverseConfigBlock]: an absent, unparseable or `reverse:`-less
 * config means "no preference declared" and returns the conservative default;
 * a width that is present but neither `32` nor `64` is a configuration error
 * ([InvalidReversePreference]), not a silent fallback.
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
        val raw = ReverseConfigBlock(configPathFromCli, envLookup, defaultConfigPath).dialect("sqlite")
            ?.get(CONFIG_KEY) ?: return null
        return raw.toString().trim().toIntOrNull()?.takeIf { it in WIDTHS }
            ?: throw InvalidReversePreference("reverse.sqlite.$CONFIG_KEY", raw, WIDTHS.map(Int::toString))
    }

    private fun widthToPreference(width: Int?): SqliteAutoincrementReverse = when (width) {
        64 -> SqliteAutoincrementReverse.BIGINTEGER_IDENTITY
        // 32 or nothing declared → conservative 32-bit contract.
        else -> SqliteAutoincrementReverse.IDENTIFIER
    }

    private companion object {
        const val CONFIG_KEY = "autoincrement_width"
        val WIDTHS = listOf(32, 64)
    }
}
