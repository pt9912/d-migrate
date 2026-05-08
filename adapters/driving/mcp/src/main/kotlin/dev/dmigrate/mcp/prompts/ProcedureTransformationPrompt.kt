package dev.dmigrate.mcp.prompts

import dev.dmigrate.mcp.protocol.PromptContent
import dev.dmigrate.mcp.protocol.PromptMessage

/**
 * LF-017 / LF-024 / LN-030 / LN-031 § 5.7 (G.7) — Pflichtprompt `procedure_transformation`.
 *
 * LF-017 / LF-024 / LN-030 / LN-031 Z. 863:
 * > Procedure-Transformation mit explizitem Policy-Hinweis
 * > Mindestargumente: planRef oder planArtifactId bzw. artifactRef
 * >   nur mit wireArtifactKind=procedure-transform-plan, targetDialect
 *
 * LF-012 / LN-011 / LN-017 / LN-027 Z. 794-799: Execute-Pfad nimmt KEINE eigenen Source-Refs;
 * der Prompt referenziert nur den Plan.
 */
internal object ProcedureTransformationPrompt {

    const val NAME: String = "procedure_transformation"

    private val ALLOWED_DIALECTS: Set<String> = setOf("POSTGRESQL", "MYSQL", "SQLITE")

    fun descriptor(): PromptDescriptor = PromptDescriptor(
        name = NAME,
        description = "Drive a procedure transformation with an explicit policy reminder. " +
            "Tool-step expectation: procedure_transform_execute against an approved plan.",
        revision = "1",
        arguments = listOf(
            PromptArgumentSpec(
                name = "planRef",
                description = "Tenant-scoped plan-artifact reference. " +
                    "Mutually exclusive with planArtifactId.",
                required = false,
                type = PromptArgumentType.RESOURCE_URI,
                allowedResourceKinds = setOf("artifacts"),
            ),
            PromptArgumentSpec(
                name = "planArtifactId",
                description = "Bare plan-artifact id. Mutually exclusive with planRef.",
                required = false,
                type = PromptArgumentType.STRING,
            ),
            PromptArgumentSpec(
                name = "targetDialect",
                description = "Target dialect for the transformation. One of POSTGRESQL, MYSQL, SQLITE.",
                required = true,
                type = PromptArgumentType.ENUM,
                allowedValues = ALLOWED_DIALECTS,
            ),
        ),
        expectedTools = listOf("procedure_transform_execute"),
        hygieneRules = listOf(
            "no source-refs in the prompt — they live in the plan provenance (LF-012 / LN-011 / LN-017 / LN-027)",
            "no inline plan content",
            "no executable target code",
        ),
        build = ::build,
    )

    private fun build(arguments: Map<String, String>): List<PromptMessage> {
        val planRef = arguments["planRef"]
        val planArtifactId = arguments["planArtifactId"]
        val targetDialect = arguments["targetDialect"]
            ?: error("targetDialect must have been validated as required")

        val planLine = when {
            planRef != null -> "planRef=$planRef"
            planArtifactId != null -> "planArtifactId=$planArtifactId"
            else -> "(no plan source — argument validation should have rejected this)"
        }

        val text = buildString {
            append("Task: drive a procedure transformation against an approved plan.\n")
            append("Vertrag: ").append(planLine).append('\n')
            append("Target dialect: ").append(targetDialect).append('\n')
            append("Policy reminder: this transformation requires explicit approval; the plan ")
            append("provenance dictates the source-refs — do not introduce new sources.\n")
            append("Expected next step: call procedure_transform_execute with the same plan and ")
            append("targetDialect once the policy challenge is satisfied.")
        }
        return listOf(
            PromptMessage(
                role = "user",
                content = PromptContent(type = "text", text = text),
            ),
        )
    }
}
