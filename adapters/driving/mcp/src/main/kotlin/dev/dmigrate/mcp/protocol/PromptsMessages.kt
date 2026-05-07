package dev.dmigrate.mcp.protocol

/**
 * Phase G § 5.7 + § 6 G.7 — MCP `prompts/list` und `prompts/get`
 * Wire-Shapes per 2025-11-25-Spezifikation.
 *
 * - [PromptsListParams] / [PromptsListResult] — Discovery aller
 *   registrierten Prompts. Cursor analog zu `resources/list`.
 * - [PromptsGetParams] / [PromptsGetResult] — Argumentvalidierte
 *   Prompt-Nachrichten. Plan §4.5 verbindlich: Prompts sind
 *   Vorlagen, keine versteckten Tool-Ausführungen — die
 *   Antwort enthält nur Text/Resource-Refs, kein Tool-Effekt.
 */
data class PromptsListParams(
    val cursor: String? = null,
)

data class PromptsListResult(
    val prompts: List<PromptListEntry>,
    val nextCursor: String? = null,
)

/**
 * MCP-`Prompt`-Discovery-Form. `arguments` ist optional, weil ein
 * Prompt keine Argumente brauchen kann (selbst wenn alle drei
 * Phase-G-Pflichtprompts welche haben).
 */
data class PromptListEntry(
    val name: String,
    val description: String,
    val arguments: List<PromptArgumentDescriptor> = emptyList(),
)

/**
 * MCP-Prompt-Argument-Beschreibung. `required` defaultet auf
 * `false`, weil die MCP-Spec optionale Argumente als Default
 * vorsieht. Plan §5.7 verlangt strukturiertes Schema; dieser
 * Wire-Form-DTO bleibt simpel — die volle Argumentvalidierung
 * läuft im [dev.dmigrate.mcp.prompts.PromptArgumentValidator].
 */
data class PromptArgumentDescriptor(
    val name: String,
    val description: String,
    val required: Boolean = false,
)

data class PromptsGetParams(
    val name: String,
    val arguments: Map<String, String>? = null,
)

data class PromptsGetResult(
    val description: String,
    val messages: List<PromptMessage>,
)

/**
 * MCP-Prompt-Message. Phase G unterstützt nur `text`-Content
 * (Plan §5.7: "kurze Prompt-Nachrichten"); `image`/`resource`-
 * Content folgt mit MCP-Sampling-/Streaming-Erweiterungen außerhalb
 * von 0.9.6.
 */
data class PromptMessage(
    val role: String,
    val content: PromptContent,
)

data class PromptContent(
    val type: String,
    val text: String,
)
