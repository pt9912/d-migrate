package dev.dmigrate.format.yaml

import com.fasterxml.jackson.dataformat.yaml.util.StringQuotingChecker
import org.yaml.snakeyaml.nodes.NodeId
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.resolver.Resolver

/**
 * Quoting-Checker, der unter `MINIMIZE_QUOTES` genau die String-**Werte** quotet,
 * die die YAML-Lese-Seite sonst zu einem Nicht-String umdeuten würde.
 *
 * Jacksons [StringQuotingChecker.Default] lässt unter `MINIMIZE_QUOTES` Scalars
 * wie `4.`, `9_`, `yes`, `on`, `~` oder `2024-01-01` unquotiert; SnakeYAMLs
 * YAML-1.1-Implicit-Resolver liest sie dann als Float/Int/Bool/Null/Timestamp,
 * und die String-Identität geht verloren (z. B. ein Enum-Label `yes` → `true`).
 * Statt die Implicit-Type-Grammatik nachzubauen, delegiert dieser Checker die
 * Entscheidung an **denselben** [Resolver], den die Lese-Seite verwendet: löst er
 * einen Scalar zu einem anderen Tag als [Tag.STR] auf, muss er gequotet werden.
 * Damit sind Schreib- und Lese-Seite garantiert symmetrisch, und harmlose Strings
 * bleiben unquotiert (sauberer Output).
 *
 * Nur Werte werden abgesichert: Map-Keys (Tabellen-/Spalten-/Feldnamen) liest
 * Jackson stets als String-Feldnamen zurück, sind also unkritisch.
 *
 * LN-046 / `docs/planning/done/yaml-codec-arbitrary-string-roundtrip.md`.
 */
internal class YamlImplicitAwareQuotingChecker : StringQuotingChecker.Default() {

    private val resolver = Resolver()

    override fun needToQuoteValue(value: String): Boolean =
        super.needToQuoteValue(value) || resolver.resolve(NodeId.scalar, value, true) != Tag.STR
}
