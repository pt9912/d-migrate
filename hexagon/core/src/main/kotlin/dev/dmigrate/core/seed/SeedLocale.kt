package dev.dmigrate.core.seed

/**
 * Unterstützte Locales für `data seed` (P1: nur `en`/`de`, siehe
 * ImpPlan-1.3.0-cli-data-seed-p1.md AE-5). Unbekannte Werte liefern
 * `null` statt eines stillen Fallbacks — der Aufrufer entscheidet den
 * Exit-Code.
 */
enum class SeedLocale(private val flag: String, val words: List<String>, val emailDomains: List<String>) {
    EN(
        "en",
        listOf(
            "alpha", "bridge", "canyon", "delta", "ember", "forest", "glacier", "harbor",
            "island", "juniper", "kernel", "lagoon", "meadow", "nebula", "orchard", "prairie",
        ),
        listOf("example.com", "example.org", "example.net"),
    ),
    DE(
        "de",
        listOf(
            "amsel", "baum", "chrom", "distel", "erle", "fichte", "garten", "hafen",
            "insel", "jasmin", "kiesel", "lerche", "moor", "nebel", "ozean", "pfad",
        ),
        listOf("beispiel.de", "beispiel.example", "test.beispiel.de"),
    ),
    ;

    companion object {
        fun fromFlag(value: String): SeedLocale? = entries.firstOrNull { it.flag == value.lowercase() }
    }
}
