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
 * through [ReverseConfigBlock] — **deliberately lenient**: any absent,
 * unparseable, `reverse:`-less, or unrecognised-width config means "no
 * preference declared" and returns the conservative default.
 */
class ReverseAutoincrementResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    /** CLI flag width (32|64|null) overrides config; both absent → default. */
    fun resolve(flagWidth: Int?): SqliteAutoincrementReverse =
        widthToPreference(flagWidth ?: configWidth())

    private fun configWidth(): Int? =
        (ReverseConfigBlock(configPathFromCli, envLookup, defaultConfigPath).dialect("sqlite")
            ?.get("autoincrement_width") as? Number)?.toInt()

    private fun widthToPreference(width: Int?): SqliteAutoincrementReverse = when (width) {
        64 -> SqliteAutoincrementReverse.BIGINTEGER_IDENTITY
        // 32, null, or any unrecognised width → conservative 32-bit contract.
        else -> SqliteAutoincrementReverse.IDENTIFIER
    }
}
