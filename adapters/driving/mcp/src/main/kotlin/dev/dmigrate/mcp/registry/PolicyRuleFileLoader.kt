package dev.dmigrate.mcp.registry

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.policy.PolicyRule
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lädt [PolicyRule]-Einträge aus einer YAML-/JSON-Datei
 * (`mcp serve --policy-file`, ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md
 * Slice A). Wird **einmal beim Start** aufgerufen (AE-A1) — bewusst kein
 * Hot-Reload wie [FileBackedApprovalGrantStore], weil Policy-Regeln reine
 * Betreiber-Konfiguration sind, nicht live entstehende Grants.
 *
 * Format:
 * ```yaml
 * rules:
 *   - tenantId: acme            # optional, weggelassen = Wildcard
 *     toolName: schema_reverse_start
 *     effect: allow
 *   - toolName: data_import_start
 *     effect: challenge
 *     requiredScopes: [dmigrate:writer]
 *     reasons: ["writes require approval"]
 *   - effect: deny
 *     reasonCode: policy:blocked-by-operator
 * ```
 *
 * Jede Validierungsverletzung wirft ausschließlich [IllegalStateException]
 * (nie [IllegalArgumentException]) — der Aufrufer
 * (`McpServeWiring.loadPolicyRulesOrExit`, `adapters/driving/cli`) fängt
 * genau diesen Typ und mappt auf `McpServeExit(2)`.
 *
 * @throws IllegalStateException bei fehlender/unlesbarer Datei, kaputtem
 *   YAML/JSON, unbekanntem `effect`-Wert oder fehlenden effekt-spezifischen
 *   Pflichtfeldern.
 */
fun loadPolicyRules(path: Path): List<PolicyRule> {
    if (!Files.isReadable(path)) {
        error("policy file is not readable: $path")
    }
    val mapper = mapperFor(path)
    val root = mapper.readTree(path.toFile()) ?: error("policy file is empty: $path")
    val rules = root.get("rules") ?: error("policy file must contain an array field 'rules'")
    if (!rules.isArray) error("policy file field 'rules' must be an array")
    return rules.map(::toPolicyRule)
}

private fun toPolicyRule(node: JsonNode): PolicyRule =
    PolicyRule(
        tenantId = node.optionalText("tenantId")?.let(::TenantId),
        toolName = node.optionalText("toolName"),
        callerId = node.optionalText("callerId")?.let(::PrincipalId),
        effect = toPolicyEffect(node),
    )

private fun toPolicyEffect(node: JsonNode): PolicyEffect {
    val effect = node.requiredText("effect")
    return when (effect) {
        "allow" -> PolicyEffect.Allow
        "challenge" -> PolicyEffect.Challenge(
            requiredScopes = node.requiredStringArray("requiredScopes").toSet(),
            reasons = node.optionalStringArray("reasons"),
        )
        "deny" -> PolicyEffect.Deny(reasonCode = node.requiredText("reasonCode"))
        else -> error("policy rule has unknown effect '$effect' (expected: allow, challenge, deny)")
    }
}

private fun mapperFor(path: Path): ObjectMapper {
    val lower = path.fileName.toString().lowercase()
    return if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
        ObjectMapper(YAMLFactory())
    } else {
        ObjectMapper()
    }
}

private fun JsonNode.requiredText(field: String): String {
    val node = get(field) ?: error("policy rule missing '$field'")
    if (!node.isTextual) error("policy rule field '$field' must be a string")
    return node.asText()
}

private fun JsonNode.optionalText(field: String): String? {
    val node = get(field) ?: return null
    if (!node.isTextual) error("policy rule field '$field' must be a string")
    return node.asText()
}

private fun JsonNode.requiredStringArray(field: String): List<String> {
    val node = get(field) ?: error("policy rule with effect 'challenge' missing '$field'")
    if (!node.isArray) error("policy rule field '$field' must be an array of strings")
    return node.map { it.asText() }
}

private fun JsonNode.optionalStringArray(field: String): List<String> {
    val node = get(field) ?: return emptyList()
    if (!node.isArray) error("policy rule field '$field' must be an array of strings")
    return node.map { it.asText() }
}
