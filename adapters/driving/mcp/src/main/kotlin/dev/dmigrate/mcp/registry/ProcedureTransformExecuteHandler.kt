package dev.dmigrate.mcp.registry

import com.google.gson.JsonElement
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.server.application.ai.AiProviderError
import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.application.ai.AiProviderRegistry
import dev.dmigrate.server.application.ai.AiProviderRequest
import dev.dmigrate.server.application.ai.AiProviderResolveOutcome
import dev.dmigrate.server.application.ai.AiProviderResult
import dev.dmigrate.server.application.ai.AiToolDispatchOutcome
import dev.dmigrate.server.application.ai.AiToolEnvelope
import dev.dmigrate.server.application.ai.AiToolOrchestrator
import dev.dmigrate.server.application.ai.AiToolWorkResult
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
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import dev.dmigrate.core.util.sha256Hex

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — Handler für
 * `procedure_transform_execute`.
 *
 * Eigenarten gegenüber [ProcedureTransformPlanHandler]:
 *
 * - Genau eine Plan-Source: `planRef` ODER `planArtifactId`. Plan
 *   §5.5 Z. 770 — Schema listet beide, Handler erzwingt
 *   exactly-one.
 * - **Keine eigenen Source-Refs im Payload** (LF-012 / LN-011 / LN-017 / LN-027 Z. 794-799
 *   wortlaeufig): Source-Refs werden ausschliesslich aus der
 *   [AiArtifactProvenance.Plan]-Provenance des Plan-Artefakts
 *   uebernommen. Der Caller darf keine eigenen `schemaRef`/
 *   `procedureRef`-Felder mitbringen.
 * - Plan-Validierung gegen AiArtifactMetadata (LF-012 / LN-011 / LN-017 / LN-027 Z. 783-792):
 *   `wireArtifactKind=procedure-transform-plan`,
 *   `aiIntent=procedure_transform_plan`, Tenant-Match,
 *   `targetDialect`-Match. Stale, fremde, manuell hochgeladene oder
 *   andersartige Artefakte werden vor Provider-Aufruf abgewiesen.
 * - Output-Artefakt: `wireArtifactKind=procedure-transform-output`,
 *   `aiIntent=procedure_transform_execute`. Provenance ist
 *   [AiArtifactProvenance.Execute] mit Plan-Bindung
 *   (`planRef`, `planArtifactFingerprint`).
 * - Wire-Envelope: `targetArtifactId` + `targetResourceUri` als
 *   Pflichtfelder (statt `planRef` im Plan-Pfad).
 *
 * Wiederholt das LF-017 / LF-024 / LN-030 / LN-031-Pipeline-Skelett; die Crosscutting-Logik
 * (Single-Writer-Acquire, Output-Hygiene LF-017 / LF-024 / LN-030 / LN-031,
 * Audit-Felder) ist identisch.
 */
internal class ProcedureTransformExecuteHandler(
    private val orchestrator: AiToolOrchestrator,
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
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
        val parsed = parseArguments(context.arguments)
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

        context.auditFields.payloadFingerprint = payloadFingerprint
        context.auditFields.resourceRefs = context.auditFields.resourceRefs + parsed.resourceRefHints()

        val dispatch = orchestrator.dispatch(envelope) { claim ->
            performWork(parsed, context.principal, envelope, payloadFingerprint, claim)
        }

        // LF-017 / LF-024 / LN-030 / LN-031: Provider-/Modell-Metadaten ins Audit-Event;
        // sowohl bei live-call als auch beim Replay.
        if (dispatch is AiToolDispatchOutcome.WireSuccess) {
            context.auditFields.resourceRefs = context.auditFields.resourceRefs + listOf(
                "provider:${dispatch.providerName}",
                "model:${dispatch.model}",
                "providerRequestId:${dispatch.providerRequestId ?: "null"}",
            )
        }

        return projectToWire(dispatch, context)
    }

    // ---- Phase 1: form validation -------------------------------------

    private fun parseArguments(arguments: JsonElement?): ParsedArgs {
        val obj = JsonArgs.requireObject(arguments)
        val approvalKey = obj.requireString("approvalKey")
        val targetDialect = obj.requireString("targetDialect")
        val approvalToken = obj.optString("approvalToken")
        val planRef = obj.optString("planRef")
        val planArtifactId = obj.optString("planArtifactId")
        val providerId = obj.optString("providerId") ?: AiProviderId.NOOP.value
        val model = obj.optString("model") ?: "noop:default"
        val executionOptions = obj.get("executionOptions")?.takeUnless { it.isJsonNull }

        val planSource = resolvePlanSource(planRef, planArtifactId)

        return ParsedArgs(
            approvalKey = approvalKey,
            approvalToken = approvalToken,
            targetDialect = targetDialect,
            planSource = planSource,
            providerId = AiProviderId(providerId),
            model = model,
            executionOptionsJson = executionOptions?.toString(),
        )
    }

    private fun resolvePlanSource(planRef: String?, planArtifactId: String?): PlanSource {
        val variants = mutableListOf<PlanSource>()
        if (planRef != null) {
            val parsed = ServerResourceUri.parse(planRef)
            if (parsed is ResourceUriParseResult.Invalid) {
                throw ValidationErrorException(
                    listOf(ValidationViolation("planRef", "invalid resource URI: ${parsed.reason}")),
                )
            }
            val uri = (parsed as ResourceUriParseResult.Valid).uri
            if (uri.kind != ResourceKind.ARTIFACTS) {
                throw ValidationErrorException(
                    listOf(
                        ValidationViolation(
                            "planRef",
                            "expected artifacts, got ${uri.kind.pathSegment}",
                        ),
                    ),
                )
            }
            variants += PlanSource.Ref(uri)
        }
        if (planArtifactId != null) {
            variants += PlanSource.Id(planArtifactId)
        }
        if (variants.isEmpty()) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "plan",
                        "exactly one of planRef or planArtifactId is required",
                    ),
                ),
            )
        }
        if (variants.size > 1) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "plan",
                        "only one of planRef or planArtifactId is allowed",
                    ),
                ),
            )
        }
        return variants.single()
    }

    // ---- Scope --------------------------------------------------------

    private fun enforceScope(principal: PrincipalContext) {
        if (principal.isAdmin) return
        if (REQUIRED_SCOPE in principal.scopes) return
        throw ForbiddenPrincipalException(
            principalId = principal.principalId,
            reason = "missing scope: $REQUIRED_SCOPE",
        )
    }

    // ---- Fingerprint --------------------------------------------------

    private fun computePayloadFingerprint(parsed: ParsedArgs): String {
        val canonical = buildString {
            append("v1|")
            append("targetDialect=").append(parsed.targetDialect).append('|')
            append("plan=").append(parsed.planSource.canonicalForm()).append('|')
            append("providerId=").append(parsed.providerId.value).append('|')
            append("model=").append(parsed.model).append('|')
            append("executionOptions=").append(parsed.executionOptionsJson ?: "")
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    // ---- Phase 2 work() -----------------------------------------------

    @Suppress("ReturnCount")
    private fun performWork(
        parsed: ParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        claim: AiToolAcquireOutcome.Acquired,
    ): AiToolWorkResult {
        val planResolution = try {
            resolvePlanProvenance(parsed, principal, envelope)
        } catch (e: PlanResolutionFailure) {
            return AiToolWorkResult.FailedTerminal(e.code, e.message ?: e.code.name)
        }

        decidePolicyOrFail(parsed, envelope, payloadFingerprint, planResolution, claim)?.let { return it }

        return performAfterPolicy(parsed, principal, envelope, payloadFingerprint, planResolution)
    }

    @Suppress("ReturnCount")
    private fun performAfterPolicy(
        parsed: ParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        planResolution: PlanResolution,
    ): AiToolWorkResult {
        val provider = resolveProviderOrFail(parsed)
        if (provider is ProviderResolution.Failure) return provider.result
        val resolved = (provider as ProviderResolution.Resolved).outcome

        val invocation = invokeProviderWithHygiene(parsed, envelope, planResolution, resolved)
        if (invocation is ProviderInvocation.Failure) return invocation.result
        val ok = invocation as ProviderInvocation.Success

        return publishExecuteArtifact(parsed, principal, envelope, payloadFingerprint, planResolution, ok)
    }

    // ---- Plan-Provenance-Validation -----------------------------------

    @Suppress("ThrowsCount")
    private fun resolvePlanProvenance(
        parsed: ParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
    ): PlanResolution {
        val tenantId = principal.effectiveTenantId
        val planArtifactId = when (val src = parsed.planSource) {
            is PlanSource.Id -> src.value
            is PlanSource.Ref -> {
                if (src.uri.tenantId != tenantId) {
                    throw PlanResolutionFailure(
                        ToolErrorCode.TENANT_SCOPE_DENIED,
                        "planRef tenant prefix mismatch",
                    )
                }
                src.uri.id
            }
        }

        val artifactRecord = artifactStore.findById(tenantId, planArtifactId)
            ?: throw PlanResolutionFailure(
                ToolErrorCode.RESOURCE_NOT_FOUND,
                "plan artifact not found",
            )
        val metadata = aiArtifactMetadataStore.findByArtifactId(tenantId, planArtifactId)
            ?: throw PlanResolutionFailure(
                ToolErrorCode.RESOURCE_NOT_FOUND,
                "plan artifact metadata not found (orphaned ArtifactRecord)",
            )

        // LF-012 / LN-011 / LN-017 / LN-027 Z. 783-792: harte Provenance-Pruefungen.
        if (metadata.wireArtifactKind != AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN) {
            throw PlanResolutionFailure(
                ToolErrorCode.VALIDATION_ERROR,
                "plan artifact has wrong wireArtifactKind: ${metadata.wireArtifactKind}",
            )
        }
        if (metadata.aiIntent != AiIntent.PROCEDURE_TRANSFORM_PLAN) {
            throw PlanResolutionFailure(
                ToolErrorCode.VALIDATION_ERROR,
                "plan artifact has wrong aiIntent: ${metadata.aiIntent}",
            )
        }
        if (metadata.targetDialect != parsed.targetDialect) {
            throw PlanResolutionFailure(
                ToolErrorCode.VALIDATION_ERROR,
                "targetDialect mismatch: plan was generated for ${metadata.targetDialect}",
            )
        }
        val planProvenance = metadata.provenance as? AiArtifactProvenance.Plan
            ?: throw PlanResolutionFailure(
                ToolErrorCode.VALIDATION_ERROR,
                "plan artifact has wrong provenance type: ${metadata.provenance::class.simpleName}",
            )
        envelope.toolName // no-op silencer for unused-param warnings; envelope is part of the signature for future use.
        return PlanResolution(
            planArtifactId = planArtifactId,
            planResourceUri = metadata.resourceUri,
            planArtifactFingerprint = artifactRecord.managedArtifact.sha256,
            planSourceRefs = metadata.sourceRefs,
            targetDialect = metadata.targetDialect,
            planProvenance = planProvenance,
        )
    }

    private class PlanResolutionFailure(
        val code: ToolErrorCode,
        message: String,
    ) : RuntimeException(message)

    private data class PlanResolution(
        val planArtifactId: String,
        val planResourceUri: ServerResourceUri,
        val planArtifactFingerprint: String,
        val planSourceRefs: List<ServerResourceUri>,
        val targetDialect: String,
        val planProvenance: AiArtifactProvenance.Plan,
    )

    // ---- Policy -------------------------------------------------------

    private fun decidePolicyOrFail(
        parsed: ParsedArgs,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        plan: PlanResolution,
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
        // LF-012 / LN-011 / LN-017 / LN-027: Policy sieht die aus dem Plan abgeleiteten
        // Source-Refs PLUS den planRef selbst. So kann eine
        // Allowlist-Regel den Execute-Pfad gegen die Planquelle
        // entscheiden.
        val refs = (plan.planSourceRefs + plan.planResourceUri).map { it.render() }
        val decision = policyService.decide(
            PolicyAttempt(
                tenantId = envelope.tenantId,
                callerId = envelope.callerId,
                toolName = envelope.toolName,
                correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
                correlationKey = envelope.approvalKey,
                payloadFingerprint = payloadFingerprint,
                resourceRefs = refs,
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

    // ---- Provider-Resolution ------------------------------------------

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
        plan: PlanResolution,
        resolved: AiProviderResolveOutcome.Resolved,
    ): ProviderInvocation {
        val cfg = resolved.config
        val hygiene = hygieneService.sanitize(
            PromptHygieneRequest(
                toolName = envelope.toolName,
                tenantId = envelope.tenantId,
                principalId = envelope.callerId,
                allowedResourceRefs = plan.planSourceRefs + plan.planResourceUri,
                payloadJson = canonicalPayloadJson(parsed),
                promptText = buildPrompt(parsed, plan),
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

    // ---- Publish ------------------------------------------------------

    private fun publishExecuteArtifact(
        parsed: ParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        plan: PlanResolution,
        ok: ProviderInvocation.Success,
    ): AiToolWorkResult {
        val artifactBytes = serializeOutputArtifact(parsed, plan, ok)
        val artifactSha = sha256Hex(artifactBytes)
        val artifactId = deterministicArtifactId(envelope, payloadFingerprint)
        val resourceUri = ServerResourceUri(envelope.tenantId, ResourceKind.ARTIFACTS, artifactId)

        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = "$artifactId.transform.json",
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
                wireArtifactKind = AiWireArtifactKind.PROCEDURE_TRANSFORM_OUTPUT,
                aiIntent = AiIntent.PROCEDURE_TRANSFORM_EXECUTE,
                originToolName = TOOL_NAME,
                ownerPrincipalId = principal.principalId,
                policyIntent = "ai.execute.$TOOL_NAME",
                // LF-012 / LN-011 / LN-017 / LN-027 Z. 794-799: Source-Refs werden aus der
                // Plan-Provenance uebernommen (NICHT aus dem
                // Execute-Payload), plus der LF-012 / LN-011 / LN-017 / LN-027 Ref selbst.
                sourceRefs = plan.planSourceRefs + plan.planResourceUri,
                targetDialect = parsed.targetDialect,
                provenance = AiArtifactProvenance.Execute(
                    promptFingerprint = ok.allow.promptFingerprint,
                    payloadFingerprint = ok.allow.payloadFingerprint,
                    planRef = plan.planResourceUri,
                    planArtifactFingerprint = plan.planArtifactFingerprint,
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

    // ---- Prompt + Artifact -------------------------------------------

    private fun buildPrompt(parsed: ParsedArgs, plan: PlanResolution): String =
        buildString {
            append("d-migrate procedure-transform-execute\n")
            append("targetDialect=").append(parsed.targetDialect).append('\n')
            append("planRef=").append(plan.planResourceUri.render()).append('\n')
            plan.planSourceRefs.forEach { append("ref=").append(it.render()).append('\n') }
            // Execute-Optionen sind opake JSON; im prompt referenzieren wir
            // nur die Anwesenheit, nicht den (potentiell sensitiven)
            // Inhalt — der Inhalt fliesst in den Fingerprint ein.
            if (parsed.executionOptionsJson != null) append("executionOptions=present\n")
        }

    private fun canonicalPayloadJson(parsed: ParsedArgs): String =
        buildString {
            append("{\"targetDialect\":\"").append(parsed.targetDialect).append('"')
            append(",\"plan\":\"").append(parsed.planSource.canonicalForm()).append('"')
            append(",\"providerId\":\"").append(parsed.providerId.value).append('"')
            append(",\"model\":\"").append(parsed.model).append('"')
            parsed.executionOptionsJson?.let { append(",\"executionOptions\":").append(it) }
            append('}')
        }

    private fun serializeOutputArtifact(
        parsed: ParsedArgs,
        plan: PlanResolution,
        ok: ProviderInvocation.Success,
    ): ByteArray {
        val text = buildString {
            append("{\"summary\":\"transform output\"")
            append(",\"providerOutput\":\"").append(escapeJson(ok.success.output)).append('"')
            append(",\"targetDialect\":\"").append(parsed.targetDialect).append('"')
            append(",\"planRef\":\"").append(plan.planResourceUri.render()).append('"')
            append(",\"planArtifactFingerprint\":\"").append(plan.planArtifactFingerprint).append('"')
            append(",\"executePromptFingerprint\":\"").append(ok.allow.promptFingerprint).append('"')
            append(",\"executePayloadFingerprint\":\"").append(ok.allow.payloadFingerprint).append('"')
            append(",\"providerName\":\"").append(ok.success.providerMeta.providerName).append('"')
            append(",\"model\":\"").append(ok.success.providerMeta.model).append('"')
            append('}')
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun deterministicArtifactId(
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
    ): String {
        val material = "ai-execute|${envelope.tenantId.value}|${envelope.approvalKey}|$payloadFingerprint"
        val digest = sha256Hex(material.toByteArray(Charsets.UTF_8))
        return "art-" + digest.take(ARTIFACT_ID_HEX_LENGTH)
    }

    // ---- Wire mapping ------------------------------------------------

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
            ),
        )
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
        append(if (replayed) "replayed transform output" else "transform output generated")
        append("\"")
        append(",\"findings\":[]")
        // LF-012 / LN-011 / LN-017 / LN-027: Output-Wire-Form heisst targetArtifactId +
        // targetResourceUri (NICHT planRef wie im Plan-Pfad).
        append(",\"targetArtifactId\":\"").append(artifactId).append('"')
        append(",\"targetResourceUri\":\"").append(resultRef).append('"')
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

    // ---- helpers ------------------------------------------------------

    private fun escapeJson(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    // ---- Parsed args + plan source -----------------------------------

    private data class ParsedArgs(
        val approvalKey: String,
        val approvalToken: String?,
        val targetDialect: String,
        val planSource: PlanSource,
        val providerId: AiProviderId,
        val model: String,
        val executionOptionsJson: String?,
    ) {
        fun resourceRefHints(): List<String> = when (val s = planSource) {
            is PlanSource.Id -> listOf("planArtifactId:${s.value}")
            is PlanSource.Ref -> listOf(s.uri.render())
        }
    }

    private sealed interface PlanSource {
        fun canonicalForm(): String

        data class Id(val value: String) : PlanSource {
            override fun canonicalForm(): String = "planArtifactId:$value"
        }

        data class Ref(val uri: ServerResourceUri) : PlanSource {
            override fun canonicalForm(): String = "planRef:${uri.render()}"
        }
    }

    companion object {
        const val TOOL_NAME: String = "procedure_transform_execute"
        const val REQUIRED_SCOPE: String = "dmigrate:ai:execute"
        private const val ARTIFACT_ID_HEX_LENGTH: Int = 32
    }
}
