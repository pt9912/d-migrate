package dev.dmigrate.mcp.registry

import com.google.gson.JsonElement
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — Eingabe-Parsing für `testdata_execute`.
 *
 * Aus dem Handler ausgelagert, weil die LF-017 / LF-024 / LN-030 / LN-031-Validierungs-Regeln
 * (Planquelle exclusiv, Zielbindung via `targetTable` oder `tables`+
 * `bundleFormat`, output-Format-Default) sonst die LargeClass-/
 * TooManyFunctions-Schwellen sprengen.
 *
 * Pure: keine I/O, keine Provider-Calls. Wirft `ValidationErrorException`
 * bei Form-Verletzungen — Mapper im DefaultErrorMapper übersetzt das
 * deterministisch in `VALIDATION_ERROR`.
 */
internal object TestdataExecuteInputParser {

    fun parse(arguments: JsonElement?): TestdataExecuteParsedArgs {
        val obj = JsonArgs.requireObject(arguments)
        val approvalKey = obj.requireString("approvalKey")
        val targetDialect = obj.requireString("targetDialect")
        val approvalToken = obj.optString("approvalToken")
        val planRef = obj.optString("planRef")
        val planArtifactId = obj.optString("planArtifactId")
        val outputFormat = obj.optString("outputFormat")?.lowercase()
        val bundleFormat = obj.optString("bundleFormat")
        val targetTable = obj.optString("targetTable")
        val tablesElement = obj.get("tables")?.takeUnless { it.isJsonNull }
        val providerId = obj.optString("providerId") ?: AiProviderId.NOOP.value
        val model = obj.optString("model") ?: "noop:default"
        val rowLimit = obj.optString("rowLimit")?.toLongOrNull()
        val seed = obj.optString("seed")

        val planSource = resolvePlanSource(planRef, planArtifactId)
        val target = resolveTargetBinding(targetTable, tablesElement, bundleFormat, outputFormat)

        return TestdataExecuteParsedArgs(
            approvalKey = approvalKey,
            approvalToken = approvalToken,
            targetDialect = targetDialect,
            planSource = planSource,
            target = target,
            providerId = AiProviderId(providerId),
            model = model,
            rowLimit = rowLimit,
            seed = seed,
        )
    }

    private fun resolvePlanSource(planRef: String?, planArtifactId: String?): TestdataExecutePlanSource {
        val variants = mutableListOf<TestdataExecutePlanSource>()
        if (planRef != null) {
            variants += parsePlanRef(planRef)
        }
        if (planArtifactId != null) {
            variants += TestdataExecutePlanSource.Id(planArtifactId)
        }
        val violation = when {
            variants.isEmpty() ->
                ValidationViolation("plan", "exactly one of planRef or planArtifactId is required")
            variants.size > 1 ->
                ValidationViolation("plan", "only one of planRef or planArtifactId is allowed")
            else -> null
        }
        if (violation != null) throw ValidationErrorException(listOf(violation))
        return variants.single()
    }

    private fun parsePlanRef(planRef: String): TestdataExecutePlanSource.Ref {
        val parsed = ServerResourceUri.parse(planRef)
        if (parsed is ResourceUriParseResult.Invalid) {
            throw ValidationErrorException(
                listOf(ValidationViolation("planRef", "invalid resource URI: ${parsed.reason}")),
            )
        }
        val uri = (parsed as ResourceUriParseResult.Valid).uri
        if (uri.kind != ResourceKind.ARTIFACTS) {
            throw ValidationErrorException(
                listOf(ValidationViolation("planRef", "expected artifacts, got ${uri.kind.pathSegment}")),
            )
        }
        return TestdataExecutePlanSource.Ref(uri)
    }

    private fun resolveTargetBinding(
        targetTable: String?,
        tablesElement: JsonElement?,
        bundleFormat: String?,
        outputFormat: String?,
    ): TestdataExecuteTargetBinding {
        val violation = when {
            !targetTable.isNullOrBlank() && tablesElement != null ->
                ValidationViolation("target", "'targetTable' and 'tables' are mutually exclusive")
            tablesElement == null && !bundleFormat.isNullOrBlank() ->
                ValidationViolation("bundleFormat", "must not be set without 'tables'")
            tablesElement == null && targetTable.isNullOrBlank() ->
                ValidationViolation(
                    "target",
                    "exactly one of 'targetTable' or 'tables' is required",
                )
            else -> null
        }
        if (violation != null) throw ValidationErrorException(listOf(violation))
        if (tablesElement != null) {
            return parseBundleTargetBinding(tablesElement, bundleFormat)
        }
        return TestdataExecuteTargetBinding.SingleTable(table = targetTable!!, outputFormat = outputFormat ?: "csv")
    }

    private fun parseBundleTargetBinding(
        tablesElement: JsonElement,
        bundleFormat: String?,
    ): TestdataExecuteTargetBinding.Bundle {
        validateBundleFormat(bundleFormat)
        return TestdataExecuteTargetBinding.Bundle(
            tables = parseTablesArray(tablesElement),
            bundleFormat = bundleFormat!!,
        )
    }

    private fun validateBundleFormat(bundleFormat: String?) {
        val violation = when {
            bundleFormat.isNullOrBlank() ->
                ValidationViolation("bundleFormat", "is required when 'tables' is set")
            bundleFormat !in dev.dmigrate.server.core.upload.bundle.BundleFormat.ALL ->
                ValidationViolation(
                    "bundleFormat",
                    "must be one of " +
                        dev.dmigrate.server.core.upload.bundle.BundleFormat.ALL.joinToString(","),
                )
            else -> null
        }
        if (violation != null) throw ValidationErrorException(listOf(violation))
    }

    private fun parseTablesArray(tablesElement: JsonElement): List<String> {
        val violation = when {
            !tablesElement.isJsonArray ->
                ValidationViolation("tables", "must be an array of strings")
            tablesElement.asJsonArray.isEmpty ->
                ValidationViolation("tables", "must not be empty")
            tablesElement.asJsonArray.any { entry ->
                val isString = entry.isJsonPrimitive && entry.asJsonPrimitive.isString
                !isString || entry.asString.isBlank()
            } -> ValidationViolation("tables", "items must be non-blank strings")
            else -> null
        }
        if (violation != null) throw ValidationErrorException(listOf(violation))
        val items = tablesElement.asJsonArray.map { it.asString }
        if (items.distinct().size != items.size) {
            throw ValidationErrorException(
                listOf(ValidationViolation("tables", "must not contain duplicates")),
            )
        }
        return items
    }
}
