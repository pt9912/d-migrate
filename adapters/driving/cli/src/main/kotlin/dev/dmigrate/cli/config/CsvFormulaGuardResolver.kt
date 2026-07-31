package dev.dmigrate.cli.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Löst die CSV-Formel-Injection-Guard-Präferenz auf (Audit-Follow-up #6,
 * CWE-1236): CLI-Flag `--csv-formula-guard` / `--no-csv-formula-guard` >
 * Config `export.csv.formula_guard` > konservativer Default `false`.
 *
 * Der Guard präfixt Text-Zellen aus einer untrusted Quell-DB, die mit einem
 * Formel-Zeichen (`=`/`+`/`-`/`@`/Tab/CR) beginnen, mit `'`, damit
 * Excel/LibreOffice sie beim Öffnen nicht als Formel ausführen. Er ist
 * **opt-in**, weil er den exportierten Wert verändert (kein byte-identischer
 * Round-Trip). Default `false` = treuer Dump; der Writer meldet betroffene
 * Spalten weiterhin per W203.
 *
 * Die Pfad-Auflösung folgt derselben `--config` > `D_MIGRATE_CONFIG` >
 * Default-Präzedenz wie die übrigen Config-Sektionen, ist aber — wie
 * [ReverseAutoincrementResolver] — **bewusst nachsichtig**: eine fehlende,
 * unparsbare oder `export.csv`-lose Config bedeutet „keine Präferenz deklariert"
 * und liefert den konservativen Default. Die Präferenz ist optional und darf
 * einen Export nie blockieren.
 */
class CsvFormulaGuardResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    /** CLI-Flag (true|false|null) überschreibt die Config; beide abwesend → `false`. */
    fun resolve(flag: Boolean?): Boolean = flag ?: (configGuard() ?: false)

    private fun configGuard(): Boolean? {
        val effective = EffectiveConfigPathResolver(
            configPathFromCli = configPathFromCli,
            envLookup = envLookup,
            defaultConfigPath = defaultConfigPath,
        ).resolve()
        // Bewusst nachsichtig (anders als die Connection-/Checkpoint-Resolver, die
        // bei fehlendem explizitem --config werfen): die Guard-Präferenz ist optional
        // und darf den Export nie blockieren — eine fehlende Config heißt schlicht
        // „keine Präferenz deklariert" → konservativer Default.
        if (!Files.isRegularFile(effective.path)) return null
        val parsed: Any? = try {
            val settings = LoadSettings.builder().build()
            Files.newInputStream(effective.path).use { input -> Load(settings).loadFromInputStream(input) }
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
            // Optionale Präferenz: eine kaputte Config schlägt über die anderen
            // Config-Resolver / die Connection-Auflösung auf, nicht hier.
            return null
        }
        val root = parsed as? Map<*, *> ?: return null
        // Spec-Taxonomie: CSV-Output-Optionen liegen unter `export.csv`
        // (neben delimiter/write_bom/… — connection-config-spec.md §Export).
        val export = root["export"] as? Map<*, *> ?: return null
        val csv = export["csv"] as? Map<*, *> ?: return null
        return csv["formula_guard"] as? Boolean
    }
}
