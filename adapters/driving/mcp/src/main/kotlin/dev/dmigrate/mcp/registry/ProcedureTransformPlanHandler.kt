package dev.dmigrate.mcp.registry

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.server.application.ai.AiProviderError
import dev.dmigrate.server.application.ai.AiProviderRegistry
import dev.dmigrate.server.application.ai.AiProviderRequest
import dev.dmigrate.server.application.ai.AiProviderResolveOutcome
import dev.dmigrate.server.application.ai.AiProviderResult
import dev.dmigrate.server.application.ai.AiToolEnvelope
import dev.dmigrate.server.application.ai.AiToolOrchestrator
import dev.dmigrate.server.application.ai.AiToolWorkResult
import dev.dmigrate.server.application.ai.AiToolDispatchOutcome
import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.application.approval.ApprovalGrantService
import dev.dmigrate.server.application.audit.prompt.PromptHygieneRequest
import dev.dmigrate.server.application.audit.prompt.PromptHygieneResult
import dev.dmigrate.server.application.audit.prompt.PromptHygieneService
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.application.policy.PolicyAttempt
import dev.dmigrate.server.application.policy.PolicyService
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.core.ai.AiArtifactMetadata
import dev.dmigrate.server.core.ai.AiArtifactProvenance
import dev.dmigrate.server.core.ai.AiIntent
import dev.dmigrate.server.core.ai.AiToolAcquireOutcome
import dev.dmigrate.server.core.ai.AiWireArtifactKind
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.policy.PolicyDecision
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.AiArtifactMetadataStore
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import dev.dmigrate.core.util.sha256Hex

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — Handler für
 * `procedure_transform_plan`.
 *
 * Verbindet die LF-017 / LF-024 / LN-030 / LN-031-Pipeline-Schritte:
 *
 * 1. Phase 1 (vor Scope, materialisierungsfrei): Form-Validation
 *    der JSON-Args, Required-Felder, Source-Exactly-One,
 *    Resource-URI-Syntax.
 * 2. Scope-Check `dmigrate:ai:execute` (defensive — der
 *    Wire-Dispatch hat das schon erzwungen).
 * 3. Deterministischer `payloadFingerprint` über das
 *    Control-Field-stripped Payload (LF-017 / LF-024 / LN-030 / LN-031 Z. 1016-1019).
 * 4. [AiToolOrchestrator.dispatch] für Single-Writer-Acquire +
 *    Terminal-Outcome-Replay.
 * 5. work() (Phase 2, semantic, post-acquire):
 *    - Source-Resolution gegen [ArtifactStore] / [SchemaStore].
 *    - [PolicyService.decide] mit LF-017 / LF-024 / LN-030 / LN-031-Source-Refs.
 *    - [PromptHygieneService.sanitize] über das gebaute Prompt.
 *    - [AiProviderRegistry.resolve] + [AiProviderPort.invoke].
 *    - Output-Hygiene über die Provider-Antwort (LF-017 / LF-024 / LN-030 / LN-031).
 *    - Publish: Plan-Artefakt + Bytes + AiArtifactMetadata.
 * 6. Wire-Mapping aus [AiToolDispatchOutcome] in den LF-017 / LF-024 / LN-030 / LN-031-
 *    Tool-Envelope (`summary`, `findings`, `planRef`,
 *    `planArtifactId`, `planResourceUri`, `providerMeta`,
 *    `executionMeta`).
 *
 * Scope-Carve-out für LF-017 / LF-024 / LN-030 / LN-031 (wird mit den
 * Execute-/Testdata-/Prompt-Pfaden geschlossen):
 *
 * - `PolicyDecision.RequiresApproval` wird als generisches
 *   `POLICY_REQUIRED` ohne Challenge-Felder (`approvalRequestId`,
 *   `requiredScopes`, `reasons`) projiziert. Volle Challenge-Form
 *   kommt mit dem LF-017 / LF-024 / LN-030 / LN-031-Approval-Flow.
 */
internal class ProcedureTransformPlanHandler(
    private val orchestrator: AiToolOrchestrator,
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
    private val schemaStore: SchemaStore,
    private val aiArtifactMetadataStore: AiArtifactMetadataStore,
    private val providerRegistry: AiProviderRegistry,
    private val hygieneService: PromptHygieneService,
    private val policyService: PolicyService,
    private val approvalGrantService: ApprovalGrantService,
    private val quotaService: QuotaService,
    private val clock: Clock,
    private val artifactTtl: Duration = Duration.ofDays(30),
) : ToolHandler {

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        // Phase 1: form validation (materialisierungsfrei, throws
        // ValidationErrorException via DefaultErrorMapper).
        val parsed = parseArguments(context.arguments)

        // Phase 2: scope check vor jedem Outcome-Store-Claim,
        // Policy, Quota, Provider-Konfig, Secret-Aufloesung
        // (LF-017 / LF-024 / LN-030 / LN-031 Z. 1020).
        enforceScope(context.principal)

        val payloadFingerprint = computePayloadFingerprint(parsed)
        val envelope = AiToolEnvelope(
            toolName = TOOL_NAME,
            tenantId = context.principal.effectiveTenantId,
            callerId = context.principal.principalId,
            approvalKey = parsed.approvalKey,
            payloadFingerprint = payloadFingerprint,
            now = clock.instant(),
        )

        // Audit-Felder befuellen (LF-017 / LF-024 / LN-030 / LN-031 + LF-012 / LN-011 / LN-017 / LN-027).
        context.auditFields.payloadFingerprint = payloadFingerprint
        context.auditFields.resourceRefs = context.auditFields.resourceRefs + parsed.allResourceRefs()

        val dispatch = orchestrator.dispatch(envelope) { claim ->
            performWork(parsed, context.principal, envelope, payloadFingerprint, claim)
        }

        // LF-017 / LF-024 / LN-030 / LN-031: Provider-/Modell-Metadaten ins Audit-Event;
        // gilt fuer Live-Aufrufe und Replay (orchestrator kopiert
        // die Werte beim Replay aus dem terminalen Outcome).
        if (dispatch is AiToolDispatchOutcome.WireSuccess) {
            context.auditFields.resourceRefs = context.auditFields.resourceRefs + listOf(
                "provider:${dispatch.providerName}",
                "model:${dispatch.model}",
                "providerRequestId:${dispatch.providerRequestId ?: "null"}",
            )
        }

        return projectToWire(dispatch, context)
    }

    // ---- Phase 1: form validation ---------------------------------------

    private fun parseArguments(arguments: JsonElement?): ParsedArgs {
        val obj = JsonArgs.requireObject(arguments)
        val approvalKey = obj.requireString("approvalKey")
        val targetDialect = obj.requireString("targetDialect")
        val approvalToken = obj.optString("approvalToken")
        val procedureRef = obj.optString("procedureRef")
        val artifactRef = obj.optString("artifactRef")
        val schemaRef = obj.optString("schemaRef")
        val procedureName = obj.optString("procedureName")
        val profileRef = obj.optString("profileRef")
        val diffRef = obj.optString("diffRef")
        val providerId = obj.optString("providerId") ?: AiProviderId.NOOP.value
        val model = obj.optString("model") ?: "noop:default"
        val rules = obj.get("rules")?.takeUnless { it.isJsonNull }

        val source = resolveSourceVariant(procedureRef, artifactRef, schemaRef, procedureName)
        validateUriSyntax("artifactRef", artifactRef, ResourceKind.ARTIFACTS)
        validateUriSyntax("schemaRef", schemaRef, ResourceKind.SCHEMAS)
        validateUriSyntax("profileRef", profileRef, ResourceKind.PROFILES)
        validateUriSyntax("diffRef", diffRef, ResourceKind.DIFFS)

        return ParsedArgs(
            approvalKey = approvalKey,
            approvalToken = approvalToken,
            targetDialect = targetDialect,
            source = source,
            profileRef = profileRef,
            diffRef = diffRef,
            providerId = AiProviderId(providerId),
            model = model,
            rulesJson = rules?.toString(),
        )
    }

    private fun resolveSourceVariant(
        procedureRef: String?,
        artifactRef: String?,
        schemaRef: String?,
        procedureName: String?,
    ): SourceVariant {
        val variants = mutableListOf<SourceVariant>()
        if (procedureRef != null) variants += SourceVariant.Procedure(procedureRef)
        if (artifactRef != null) variants += SourceVariant.Artifact(artifactRef)
        if (schemaRef != null) {
            // procedureName-Pflichtcheck passiert weiter unten als
            // strukturierter ValidationErrorException (LF-017 / LF-024 / LN-030 / LN-031
            // erwartet feldname=procedureName).
            variants += SourceVariant.SchemaWithProcedure(schemaRef, procedureName ?: "")
        }
        if (variants.isEmpty()) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "source",
                        "exactly one of procedureRef, artifactRef, or schemaRef+procedureName is required",
                    ),
                ),
            )
        }
        if (variants.size > 1) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "source",
                        "only one of procedureRef, artifactRef, or schemaRef+procedureName is allowed",
                    ),
                ),
            )
        }
        val v = variants.single()
        if (v is SourceVariant.SchemaWithProcedure && v.procedureName.isBlank()) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "procedureName",
                        "is required when schemaRef is supplied",
                    ),
                ),
            )
        }
        return v
    }

    private fun validateUriSyntax(field: String, raw: String?, expected: ResourceKind) {
        if (raw == null) return
        val parsed = ServerResourceUri.parse(raw)
        if (parsed is ResourceUriParseResult.Invalid) {
            throw ValidationErrorException(
                listOf(ValidationViolation(field, "invalid resource URI: ${parsed.reason}")),
            )
        }
        val uri = (parsed as ResourceUriParseResult.Valid).uri
        if (uri.kind != expected) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        field,
                        "expected ${expected.pathSegment}, got ${uri.kind.pathSegment}",
                    ),
                ),
            )
        }
    }

    // ---- Phase 2: scope ------------------------------------------------

    private fun enforceScope(principal: PrincipalContext) {
        if (principal.isAdmin) return
        if (REQUIRED_SCOPE in principal.scopes) return
        throw ForbiddenPrincipalException(
            principalId = principal.principalId,
            reason = "missing scope: $REQUIRED_SCOPE",
        )
    }

    // ---- Fingerprint ---------------------------------------------------

    private fun computePayloadFingerprint(parsed: ParsedArgs): String {
        // LF-017 / LF-024 / LN-030 / LN-031 Z. 1016-1019: Control-Felder (approvalKey,
        // approvalToken) werden VOR dem Hashen entfernt. approvalKey
        // ist bereits Scope-Komponente (AiToolScope.approvalKey).
        val canonical = buildString {
            append("v1|")
            append("targetDialect=").append(parsed.targetDialect).append('|')
            append("source=").append(parsed.source.canonicalForm()).append('|')
            append("profileRef=").append(parsed.profileRef ?: "").append('|')
            append("diffRef=").append(parsed.diffRef ?: "").append('|')
            append("providerId=").append(parsed.providerId.value).append('|')
            append("model=").append(parsed.model).append('|')
            append("rules=").append(parsed.rulesJson ?: "")
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    // ---- Phase 2 work() ------------------------------------------------

    @Suppress("ReturnCount")
    private fun performWork(
        parsed: ParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        claim: AiToolAcquireOutcome.Acquired,
    ): AiToolWorkResult {
        val sourceRefs: List<ServerResourceUri> = try {
            resolveSources(parsed, principal)
        } catch (e: SourceResolutionFailure) {
            return AiToolWorkResult.FailedTerminal(e.code, e.message ?: e.code.name)
        }

        decidePolicyOrFail(parsed, envelope, payloadFingerprint, sourceRefs, claim)?.let { return it }

        return performAfterPolicy(parsed, principal, envelope, payloadFingerprint, sourceRefs)
    }

    @Suppress("ReturnCount")
    private fun performAfterPolicy(
        parsed: ParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        sourceRefs: List<ServerResourceUri>,
    ): AiToolWorkResult {
        val provider = resolveProviderOrFail(parsed)
        if (provider is ProviderResolution.Failure) return provider.result
        val resolved = (provider as ProviderResolution.Resolved).outcome

        val invocation = invokeProviderWithHygiene(parsed, envelope, sourceRefs, resolved)
        if (invocation is ProviderInvocation.Failure) return invocation.result
        val ok = invocation as ProviderInvocation.Success

        return publishPlanArtifact(parsed, principal, envelope, payloadFingerprint, sourceRefs, ok)
    }

    private fun decidePolicyOrFail(
        parsed: ParsedArgs,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        sourceRefs: List<ServerResourceUri>,
        claim: AiToolAcquireOutcome.Acquired,
    ): AiToolWorkResult? {
        val previousChallenge = claim.previousRetryable?.takeIf {
            it.toolErrorCode == ToolErrorCode.POLICY_REQUIRED && it.approvalRequestId != null
        }
        if (previousChallenge != null) {
            return if (parsed.approvalToken == null) {
                AiToolApprovalSupport.replayChallenge(previousChallenge)
            } else {
                AiToolApprovalSupport.validateGrant(
                    rawToken = parsed.approvalToken,
                    challenge = previousChallenge,
                    envelope = envelope,
                    payloadFingerprint = payloadFingerprint,
                    approvalGrantService = approvalGrantService,
                )
            }
        }
        if (parsed.approvalToken != null) {
            return AiToolWorkResult.FailedTerminal(
                ToolErrorCode.POLICY_DENIED,
                "approval token supplied without a pending approval challenge",
            )
        }
        val decision = policyService.decide(
            PolicyAttempt(
                tenantId = envelope.tenantId,
                callerId = envelope.callerId,
                toolName = envelope.toolName,
                correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
                correlationKey = envelope.approvalKey,
                payloadFingerprint = payloadFingerprint,
                resourceRefs = sourceRefs.map { it.render() },
            ),
        )
        return when (decision) {
            PolicyDecision.Allowed -> null
            is PolicyDecision.Denied -> AiToolWorkResult.FailedTerminal(
                ToolErrorCode.POLICY_DENIED,
                "policy decision: ${decision.reasonCode}",
            )
            is PolicyDecision.RequiresApproval ->
                AiToolApprovalSupport.requiresApproval(decision, payloadFingerprint)
        }
    }

    private fun resolveProviderOrFail(parsed: ParsedArgs): ProviderResolution =
        when (val resolved = providerRegistry.resolve(parsed.providerId, parsed.model)) {
            is AiProviderResolveOutcome.NotConfigured -> ProviderResolution.Failure(
                AiToolWorkResult.FailedTerminal(
                    ToolErrorCode.FORBIDDEN_PRINCIPAL,
                    "provider '${parsed.providerId.value}' is not configured",
                ),
            )
            is AiProviderResolveOutcome.Disabled -> ProviderResolution.Failure(
                AiToolWorkResult.FailedTerminal(
                    ToolErrorCode.FORBIDDEN_PRINCIPAL,
                    "provider '${parsed.providerId.value}' is disabled",
                ),
            )
            is AiProviderResolveOutcome.UnknownModel -> ProviderResolution.Failure(
                AiToolWorkResult.FailedTerminal(
                    ToolErrorCode.VALIDATION_ERROR,
                    "model '${parsed.model}' is not in the provider's allowedModels",
                ),
            )
            is AiProviderResolveOutcome.ServerMisconfigured -> ProviderResolution.Failure(
                AiToolWorkResult.FailedTerminal(
                    ToolErrorCode.INTERNAL_AGENT_ERROR,
                    "provider registry is misconfigured",
                ),
            )
            is AiProviderResolveOutcome.Resolved -> ProviderResolution.Resolved(resolved)
        }

    private fun invokeProviderWithHygiene(
        parsed: ParsedArgs,
        envelope: AiToolEnvelope,
        sourceRefs: List<ServerResourceUri>,
        resolved: AiProviderResolveOutcome.Resolved,
    ): ProviderInvocation {
        val cfg = resolved.config
        val hygiene = hygieneService.sanitize(
            PromptHygieneRequest(
                toolName = envelope.toolName,
                tenantId = envelope.tenantId,
                principalId = envelope.callerId,
                allowedResourceRefs = sourceRefs,
                payloadJson = canonicalPayloadJson(parsed),
                promptText = buildPrompt(parsed, sourceRefs),
                providerId = parsed.providerId,
                maxPromptBytes = cfg.maxPromptBytes,
                maxPayloadBytes = cfg.maxPromptBytes,
            ),
        )
        if (hygiene is PromptHygieneResult.Block) {
            return ProviderInvocation.Failure(
                AiToolWorkResult.FailedTerminal(
                    ToolErrorCode.PROMPT_HYGIENE_BLOCKED,
                    hygiene.publicMessage,
                ),
            )
        }
        val allow = hygiene as PromptHygieneResult.Allow
        val quotaKey = QuotaKey(
            tenantId = envelope.tenantId,
            dimension = QuotaDimension.PROVIDER_CALLS,
            principalId = envelope.callerId,
            operation = envelope.toolName,
        )
        when (val outcome = quotaService.reserve(quotaKey, 1)) {
            is QuotaOutcome.RateLimited -> return ProviderInvocation.Failure(
                AiToolWorkResult.FailedRetryable(
                    ToolErrorCode.RATE_LIMITED,
                    "provider quota exceeded (${outcome.current}/${outcome.limit}, " +
                        "retryAfter=${outcome.retryAfter.seconds}s)",
                ),
            )
            is QuotaOutcome.Granted -> Unit
        }
        val reservation = QuotaReservation(quotaKey, 1)
        val success = try {
            val providerResult = resolved.port.invoke(
                AiProviderRequest(
                    prompt = allow.sanitizedPrompt,
                    model = parsed.model,
                    promptFingerprint = allow.promptFingerprint,
                    payloadFingerprint = allow.payloadFingerprint,
                    timeout = cfg.defaultTimeout,
                    maxOutputBytes = cfg.maxOutputBytes,
                ),
            )
            when (providerResult) {
                is AiProviderResult.Failure ->
                    return ProviderInvocation.Failure(mapProviderFailure(providerResult))
                is AiProviderResult.Success -> providerResult
            }
        } finally {
            quotaService.release(reservation)
        }
        // LF-017 / LF-024 / LN-030 / LN-031: Output-Hygiene über die Provider-Antwort.
        val outputCheck = hygieneService.sanitize(
            PromptHygieneRequest(
                toolName = envelope.toolName + ":output",
                tenantId = envelope.tenantId,
                principalId = envelope.callerId,
                allowedResourceRefs = emptyList(),
                payloadJson = "{}",
                promptText = success.output,
                providerId = parsed.providerId,
                maxPromptBytes = cfg.maxOutputBytes,
                maxPayloadBytes = cfg.maxOutputBytes,
            ),
        )
        if (outputCheck is PromptHygieneResult.Block) {
            return ProviderInvocation.Failure(
                AiToolWorkResult.FailedTerminal(
                    ToolErrorCode.PROMPT_HYGIENE_BLOCKED,
                    "provider output blocked by hygiene: ${outputCheck.publicMessage}",
                ),
            )
        }
        return ProviderInvocation.Success(allow, success)
    }

    private fun publishPlanArtifact(
        parsed: ParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        sourceRefs: List<ServerResourceUri>,
        ok: ProviderInvocation.Success,
    ): AiToolWorkResult {
        // LF-017 / LF-024 / LN-030 / LN-031 Z. 748: atomar zusammen mit dem Artefakt-Publish.
        val artifactBytes = serializePlanArtifact(parsed, ok.success, ok.allow)
        val artifactSha = sha256Hex(artifactBytes)
        val artifactId = deterministicArtifactId(envelope, payloadFingerprint)
        val resourceUri = ServerResourceUri(envelope.tenantId, ResourceKind.ARTIFACTS, artifactId)

        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = "$artifactId.plan.json",
                    contentType = "application/json",
                    sizeBytes = artifactBytes.size.toLong(),
                    sha256 = artifactSha,
                    createdAt = envelope.now,
                    expiresAt = envelope.now.plus(artifactTtl),
                ),
                kind = ArtifactKind.OTHER,
                tenantId = envelope.tenantId,
                ownerPrincipalId = principal.principalId,
                visibility = JobVisibility.TENANT,
                resourceUri = resourceUri,
            ),
        )
        artifactContentStore.write(
            artifactId = artifactId,
            source = ByteArrayInputStream(artifactBytes),
            expectedSizeBytes = artifactBytes.size.toLong(),
        )
        aiArtifactMetadataStore.save(
            AiArtifactMetadata(
                tenantId = envelope.tenantId,
                artifactId = artifactId,
                resourceUri = resourceUri,
                wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN,
                aiIntent = AiIntent.PROCEDURE_TRANSFORM_PLAN,
                originToolName = TOOL_NAME,
                ownerPrincipalId = principal.principalId,
                policyIntent = "ai.execute.$TOOL_NAME",
                sourceRefs = sourceRefs,
                targetDialect = parsed.targetDialect,
                provenance = AiArtifactProvenance.Plan(
                    promptFingerprint = ok.allow.promptFingerprint,
                    payloadFingerprint = ok.allow.payloadFingerprint,
                ),
                providerName = ok.success.providerMeta.providerName,
                model = ok.success.providerMeta.model,
                modelVersion = ok.success.providerMeta.modelVersion,
                outputFingerprint = artifactSha,
                createdAt = envelope.now,
            ),
        )
        return AiToolWorkResult.Succeeded(
            resultRef = resourceUri.render(),
            outputFingerprint = artifactSha,
            providerName = ok.success.providerMeta.providerName,
            model = ok.success.providerMeta.model,
            providerRequestId = ok.success.providerMeta.requestId,
            promptFingerprint = ok.allow.promptFingerprint,
            payloadFingerprint = ok.allow.payloadFingerprint,
            modelVersion = ok.success.providerMeta.modelVersion,
        )
    }

    private sealed interface ProviderResolution {
        data class Resolved(val outcome: AiProviderResolveOutcome.Resolved) : ProviderResolution
        data class Failure(val result: AiToolWorkResult) : ProviderResolution
    }

    private sealed interface ProviderInvocation {
        data class Success(
            val allow: PromptHygieneResult.Allow,
            val success: AiProviderResult.Success,
        ) : ProviderInvocation

        data class Failure(val result: AiToolWorkResult) : ProviderInvocation
    }

    private fun mapProviderFailure(failure: AiProviderResult.Failure): AiToolWorkResult {
        val code = when (failure.error) {
            AiProviderError.TIMEOUT -> ToolErrorCode.OPERATION_TIMEOUT
            AiProviderError.RATE_LIMITED -> ToolErrorCode.RATE_LIMITED
            AiProviderError.OUTPUT_TOO_LARGE -> ToolErrorCode.PAYLOAD_TOO_LARGE
            AiProviderError.OUTPUT_HYGIENE_BLOCKED -> ToolErrorCode.PROMPT_HYGIENE_BLOCKED
            AiProviderError.PROVIDER_UNAVAILABLE,
            AiProviderError.UNAUTHORIZED,
            AiProviderError.BAD_REQUEST,
            AiProviderError.INTERNAL,
            -> ToolErrorCode.INTERNAL_AGENT_ERROR
        }
        return if (failure.retryable) {
            AiToolWorkResult.FailedRetryable(code, failure.message)
        } else {
            AiToolWorkResult.FailedTerminal(code, failure.message)
        }
    }

    // ---- Source resolution --------------------------------------------

    @Suppress("ThrowsCount")
    private fun resolveSources(
        parsed: ParsedArgs,
        principal: PrincipalContext,
    ): List<ServerResourceUri> {
        val tenantId = principal.effectiveTenantId
        val refs = mutableListOf<ServerResourceUri>()
        when (val src = parsed.source) {
            is SourceVariant.Procedure -> {
                // procedureRef ist ein freies String-Token (LF-017 / LF-024 / LN-030 / LN-031 erlaubt
                // tool-spezifische Identitaeten); im MVP keine Lookup-
                // Pflicht — die Provenance haelt den Wert als Audit-Spur.
                // Bei einem `dmigrate://`-Format-Wert zwingen wir Tenant-Bindung.
                val parsedUri = ServerResourceUri.parse(src.value)
                if (parsedUri is ResourceUriParseResult.Valid) {
                    if (parsedUri.uri.tenantId != tenantId) {
                        throw SourceResolutionFailure(
                            ToolErrorCode.TENANT_SCOPE_DENIED,
                            "procedureRef tenant prefix mismatch",
                        )
                    }
                    refs += parsedUri.uri
                }
            }
            is SourceVariant.Artifact -> {
                val parsedUri = (ServerResourceUri.parse(src.value) as ResourceUriParseResult.Valid).uri
                if (parsedUri.tenantId != tenantId) {
                    throw SourceResolutionFailure(
                        ToolErrorCode.TENANT_SCOPE_DENIED,
                        "artifactRef tenant prefix mismatch",
                    )
                }
                artifactStore.findById(tenantId, parsedUri.id)
                    ?: throw SourceResolutionFailure(
                        ToolErrorCode.RESOURCE_NOT_FOUND,
                        "artifactRef not found",
                    )
                refs += parsedUri
            }
            is SourceVariant.SchemaWithProcedure -> {
                val parsedUri = (ServerResourceUri.parse(src.schemaRef) as ResourceUriParseResult.Valid).uri
                if (parsedUri.tenantId != tenantId) {
                    throw SourceResolutionFailure(
                        ToolErrorCode.TENANT_SCOPE_DENIED,
                        "schemaRef tenant prefix mismatch",
                    )
                }
                schemaStore.findById(tenantId, parsedUri.id)
                    ?: throw SourceResolutionFailure(
                        ToolErrorCode.RESOURCE_NOT_FOUND,
                        "schemaRef not found",
                    )
                refs += parsedUri
            }
        }
        // Optional refs auflösen.
        parsed.profileRef?.let { ref ->
            val u = (ServerResourceUri.parse(ref) as ResourceUriParseResult.Valid).uri
            if (u.tenantId != tenantId) {
                throw SourceResolutionFailure(
                    ToolErrorCode.TENANT_SCOPE_DENIED,
                    "profileRef tenant prefix mismatch",
                )
            }
            refs += u
        }
        parsed.diffRef?.let { ref ->
            val u = (ServerResourceUri.parse(ref) as ResourceUriParseResult.Valid).uri
            if (u.tenantId != tenantId) {
                throw SourceResolutionFailure(
                    ToolErrorCode.TENANT_SCOPE_DENIED,
                    "diffRef tenant prefix mismatch",
                )
            }
            refs += u
        }
        return refs.toList()
    }

    private class SourceResolutionFailure(
        val code: ToolErrorCode,
        message: String,
    ) : RuntimeException(message)

    private fun deterministicArtifactId(
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
    ): String {
        val material = "ai-plan|${envelope.tenantId.value}|${envelope.approvalKey}|$payloadFingerprint"
        val digest = sha256Hex(material.toByteArray(Charsets.UTF_8))
        return "art-" + digest.take(ARTIFACT_ID_HEX_LENGTH)
    }

    // ---- Wire mapping -------------------------------------------------

    private fun projectToWire(
        outcome: AiToolDispatchOutcome,
        context: ToolCallContext,
    ): ToolCallOutcome = when (outcome) {
        is AiToolDispatchOutcome.WireSuccess -> {
            val artifactId = outcome.resultRef.substringAfterLast("/")
            val responseJson = buildSuccessJson(
                resultRef = outcome.resultRef,
                artifactId = artifactId,
                providerName = outcome.providerName,
                model = outcome.model,
                providerRequestId = outcome.providerRequestId,
                requestId = context.requestId,
                replayed = outcome.replayed,
            )
            ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(type = "text", text = responseJson, mimeType = "application/json"),
                ),
            )
        }
        is AiToolDispatchOutcome.WireFailure -> ToolCallOutcome.Error(
            envelope = dev.dmigrate.server.core.error.ToolErrorEnvelope(
                code = outcome.toolErrorCode,
                message = outcome.scrubbedMessage,
                details = outcome.details,
                requestId = context.requestId,
                // `retryable`-Hint wandert nicht in den
                // ToolErrorEnvelope (LF-017 / LF-024 / LN-030 / LN-031: Wire-Caller kann
                // anhand des `code` ableiten — siehe ToolErrorCode-
                // Tabelle in LF-012 / LN-011 / LN-017 / LN-027). Audit hat den Hint via
                // AiToolOutcomeStore-Outcome.
            ),
        )
    }

    companion object {
        const val TOOL_NAME: String = "procedure_transform_plan"
        const val REQUIRED_SCOPE: String = "dmigrate:ai:execute"
        private const val ARTIFACT_ID_HEX_LENGTH: Int = 32
    }
}

private fun buildPrompt(parsed: ParsedArgs, sourceRefs: List<ServerResourceUri>): String =
    buildString {
        append("d-migrate procedure-transform-plan\n")
        append("targetDialect=").append(parsed.targetDialect).append('\n')
        append("sourceType=").append(parsed.source.kindLabel()).append('\n')
        sourceRefs.forEach { append("ref=").append(it.render()).append('\n') }
        parsed.profileRef?.let { append("profileRef=").append(it).append('\n') }
        parsed.diffRef?.let { append("diffRef=").append(it).append('\n') }
        if (parsed.rulesJson != null) append("rules=present\n")
    }

private fun canonicalPayloadJson(parsed: ParsedArgs): String =
    buildString {
        append("{\"targetDialect\":\"").append(parsed.targetDialect).append('"')
        append(",\"source\":\"").append(parsed.source.canonicalForm()).append('"')
        parsed.profileRef?.let { append(",\"profileRef\":\"").append(it).append('"') }
        parsed.diffRef?.let { append(",\"diffRef\":\"").append(it).append('"') }
        append(",\"providerId\":\"").append(parsed.providerId.value).append('"')
        append(",\"model\":\"").append(parsed.model).append('"')
        parsed.rulesJson?.let { append(",\"rules\":").append(it) }
        append('}')
    }

private fun serializePlanArtifact(
    parsed: ParsedArgs,
    success: AiProviderResult.Success,
    allow: PromptHygieneResult.Allow,
): ByteArray {
    val text = buildString {
        append("{\"summary\":\"plan generated\"")
        append(",\"providerOutput\":\"").append(escapeJson(success.output)).append('"')
        append(",\"targetDialect\":\"").append(parsed.targetDialect).append('"')
        append(",\"promptFingerprint\":\"").append(allow.promptFingerprint).append('"')
        append(",\"payloadFingerprint\":\"").append(allow.payloadFingerprint).append('"')
        append(",\"providerName\":\"").append(success.providerMeta.providerName).append('"')
        append(",\"model\":\"").append(success.providerMeta.model).append('"')
        append('}')
    }
    return text.toByteArray(Charsets.UTF_8)
}

private fun buildSuccessJson(
    resultRef: String,
    artifactId: String,
    providerName: String,
    model: String,
    providerRequestId: String?,
    requestId: String,
    replayed: Boolean,
): String = buildString {
    append("{\"summary\":\"")
    append(if (replayed) "replayed plan" else "plan generated")
    append("\"")
    append(",\"findings\":[]")
    append(",\"planRef\":\"").append(resultRef).append('"')
    append(",\"planArtifactId\":\"").append(artifactId).append('"')
    append(",\"planResourceUri\":\"").append(resultRef).append('"')
    append(",\"providerMeta\":{\"providerName\":\"").append(providerName).append('"')
    append(",\"model\":\"").append(model).append('"')
    append(",\"modelVersion\":null")
    append(",\"requestId\":")
    if (providerRequestId == null) append("null") else {
        append('"').append(providerRequestId).append('"')
    }
    append('}')
    append(",\"executionMeta\":{\"requestId\":\"").append(requestId).append("\"}")
    append('}')
}

private fun escapeJson(text: String): String =
    text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private data class ParsedArgs(
    val approvalKey: String,
    val approvalToken: String?,
    val targetDialect: String,
    val source: SourceVariant,
    val profileRef: String?,
    val diffRef: String?,
    val providerId: AiProviderId,
    val model: String,
    val rulesJson: String?,
) {
    fun allResourceRefs(): List<String> = buildList {
        when (source) {
            is SourceVariant.Procedure -> add(source.value)
            is SourceVariant.Artifact -> add(source.value)
            is SourceVariant.SchemaWithProcedure -> {
                add(source.schemaRef)
                add(source.procedureName)
            }
        }
        profileRef?.let { add(it) }
        diffRef?.let { add(it) }
    }
}

private sealed interface SourceVariant {
    fun canonicalForm(): String
    fun kindLabel(): String

    data class Procedure(val value: String) : SourceVariant {
        override fun canonicalForm(): String = "procedureRef:$value"
        override fun kindLabel(): String = "procedureRef"
    }

    data class Artifact(val value: String) : SourceVariant {
        override fun canonicalForm(): String = "artifactRef:$value"
        override fun kindLabel(): String = "artifactRef"
    }

    data class SchemaWithProcedure(
        val schemaRef: String,
        val procedureName: String,
    ) : SourceVariant {
        override fun canonicalForm(): String = "schemaRef:$schemaRef|procedureName:$procedureName"
        override fun kindLabel(): String = "schemaRef+procedureName"
    }
}
