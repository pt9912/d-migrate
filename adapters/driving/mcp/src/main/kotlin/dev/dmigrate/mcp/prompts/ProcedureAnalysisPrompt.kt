package dev.dmigrate.mcp.prompts

import dev.dmigrate.mcp.protocol.PromptContent
import dev.dmigrate.mcp.protocol.PromptMessage

/**
 * LF-017 / LF-024 / LN-030 / LN-031 § 5.7 (G.7) — Pflichtprompt `procedure_analysis`.
 *
 * LF-017 / LF-024 / LN-030 / LN-031 Z. 862:
 * > Procedure-Analyse auf Basis von Schema-/Artefaktreferenzen
 * > Mindestargumente: `schemaRef` oder `artifactRef`,
 * >                   optional `procedureName`
 *
 * Der Prompt erzeugt **keine** Tool-Ausführung (LF-017 / LF-024 / LN-030 / LN-031); der
 * resultierende `text` referenziert die übergebenen Refs und
 * lädt das Modell ein, sie als Read-Quelle zu nutzen — die
 * tatsächliche Auflösung (z. B. `procedure_transform_plan`) ist
 * Sache des Caller-Agenten.
 */
internal object ProcedureAnalysisPrompt {

    const val NAME: String = "procedure_analysis"

    fun descriptor(): PromptDescriptor = PromptDescriptor(
        name = NAME,
        description = "Analyse a stored procedure from a schema or artifact reference. " +
            "Tool-step expectations: procedure_transform_plan once the analysis result motivates a transformation.",
        revision = "1",
        arguments = listOf(
            PromptArgumentSpec(
                name = "schemaRef",
                description = "Tenant-scoped schema reference. Mutually exclusive with artifactRef.",
                required = false,
                type = PromptArgumentType.RESOURCE_URI,
                allowedResourceKinds = setOf("schemas"),
            ),
            PromptArgumentSpec(
                name = "artifactRef",
                description = "Tenant-scoped artifact reference. Mutually exclusive with schemaRef.",
                required = false,
                type = PromptArgumentType.RESOURCE_URI,
                allowedResourceKinds = setOf("artifacts"),
            ),
            PromptArgumentSpec(
                name = "procedureName",
                description = "Procedure identifier. Required only when paired with schemaRef.",
                required = false,
                type = PromptArgumentType.STRING,
            ),
        ),
        expectedTools = listOf("procedure_transform_plan"),
        hygieneRules = listOf(
            "no inline procedure body",
            "no external URLs",
            "schemaRef + artifactRef are mutually exclusive",
        ),
        build = ::build,
    )

    private fun build(arguments: Map<String, String>): List<PromptMessage> {
        val schemaRef = arguments["schemaRef"]
        val artifactRef = arguments["artifactRef"]
        val procedureName = arguments["procedureName"]

        // LF-017 / LF-024 / LN-030 / LN-031-Akzeptanz: Caller hat genau eine Source-Variante
        // gegeben. Hygiene + Validierung haben das geprüft; hier nur
        // saubere Wiedergabe.
        // Newline-separierte key=value-Form, damit die Hygiene-
        // DMIGRATE_REF-Regex die Resource-URIs sauber vom umgebenden
        // Text trennen kann (Komma wäre Teil der Match-Klasse).
        val text = buildString {
            append("Task: analyze a stored procedure as preparation for a transformation plan.\n")
            if (schemaRef != null) append("schemaRef=").append(schemaRef).append('\n')
            if (artifactRef != null) append("artifactRef=").append(artifactRef).append('\n')
            if (procedureName != null) append("procedureName=").append(procedureName).append('\n')
            append("Constraint: do not invoke any tool; cite only the referenced resources.\n")
            append("Expected next step: call procedure_transform_plan with the same source ")
            append("and a targetDialect once the analysis is complete.")
        }
        return listOf(
            PromptMessage(
                role = "user",
                content = PromptContent(type = "text", text = text),
            ),
        )
    }
}
