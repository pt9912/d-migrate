package dev.dmigrate.mcp.prompts

import dev.dmigrate.mcp.protocol.PromptArgumentDescriptor
import dev.dmigrate.mcp.protocol.PromptListEntry
import dev.dmigrate.mcp.protocol.PromptMessage

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — Server-seitige Prompt-Definition.
 *
 * LF-017 / LF-024 / LN-030 / LN-031 listet die Pflichtfelder:
 *
 * - `name` (stabil, snake_case)
 * - `description`
 * - `revision`
 * - JSON Schema 2020-12 für Argumente
 * - erlaubte Resource-Kinds
 * - erwartete Tool-Schritte
 * - Hygiene-Regeln
 * - kurze Prompt-Nachrichten
 *
 * Dieser Record bildet die Discovery- + Build-Form ab. Die
 * Argument-Validierung läuft separat über [PromptArgumentValidator]
 * — `arguments` hier nur zur Discovery-Projektion.
 *
 * @param name stabiler Wire-Identifikator (snake_case). LF-017 / LF-024 / LN-030 / LN-031
 *   definiert die drei Pflichtprompts (`procedure_analysis`,
 *   `procedure_transformation`, `testdata_planning`).
 * @param description kurze, agentenfreundliche Beschreibung. Wird
 *   1:1 in das `prompts/list`-Discovery-Result projiziert.
 * @param revision Versionsstempel (etwa `"1"`, `"v2"`). LF-017 / LF-024 / LN-030 / LN-031
 *   verlangt sie, damit Caller eine Drift erkennen — der Wert
 *   bleibt für die Lebensdauer einer Prompt-Definition stabil.
 * @param arguments Argumentliste für Discovery + Validation.
 *   Reihenfolge folgt der natürlichen Lesereihenfolge des Prompts.
 * @param expectedTools LF-017 / LF-024 / LN-030 / LN-031: erwartete Tool-Schritte. Rein
 *   informativ — die Prompt-Engine führt KEINE Tools aus
 *   (LF-017 / LF-024 / LN-030 / LN-031). Der Wert landet im `description`-Text und in
 *   internen Audit-Spuren.
 * @param hygieneRules kurze, scrubsichere Beschreibungen der
 *   Hygiene-Pflichten. Werden im Description-Text gespiegelt
 *   (LF-017 / LF-024 / LN-030 / LN-031).
 * @param build Funktion, die aus den (bereits validierten und
 *   hygienisierten) Argumenten die Prompt-Nachrichten erzeugt.
 *   Output muss ausschließlich `text`-Content sein (LF-017 / LF-024 / LN-030 / LN-031
 *   Z. 875-878).
 */
data class PromptDescriptor(
    val name: String,
    val description: String,
    val revision: String,
    val arguments: List<PromptArgumentSpec>,
    val expectedTools: List<String>,
    val hygieneRules: List<String>,
    val build: (Map<String, String>) -> List<PromptMessage>,
) {
    init {
        require(name.isNotBlank()) { "prompt name must not be blank" }
        require(description.isNotBlank()) { "prompt description must not be blank" }
        require(revision.isNotBlank()) { "prompt revision must not be blank" }
    }

    /**
     * Discovery-Projektion für `prompts/list`. LF-017 / LF-024 / LN-030 / LN-031 Z. 866-877
     * verlangt `name` + optional `description` + `arguments`. Die
     * detaillierten Hygiene-/Tool-Felder bleiben serverseitig.
     */
    fun toListEntry(): PromptListEntry = PromptListEntry(
        name = name,
        description = description,
        arguments = arguments.map { it.toDescriptor() },
    )
}

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — Argument-Spec eines Prompts. Beschreibt
 * Form, Pflicht-Status und (für Refs) erlaubte ResourceKinds.
 *
 * Argument-Werte sind im MCP-Wire immer Strings (siehe
 * [dev.dmigrate.mcp.protocol.PromptsGetParams.arguments]). Der
 * [PromptArgumentValidator] mappt sie gegen [type]:
 *
 * - `STRING` — beliebiger String, optional `pattern`-Match.
 * - `RESOURCE_URI` — muss ein gültiger
 *   `dmigrate://tenants/{tenantId}/{kind}/{id}`-URI sein,
 *   `kind` ∈ [allowedResourceKinds].
 * - `ENUM` — String-Wert aus [allowedValues].
 */
data class PromptArgumentSpec(
    val name: String,
    val description: String,
    val required: Boolean,
    val type: PromptArgumentType,
    val pattern: String? = null,
    val allowedValues: Set<String> = emptySet(),
    val allowedResourceKinds: Set<String> = emptySet(),
) {
    init {
        require(name.isNotBlank()) { "argument name must not be blank" }
        require(description.isNotBlank()) { "argument description must not be blank" }
        if (type == PromptArgumentType.ENUM) {
            require(allowedValues.isNotEmpty()) {
                "ENUM argument '$name' must declare allowedValues"
            }
        }
        if (type == PromptArgumentType.RESOURCE_URI) {
            require(allowedResourceKinds.isNotEmpty()) {
                "RESOURCE_URI argument '$name' must declare allowedResourceKinds"
            }
        }
    }

    fun toDescriptor(): PromptArgumentDescriptor = PromptArgumentDescriptor(
        name = name,
        description = description,
        required = required,
    )
}

enum class PromptArgumentType {
    STRING,
    RESOURCE_URI,
    ENUM,
}
