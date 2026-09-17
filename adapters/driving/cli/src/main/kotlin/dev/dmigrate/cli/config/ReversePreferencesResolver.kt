package dev.dmigrate.cli.config

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ReversePreferences
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Alle Reverse-Praeferenzen eines Laufs in einem Wert — fuer die Lesepfade,
 * die kein eigenes Flag haben und nur die Konfiguration sehen:
 * `schema compare` mit `db:`-Operanden und `mcp serve`
 * (`schema_reverse_start`, `schema_compare_start` mit Verbindungen).
 * `schema reverse` reicht zusaetzlich seine Flags herein.
 */
class ReversePreferencesResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    fun resolve(
        sqliteWidthFlag: Int? = null,
        syntaxFlags: Map<DatabaseDialect, String?> = emptyMap(),
    ): ReversePreferences = ReversePreferences(
        sqliteAutoincrement = ReverseAutoincrementResolver(configPathFromCli, envLookup, defaultConfigPath)
            .resolve(sqliteWidthFlag),
        autoIncrementSyntax = ReverseAutoIncrementSyntaxResolver(configPathFromCli, envLookup, defaultConfigPath)
            .resolve(syntaxFlags),
    )
}
