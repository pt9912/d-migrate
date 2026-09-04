package dev.dmigrate.cli.commands

import dev.dmigrate.core.seed.ColumnRule
import dev.dmigrate.core.seed.SeedRuleEntry
import dev.dmigrate.core.seed.SeedRuleSet
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lädt `--rules`-Einträge für `d-migrate data seed` P2 aus einer YAML-Datei
 * (ImpPlan-1.3.0-cli-data-seed-p2.md AP4). Nutzt `snakeyaml-engine` (bereits
 * `cli`-Abhängigkeit für `.d-migrate.yaml`, s. `EffectiveConfigLoader.kt`)
 * statt eines neuen Jackson-Mappers: `adapters:driving:cli` hat
 * `jackson-databind` nicht auf dem Klassenpfad (nur `adapters:driving:mcp`,
 * dort `implementation`-Scope, also nicht transitiv, und eine neue
 * Runtime-Dependency braucht ihrerseits einen CVE-/Lizenz-Check). JSON ist
 * eine YAML-Teilmenge, ein separater JSON-Codepfad ist deshalb nicht nötig.
 *
 * Ausschließlich `error(...)` (nie `require(...)`) für Validierungsfehler,
 * damit `DataSeedWiring` genau einen Exception-Typ (`IllegalStateException`)
 * fängt und auf Exit 7 mapped -- Konvention analog
 * `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/registry/PolicyRuleFileLoader.kt`.
 *
 * Format:
 * ```yaml
 * rules:
 *   - table: users            # optional, weggelassen = Wildcard
 *     column: email
 *     values: ["a@x.com", "b@x.com"]
 *     weights: [0.7, 0.3]     # optional, Default Gleichverteilung
 *   - column: age
 *     range:
 *       min: 18
 *       max: 65
 *   - column: handle
 *     template: "user-{digits:6}"
 * ```
 * Genau eine von `values`/`range`/`template` je Regel. Regeln werden in
 * Dateireihenfolge geprüft (erste passende gewinnt, AE-2) -- tabellen-
 * spezifische Regeln (`table` gesetzt) gehören deshalb VOR Wildcard-Regeln
 * (`table` weggelassen) für denselben Spaltennamen, sonst schattiert die
 * Wildcard-Regel die spezifische vollständig.
 *
 * Template-Token-Syntax (`{word}`/`{digits:N}`/`{uuid}`) wird hier bereits
 * geprüft (nicht erst beim Rendern) -- AE-4-Review-Ergänzung: schema-
 * unabhängig prüfbar, gehört an die Lade-Zeit, nicht an die Anwendungs-Zeit.
 *
 * @throws IllegalStateException bei fehlender/unlesbarer Datei, kaputtem
 *   YAML, fehlendem `column`, keiner oder mehr als einer Strategie je
 *   Regel, `values`/`weights`-Längen-Mismatch, ungültigem `range`
 *   (`min > max`) oder fehlerhafter Template-Token-Syntax.
 */
fun loadSeedRules(path: Path): SeedRuleSet {
    if (!Files.isReadable(path)) {
        error("rules file is not readable: $path")
    }
    val parsed: Any? = try {
        Files.newInputStream(path).use { input ->
            Load(LoadSettings.builder().build()).loadFromInputStream(input)
        }
    } catch (cause: Throwable) {
        error("failed to parse rules file $path: ${cause.message ?: cause::class.simpleName}")
    }
    val root = parsed as? Map<*, *> ?: error("rules file $path: top-level YAML must be a mapping")
    val rulesRaw = root["rules"] ?: error("rules file $path must contain an array field 'rules'")
    val rulesList = rulesRaw as? List<*> ?: error("rules file $path: field 'rules' must be an array")
    return SeedRuleSet(rulesList.map { toSeedRuleEntry(it, path) })
}

private fun toSeedRuleEntry(raw: Any?, path: Path): SeedRuleEntry {
    val node = raw as? Map<*, *> ?: error("rules file $path: each 'rules' entry must be a mapping")
    val column = node.requiredText("column", path)
    val table = node.optionalText("table", path)
    return SeedRuleEntry(table = table, column = column, rule = toColumnRule(node, column, path))
}

private fun toColumnRule(node: Map<*, *>, column: String, path: Path): ColumnRule {
    val strategies = listOf("values", "range", "template").filter { node.containsKey(it) }
    if (strategies.size != 1) {
        error(
            "rules file $path: seed rule for column '$column' must have exactly one of " +
                "'values', 'range', 'template' " +
                "(found: ${if (strategies.isEmpty()) "none" else strategies.joinToString(", ")})",
        )
    }
    return when (strategies.single()) {
        "values" -> toValuesRule(node, column, path)
        "range" -> toRangeRule(node, column, path)
        else -> toTemplateRule(node, column, path)
    }
}

private fun toValuesRule(node: Map<*, *>, column: String, path: Path): ColumnRule.Values {
    val valuesRaw = node["values"] as? List<*>
    val values = valuesRaw?.map { it.toRuleValue(column, path) } ?: emptyList()
    if (values.isEmpty()) {
        error("rules file $path: seed rule for column '$column' has 'values' but it is not a non-empty array")
    }
    val weightsRaw = node["weights"]
    val weights = weightsRaw?.let {
        val list = it as? List<*>
            ?: error("rules file $path: seed rule for column '$column' has 'weights' but it is not an array")
        list.map { w -> w.toRuleDouble(column, path) }
    }
    if (weights != null && weights.size != values.size) {
        error(
            "rules file $path: seed rule for column '$column' has a values/weights length mismatch: " +
                "${values.size} value(s) vs ${weights.size} weight(s)",
        )
    }
    return ColumnRule.Values(values, weights)
}

private fun toRangeRule(node: Map<*, *>, column: String, path: Path): ColumnRule.Range {
    val rangeRaw = node["range"] as? Map<*, *>
        ?: error("rules file $path: seed rule for column '$column' has 'range' but it is not a mapping")
    val min = rangeRaw["min"].toRuleDoubleOrNull()
        ?: error("rules file $path: seed rule for column '$column' range is missing a numeric 'min'")
    val max = rangeRaw["max"].toRuleDoubleOrNull()
        ?: error("rules file $path: seed rule for column '$column' range is missing a numeric 'max'")
    if (min > max) error("rules file $path: seed rule for column '$column' has range min ($min) > max ($max)")
    return ColumnRule.Range(min, max)
}

private fun toTemplateRule(node: Map<*, *>, column: String, path: Path): ColumnRule.Template {
    val pattern = node.requiredText("template", path)
    val rule = ColumnRule.Template(pattern)
    try {
        rule.segments
    } catch (e: IllegalArgumentException) {
        error("rules file $path: seed rule for column '$column' has an invalid template: ${e.message}")
    }
    return rule
}

/** Normalisiert Int→Long/Float→Double (matcht die "Integer-Familie -> Long"-Konvention aus P1). */
private fun Any?.toRuleValue(column: String, path: Path): Any? = when (this) {
    null -> null
    is Int -> toLong()
    is Float -> toDouble()
    is String, is Boolean, is Long, is Double -> this
    else -> error("rules file $path: seed rule for column '$column' has an unsupported value type: $this")
}

private fun Any?.toRuleDouble(column: String, path: Path): Double = when (this) {
    is Number -> toDouble()
    else -> error("rules file $path: seed rule for column '$column' weight must be numeric, got: $this")
}

private fun Any?.toRuleDoubleOrNull(): Double? = (this as? Number)?.toDouble()

private fun Map<*, *>.requiredText(field: String, path: Path): String {
    val value = this[field] ?: error("rules file $path: seed rule missing '$field'")
    return value as? String ?: error("rules file $path: seed rule field '$field' must be a string")
}

private fun Map<*, *>.optionalText(field: String, path: Path): String? {
    val value = this[field] ?: return null
    return value as? String ?: error("rules file $path: seed rule field '$field' must be a string")
}
