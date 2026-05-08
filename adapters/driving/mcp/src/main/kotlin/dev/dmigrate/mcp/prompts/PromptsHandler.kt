package dev.dmigrate.mcp.prompts

import dev.dmigrate.mcp.protocol.PromptsGetParams
import dev.dmigrate.mcp.protocol.PromptsGetResult
import dev.dmigrate.mcp.protocol.PromptsListParams
import dev.dmigrate.mcp.protocol.PromptsListResult
import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.application.audit.prompt.PromptHygieneRequest
import dev.dmigrate.server.application.audit.prompt.PromptHygieneResult
import dev.dmigrate.server.application.audit.prompt.PromptHygieneService
import dev.dmigrate.server.core.principal.PrincipalContext

/**
 * Phase G § 6 G.7 — Server-seitiger Handler für die MCP-
 * Prompt-Methoden.
 *
 * Pipeline für `prompts/get` (Plan §6 G.7 + §4.5):
 *
 * 1. Look-up via [PromptRegistry.find] → unbekannt → [PromptsLookupOutcome.NotFound].
 * 2. Argumentvalidierung über [PromptArgumentValidator] →
 *    invalid → [PromptsLookupOutcome.InvalidArguments].
 * 3. Prompt-Build (deterministisch aus den validierten Args).
 * 4. Hygiene-Check über die assemblierte Prompt-Nachricht
 *    (Plan §6 G.4 + §7.4) — hier laufen Argumente durch
 *    `payloadJson`, der zusammengebaute Text durch `promptText`.
 * 5. Rückgabe als [PromptsGetResult] mit Tool-Step-Hinweis im
 *    `description`.
 *
 * `prompts/list` ist ein dünner Discovery-Wrapper.
 *
 * Plan §4.5 Akzeptanz: keine versteckte Tool-Ausführung. Dieser
 * Handler hat keinen Zugriff auf `ToolRegistry` o. Ä. — strukturell
 * unmöglich, einen Tool zu invoken.
 */
class PromptsHandler(
    private val registry: PromptRegistry,
    private val hygieneService: PromptHygieneService,
) {

    fun list(@Suppress("UNUSED_PARAMETER") params: PromptsListParams?): PromptsListResult =
        PromptsListResult(prompts = registry.list())

    fun get(params: PromptsGetParams, principal: PrincipalContext): PromptsLookupOutcome {
        val descriptor = registry.find(params.name)
            ?: return PromptsLookupOutcome.NotFound(params.name)

        val tenantId = principal.effectiveTenantId
        val args = params.arguments ?: emptyMap()
        val validation = PromptArgumentValidator.validate(descriptor, args, tenantId)
        if (validation is PromptArgumentValidationResult.Invalid) {
            return PromptsLookupOutcome.InvalidArguments(validation.violations)
        }
        val validated = (validation as PromptArgumentValidationResult.Valid).arguments

        val messages = descriptor.build(validated)

        // Plan §6 G.4 + §7.4: Hygiene über jede gebaute
        // Prompt-Nachricht. Wir leiten den Promptmessage-Text als
        // promptText durch und die Argumente als JSON-Payload, damit
        // sowohl direkte als auch maskierte Secret-Pattern erfasst
        // werden.
        for (message in messages) {
            val hygiene = hygieneService.sanitize(
                PromptHygieneRequest(
                    toolName = "prompts/get:${descriptor.name}",
                    tenantId = tenantId,
                    principalId = principal.principalId,
                    allowedResourceRefs = descriptor.arguments
                        .filter { it.type == PromptArgumentType.RESOURCE_URI }
                        .mapNotNull { spec ->
                            validated[spec.name]?.let { value ->
                                tryParseUri(value)
                            }
                        },
                    payloadJson = canonicalArgumentsJson(validated),
                    promptText = message.content.text,
                    providerId = AiProviderId.NOOP,
                    maxPromptBytes = MAX_PROMPT_BYTES,
                    maxPayloadBytes = MAX_PAYLOAD_BYTES,
                ),
            )
            if (hygiene is PromptHygieneResult.Block) {
                return PromptsLookupOutcome.HygieneBlocked(hygiene.publicMessage)
            }
        }

        return PromptsLookupOutcome.Found(
            PromptsGetResult(
                description = describePromptOutcome(descriptor),
                messages = messages,
            ),
        )
    }

    private fun tryParseUri(raw: String): dev.dmigrate.server.core.resource.ServerResourceUri? {
        val parsed = dev.dmigrate.server.core.resource.ServerResourceUri.parse(raw)
        return when (parsed) {
            is dev.dmigrate.server.core.resource.ResourceUriParseResult.Valid -> parsed.uri
            is dev.dmigrate.server.core.resource.ResourceUriParseResult.Invalid -> null
        }
    }

    private fun describePromptOutcome(descriptor: PromptDescriptor): String =
        "${descriptor.description} (revision=${descriptor.revision}; " +
            "expectedTools=${descriptor.expectedTools.joinToString(",")})"

    private fun canonicalArgumentsJson(args: Map<String, String>): String {
        // Lex-sortiert + JSON-escape für deterministische Form.
        val sorted = args.toSortedMap()
        return buildString {
            append('{')
            val it = sorted.entries.iterator()
            while (it.hasNext()) {
                val (k, v) = it.next()
                append('"').append(k).append('"').append(':')
                append('"').append(escapeJson(v)).append('"')
                if (it.hasNext()) append(',')
            }
            append('}')
        }
    }

    private fun escapeJson(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private companion object {
        const val MAX_PROMPT_BYTES: Int = 32_768
        const val MAX_PAYLOAD_BYTES: Int = 16_384
    }
}

sealed interface PromptsLookupOutcome {

    data class Found(val result: PromptsGetResult) : PromptsLookupOutcome

    data class NotFound(val name: String) : PromptsLookupOutcome

    data class InvalidArguments(val violations: List<PromptArgumentViolation>) : PromptsLookupOutcome

    data class HygieneBlocked(val publicMessage: String) : PromptsLookupOutcome
}
