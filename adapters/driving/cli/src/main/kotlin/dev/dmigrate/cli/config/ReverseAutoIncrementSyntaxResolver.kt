package dev.dmigrate.cli.config

import dev.dmigrate.driver.AutoIncrementSyntaxReverse
import dev.dmigrate.driver.DatabaseDialect
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loest die Praeferenz `serial`/`identity` fuer Auto-Increment-Spalten auf
 * (`spec/dialect-preference-mechanism.md`, Lese-Mehrdeutigkeiten): je Dialekt
 * CLI-Flag > `reverse.<dialekt>.autoincrement_syntax` > Default `serial`.
 *
 * Nur MySQL (`AUTO_INCREMENT` auf `bigint`) und SQLite (`AUTOINCREMENT` unter
 * der 64-Bit-Breite) schreiben eine solche Spalte als `generation: identity`;
 * nur fuer sie gibt es den Schluessel. Ein nicht erkannter Wert in der
 * Konfiguration gilt wie beim Breiten-Schluessel als „nicht erklaert"
 * ([ReverseConfigBlock]); das Flag prueft Clikt selbst (Exit 2).
 */
class ReverseAutoIncrementSyntaxResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    /**
     * @param flags der Wert des Flags je Dialekt (`serial`, `identity` oder
     *   `null`, wenn nicht gesetzt)
     * @return die erklaerten Praeferenzen; ein Dialekt ohne Eintrag liest den
     *   Default
     */
    fun resolve(flags: Map<DatabaseDialect, String?> = emptyMap()): Map<DatabaseDialect, AutoIncrementSyntaxReverse> {
        val block = ReverseConfigBlock(configPathFromCli, envLookup, defaultConfigPath)
        return DIALECTS.mapNotNull { dialect ->
            val declared = flags[dialect]
                ?: block.dialect(dialect.name.lowercase())?.get(CONFIG_KEY) as? String
            parse(declared)?.let { dialect to it }
        }.toMap()
    }

    companion object {
        /** Der Schluessel unter `reverse.mysql` bzw. `reverse.sqlite`. */
        const val CONFIG_KEY: String = "autoincrement_syntax"

        /** Die Werte des Flags und des Schluessels. */
        val VALUES: List<String> = listOf("serial", "identity")

        private val DIALECTS = listOf(DatabaseDialect.MYSQL, DatabaseDialect.SQLITE)

        private fun parse(value: String?): AutoIncrementSyntaxReverse? = when (value?.trim()?.lowercase()) {
            "serial" -> AutoIncrementSyntaxReverse.SERIAL
            "identity" -> AutoIncrementSyntaxReverse.IDENTITY
            else -> null
        }
    }
}
