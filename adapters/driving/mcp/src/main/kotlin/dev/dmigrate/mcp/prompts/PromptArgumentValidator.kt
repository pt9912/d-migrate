package dev.dmigrate.mcp.prompts

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * Phase G § 5.7 + § 6 G.7 (G.7) — pure Argumentvalidierung gegen
 * [PromptArgumentSpec].
 *
 * Pure Funktion: keine IO, keine Lookups. Ressource-Refs werden
 * **strukturell** geprüft (URI-Form + ResourceKind-Match), nicht
 * gegen einen Store — der Store-Lookup folgt erst, wenn der Tool-
 * Pfad sie tatsächlich materialisiert (Plan §6 G.6 Z. 1014:
 * "semantische Resource-/Artifact-/Provider-Validierung erst
 * nach Scope, Idempotency-/Outcome-Replay und Policy").
 *
 * Tenant-Bindung: jede `dmigrate://`-Ref im Argument muss zum
 * `effectiveTenantId` des Callers gehören. Cross-Tenant-Refs
 * werden mit [PromptArgumentValidationError.TENANT_SCOPE_DENIED]
 * abgewiesen.
 */
object PromptArgumentValidator {

    fun validate(
        spec: PromptDescriptor,
        arguments: Map<String, String>,
        tenantId: TenantId,
    ): PromptArgumentValidationResult {
        val errors = mutableListOf<PromptArgumentViolation>()

        // 1. Pflichtfelder: alle required-Args müssen vorhanden sein.
        for (arg in spec.arguments) {
            val value = arguments[arg.name]
            if (arg.required && value.isNullOrBlank()) {
                errors += PromptArgumentViolation(
                    arg.name,
                    "is required",
                    PromptArgumentValidationError.MISSING_REQUIRED,
                )
                continue
            }
            if (value == null) continue
            validateValue(arg, value, tenantId, errors)
        }

        // 2. additionalProperties=false-Äquivalent: unbekannte
        // Argumente sind ein Validation-Fehler. Plan §6 G.7
        // Akzeptanz: "ungueltige Argumente -> VALIDATION_ERROR".
        val knownNames = spec.arguments.map { it.name }.toSet()
        for ((suppliedName, _) in arguments) {
            if (suppliedName !in knownNames) {
                errors += PromptArgumentViolation(
                    suppliedName,
                    "unknown argument (additionalProperties=false)",
                    PromptArgumentValidationError.UNKNOWN_ARGUMENT,
                )
            }
        }

        return if (errors.isEmpty()) {
            PromptArgumentValidationResult.Valid(arguments.filterKeys { it in knownNames })
        } else {
            PromptArgumentValidationResult.Invalid(errors.toList())
        }
    }

    private fun validateValue(
        arg: PromptArgumentSpec,
        value: String,
        tenantId: TenantId,
        errors: MutableList<PromptArgumentViolation>,
    ) {
        when (arg.type) {
            PromptArgumentType.STRING -> {
                if (arg.pattern != null && !Regex(arg.pattern).containsMatchIn(value)) {
                    errors += PromptArgumentViolation(
                        arg.name,
                        "does not match required pattern",
                        PromptArgumentValidationError.PATTERN_MISMATCH,
                    )
                }
            }
            PromptArgumentType.ENUM -> {
                if (value !in arg.allowedValues) {
                    errors += PromptArgumentViolation(
                        arg.name,
                        "must be one of ${arg.allowedValues.sorted()}",
                        PromptArgumentValidationError.ENUM_VIOLATION,
                    )
                }
            }
            PromptArgumentType.RESOURCE_URI -> {
                val parsed = ServerResourceUri.parse(value)
                if (parsed is ResourceUriParseResult.Invalid) {
                    errors += PromptArgumentViolation(
                        arg.name,
                        "invalid resource URI: ${parsed.reason}",
                        PromptArgumentValidationError.INVALID_URI,
                    )
                    return
                }
                val uri = (parsed as ResourceUriParseResult.Valid).uri
                val kindLabel = uri.kind.pathSegment
                if (kindLabel !in arg.allowedResourceKinds) {
                    errors += PromptArgumentViolation(
                        arg.name,
                        "expected one of ${arg.allowedResourceKinds.sorted()}, got $kindLabel",
                        PromptArgumentValidationError.WRONG_RESOURCE_KIND,
                    )
                    return
                }
                if (uri.tenantId != tenantId) {
                    errors += PromptArgumentViolation(
                        arg.name,
                        "tenant prefix mismatch: caller is ${tenantId.value}",
                        PromptArgumentValidationError.TENANT_SCOPE_DENIED,
                    )
                }
            }
        }
    }
}

sealed interface PromptArgumentValidationResult {
    data class Valid(val arguments: Map<String, String>) : PromptArgumentValidationResult
    data class Invalid(val violations: List<PromptArgumentViolation>) : PromptArgumentValidationResult
}

data class PromptArgumentViolation(
    val field: String,
    val reason: String,
    val code: PromptArgumentValidationError,
)

enum class PromptArgumentValidationError {
    MISSING_REQUIRED,
    UNKNOWN_ARGUMENT,
    PATTERN_MISMATCH,
    ENUM_VIOLATION,
    INVALID_URI,
    WRONG_RESOURCE_KIND,
    TENANT_SCOPE_DENIED,
}
