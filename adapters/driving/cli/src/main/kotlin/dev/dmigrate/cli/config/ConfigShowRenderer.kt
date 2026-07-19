package dev.dmigrate.cli.config

import dev.dmigrate.driver.connection.ConnectionSecretMasker
import java.nio.file.Path

/**
 * `config show` Phase 1 (config-cli-management-surface.md §3): rendert die **effektiv aufgelöste**
 * `.d-migrate.yaml` (nach CLI>ENV>Default-Pfadvertrag) als eingerückten Section-Baum — **keine Krypto,
 * kein voller Multi-Source-Merge** (der bleibt ein späterer Slice).
 *
 * Masking (verpflichtend, kein Secret im Klartext):
 * - **Sensibler Key-Name** (`password`/`passwd`/`secret`/`token`/`api_key`/`credentialRef`/
 *   `access_key`/`private_key`, case-/`_`-/`-`-insensitiv, **jede Tiefe**) → Wert komplett `***`
 *   (auch wenn er eine Map/Liste wäre) — kein Recursion in den Wert.
 * - Sonstige **String**-Werte → [ConnectionSecretMasker.mask] (URL-Authority-Passwörter +
 *   sensible Query-Params). `${VAR}`-Werte werden **nicht** aufgelöst; sie erscheinen literal,
 *   außer der Key ist sensibel.
 *
 * Rein/seiteneffektfrei (Env als Parameter) → deterministisch testbar.
 */
internal object ConfigShowRenderer {

    private val SENSITIVE_KEY_TOKENS = listOf(
        "password", "passwd", "secret", "token", "apikey", "credentialref", "accesskey", "privatekey",
    )

    /** Bevorzugte Reihenfolge bekannter Top-Level-Sections; unbekannte danach in Dateireihenfolge. */
    private val SECTION_ORDER = listOf(
        "database", "export", "import", "pipeline", "incremental",
        "ai", "i18n", "ddl", "documentation", "logging", "security",
    )

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class UnknownSection(val section: String, val available: List<String>) : Result
    }

    fun render(root: Map<*, *>?, path: Path, section: String?, envVars: Map<String, String>): Result {
        val sections = orderedTopLevelKeys(root)
        if (section != null && !sections.contains(section)) {
            return Result.UnknownSection(section, sections)
        }
        val sb = StringBuilder()
        sb.append(if (root == null) "# Keine Konfigurationsdatei ($path)\n" else "# Effektive Konfiguration: $path\n")
        val shown = if (section != null) listOf(section) else sections
        for (key in shown) {
            renderEntry(key, root?.get(key), indent = 0, sb = sb)
        }
        appendOverrides(envVars, sb)
        return Result.Ok(sb.toString())
    }

    private fun orderedTopLevelKeys(root: Map<*, *>?): List<String> {
        if (root == null) return emptyList()
        val keys = root.keys.mapNotNull { it?.toString() }
        val known = SECTION_ORDER.filter { keys.contains(it) }
        val rest = keys.filterNot { known.contains(it) }
        return known + rest
    }

    private fun renderEntry(key: String, value: Any?, indent: Int, sb: StringBuilder) {
        val pad = "  ".repeat(indent)
        when {
            isSensitiveKey(key) -> sb.append(pad).append(key).append(": ***\n")
            value is Map<*, *> -> {
                sb.append(pad).append(key).append(":\n")
                for ((k, v) in value) renderEntry(k?.toString() ?: "null", v, indent + 1, sb)
            }
            value is List<*> -> {
                sb.append(pad).append(key).append(":\n")
                for (item in value) renderListItem(item, indent + 1, sb)
            }
            else -> sb.append(pad).append(key).append(": ").append(renderScalar(value)).append('\n')
        }
    }

    private fun renderListItem(item: Any?, indent: Int, sb: StringBuilder) {
        val pad = "  ".repeat(indent)
        when (item) {
            is Map<*, *> -> {
                sb.append(pad).append("-\n")
                for ((k, v) in item) renderEntry(k?.toString() ?: "null", v, indent + 1, sb)
            }
            is List<*> -> {
                sb.append(pad).append("-\n")
                for (nested in item) renderListItem(nested, indent + 1, sb)
            }
            else -> sb.append(pad).append("- ").append(renderScalar(item)).append('\n')
        }
    }

    private fun renderScalar(value: Any?): String = when (value) {
        null -> "null"
        is String -> ConnectionSecretMasker.mask(value)
        else -> value.toString()
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().replace("_", "").replace("-", "")
        return SENSITIVE_KEY_TOKENS.any { normalized.contains(it) }
    }

    /**
     * Provenienz-Zeile: erkennbare `D_MIGRATE_*`-Runtime-Overrides. Nur die **Namen** (nie die Werte,
     * die ein Secret tragen könnten) — die effektive Datei oben ist das eigentliche Zielbild.
     */
    private fun appendOverrides(envVars: Map<String, String>, sb: StringBuilder) {
        val overrides = envVars.keys.filter { it.startsWith("D_MIGRATE_") }.sorted()
        if (overrides.isNotEmpty()) {
            sb.append("\n# Aktive D_MIGRATE_*-Runtime-Overrides: ").append(overrides.joinToString(", ")).append('\n')
        }
    }
}
