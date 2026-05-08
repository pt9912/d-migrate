package dev.dmigrate.mcp.prompts

import dev.dmigrate.mcp.protocol.PromptContent
import dev.dmigrate.mcp.protocol.PromptMessage

/**
 * LF-017 / LF-024 / LN-030 / LN-031 § 5.7 (G.7) — Pflichtprompt `testdata_planning`.
 *
 * LF-017 / LF-024 / LN-030 / LN-031 Z. 864:
 * > Testdatenplanung auf Basis von Schema, Regeln und Profil-Summaries
 * > Mindestargumente: schemaRef, targetDialect, optional profileRef,
 * >                   optionale rules
 */
internal object TestdataPlanningPrompt {

    const val NAME: String = "testdata_planning"

    private val ALLOWED_DIALECTS: Set<String> = setOf("POSTGRESQL", "MYSQL", "SQLITE")

    fun descriptor(): PromptDescriptor = PromptDescriptor(
        name = NAME,
        description = "Plan synthetic test data from a schema, optional profiling summary and rules. " +
            "Tool-step expectation: testdata_plan once the plan shape is settled.",
        revision = "1",
        arguments = listOf(
            PromptArgumentSpec(
                name = "schemaRef",
                description = "Tenant-scoped schema reference.",
                required = true,
                type = PromptArgumentType.RESOURCE_URI,
                allowedResourceKinds = setOf("schemas"),
            ),
            PromptArgumentSpec(
                name = "targetDialect",
                description = "Target dialect for the testdata plan. One of POSTGRESQL, MYSQL, SQLITE.",
                required = true,
                type = PromptArgumentType.ENUM,
                allowedValues = ALLOWED_DIALECTS,
            ),
            PromptArgumentSpec(
                name = "profileRef",
                description = "Optional tenant-scoped profile reference for distribution hints.",
                required = false,
                type = PromptArgumentType.RESOURCE_URI,
                allowedResourceKinds = setOf("profiles"),
            ),
            PromptArgumentSpec(
                name = "rulesSummary",
                description = "Optional short summary of structured rules. " +
                    "MUST be summary, not raw SQL or production data.",
                required = false,
                type = PromptArgumentType.STRING,
            ),
        ),
        expectedTools = listOf("testdata_plan"),
        hygieneRules = listOf(
            "no real production data in rules",
            "no inline profile content (use profileRef)",
            "no DML or DDL fragments",
        ),
        build = ::build,
    )

    private fun build(arguments: Map<String, String>): List<PromptMessage> {
        val schemaRef = arguments["schemaRef"]
            ?: error("schemaRef must have been validated as required")
        val targetDialect = arguments["targetDialect"]
            ?: error("targetDialect must have been validated as required")
        val profileRef = arguments["profileRef"]
        val rulesSummary = arguments["rulesSummary"]

        val text = buildString {
            append("Task: plan synthetic test data for a schema.\n")
            append("Schema: ").append(schemaRef).append('\n')
            append("Target dialect: ").append(targetDialect).append('\n')
            if (profileRef != null) {
                append("Profile reference: ").append(profileRef).append('\n')
            }
            if (rulesSummary != null) {
                append("Rule summary: ").append(rulesSummary).append('\n')
            }
            append("Constraint: produce a plan only — no DML, no DDL, no production data.\n")
            append("Expected next step: call testdata_plan with the same schemaRef + targetDialect ")
            append("(plus optional profileRef + structured rules) to materialize the plan artifact.")
        }
        return listOf(
            PromptMessage(
                role = "user",
                content = PromptContent(type = "text", text = text),
            ),
        )
    }
}
