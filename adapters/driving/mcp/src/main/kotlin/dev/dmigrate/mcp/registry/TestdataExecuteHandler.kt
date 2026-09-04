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
import dev.dmigrate.server.core.artifact.ArtifactUploadMetadata
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
 * LF-017 / LF-024 / LN-030 / LN-031 — Handler für `testdata_execute`.
 *
 * LF-017 / LF-024 / LN-030 / LN-031-Vertrag in Kürze:
 *
 * - Konsumiert ein freigegebenes [AiWireArtifactKind.TESTDATA_PLAN]-
 *   Artefakt (per `planRef` ODER `planArtifactId` — exactly-one).
 * - Erzeugt **kein** Datenbank-Write. Output ist ein importierbares
 *   Datenartefakt (`UPLOAD_INPUT`-Kind) mit doppelter Metadaten-Spur:
 *   `ArtifactUploadMetadata` (LF-017 / LF-024 / LN-030 / LN-031 Pfad-A — synthetisch) plus
 *   `AiArtifactMetadata` (Provenance/Origin).
 * - Single-Table-Outputs tragen `wireArtifactKind=generated-testdata`
 *   und nutzen den LF-010 / LF-013 / LN-009 / LN-011-konformen Single-File-Importpfad.
 * - Bundle-Outputs tragen `wireArtifactKind=seed-data-bundle` plus
 *   `bundleFormat=seed-bundle.v1.zip` — `data_import_start.tables`
 *   konsumiert sie über den LF-010 / LF-013 / LN-009 / LN-011-Bundle-Vertrag.
 * - LF-017 / LF-024 / LN-030 / LN-031: kein `targetConnectionRef` im Tool-Payload. Schreibend
 *   wird erst der nachgelagerte `data_import_start` mit
 *   `dmigrate:data:write`.
 *
 * Pipeline analog zu [TestdataPlanHandler] (gemeinsame LF-017 / LF-024 / LN-030 / LN-031-
 * Single-Writer-Lease via [AiToolOrchestrator]); abweichende Schritte:
 * Plan-Artefakt-Provenance-Validation (§5 Z. 27-33), Zielbindungs-
 * Auflösung aus Plan-Artefakt ODER Payload (LF-017 / LF-024 / LN-030 / LN-031:
 * Bundle-Outputs müssen denselben Manifest-v1- und `targetTables`-Vertrag
 * wie LF-010 / LF-013 / LN-009 / LN-011 erfüllen),
 * synthetische `ArtifactUploadMetadata`-Erzeugung beim Publish.
 */
internal class TestdataExecuteHandler(
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
        context.auditFields.resourceRefs = context.auditFields.resourceRefs +
            parsed.resourceRefHints()

        val dispatch = orchestrator.dispatch(envelope) { claim ->
            performWork(parsed, context.principal, envelope, payloadFingerprint, claim)
        }

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

    private fun parseArguments(arguments: JsonElement?): TestdataExecuteParsedArgs =
        TestdataExecuteInputParser.parse(arguments)

    private fun enforceScope(principal: PrincipalContext) {
        if (principal.isAdmin) return
        if (REQUIRED_SCOPE in principal.scopes) return
        throw ForbiddenPrincipalException(
            principalId = principal.principalId,
            reason = "missing scope: $REQUIRED_SCOPE",
        )
    }

    private fun computePayloadFingerprint(parsed: TestdataExecuteParsedArgs): String {
        val canonical = buildString {
            append("v1|")
            append("targetDialect=").append(parsed.targetDialect).append('|')
            append("plan=").append(parsed.planSource.canonicalForm()).append('|')
            append("target=").append(parsed.target.canonicalForm()).append('|')
            append("providerId=").append(parsed.providerId.value).append('|')
            append("model=").append(parsed.model).append('|')
            append("rowLimit=").append(parsed.rowLimit?.toString() ?: "").append('|')
            append("seed=").append(parsed.seed ?: "")
        }
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    // ---- Phase 2 work() -----------------------------------------------

    @Suppress("ReturnCount")
    private fun performWork(
        parsed: TestdataExecuteParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        claim: AiToolAcquireOutcome.Acquired,
    ): AiToolWorkResult {
        val planResolution = try {
            resolvePlanProvenance(parsed, principal)
        } catch (e: PlanResolutionFailure) {
            return AiToolWorkResult.FailedTerminal(e.code, e.message ?: e.code.name)
        }

        decidePolicyOrFail(parsed, envelope, payloadFingerprint, planResolution, claim)
            ?.let { return it }

        return performAfterPolicy(parsed, principal, envelope, payloadFingerprint, planResolution)
    }

    private fun performAfterPolicy(
        parsed: TestdataExecuteParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        plan: PlanResolution,
    ): AiToolWorkResult {
        val provider = resolveProviderOrFail(parsed)
        if (provider is ProviderResolution.Failure) return provider.result
        val resolved = (provider as ProviderResolution.Resolved).outcome

        val invocation = invokeProviderWithHygiene(parsed, envelope, plan, resolved)
        if (invocation is ProviderInvocation.Failure) return invocation.result
        val ok = invocation as ProviderInvocation.Success

        return publishTestdataArtifact(parsed, principal, envelope, payloadFingerprint, plan, ok)
    }

    @Suppress("ThrowsCount")
    private fun resolvePlanProvenance(
        parsed: TestdataExecuteParsedArgs,
        principal: PrincipalContext,
    ): PlanResolution {
        val tenantId = principal.effectiveTenantId
        val planArtifactId = when (val src = parsed.planSource) {
            is TestdataExecutePlanSource.Id -> src.value
            is TestdataExecutePlanSource.Ref -> {
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
        if (metadata.wireArtifactKind != AiWireArtifactKind.TESTDATA_PLAN) {
            throw PlanResolutionFailure(
                ToolErrorCode.VALIDATION_ERROR,
                "plan artifact has wrong wireArtifactKind: ${metadata.wireArtifactKind}",
            )
        }
        if (metadata.aiIntent != AiIntent.TESTDATA_PLAN) {
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
        return PlanResolution(
            planArtifactId = planArtifactId,
            planResourceUri = metadata.resourceUri,
            planArtifactFingerprint = artifactRecord.managedArtifact.sha256,
            planSourceRefs = metadata.sourceRefs,
            targetDialect = metadata.targetDialect,
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
    )

    private fun decidePolicyOrFail(
        parsed: TestdataExecuteParsedArgs,
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

    private fun resolveProviderOrFail(parsed: TestdataExecuteParsedArgs): ProviderResolution =
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
        parsed: TestdataExecuteParsedArgs,
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

    private fun publishTestdataArtifact(
        parsed: TestdataExecuteParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        plan: PlanResolution,
        ok: ProviderInvocation.Success,
    ): AiToolWorkResult {
        val artifactBytes = serializeOutput(ok)
        val artifactSha = sha256Hex(artifactBytes)
        val artifactId = deterministicArtifactId(envelope, payloadFingerprint)
        val resourceUri = ServerResourceUri(envelope.tenantId, ResourceKind.ARTIFACTS, artifactId)
        val target = parsed.target
        val wireKind = when (target) {
            is TestdataExecuteTargetBinding.SingleTable -> AiWireArtifactKind.GENERATED_TESTDATA
            is TestdataExecuteTargetBinding.Bundle -> AiWireArtifactKind.SEED_DATA_BUNDLE
        }
        val outputFormat = when (target) {
            is TestdataExecuteTargetBinding.SingleTable -> target.outputFormat
            is TestdataExecuteTargetBinding.Bundle -> "csv"
        }
        val contentType = when (target) {
            is TestdataExecuteTargetBinding.SingleTable -> mimeTypeFor(target.outputFormat)
            is TestdataExecuteTargetBinding.Bundle -> "application/zip"
        }

        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = filenameFor(artifactId, target),
                    contentType = contentType,
                    sizeBytes = artifactBytes.size.toLong(),
                    sha256 = artifactSha,
                    createdAt = envelope.now,
                    expiresAt = envelope.now.plus(artifactTtl),
                ),
                kind = ArtifactKind.UPLOAD_INPUT,
                tenantId = envelope.tenantId,
                ownerPrincipalId = principal.principalId,
                visibility = JobVisibility.TENANT,
                resourceUri = resourceUri,
                uploadMetadata = ArtifactUploadMetadata(
                    artifactId = artifactId,
                    resourceUri = resourceUri.render(),
                    uploadIntent = ArtifactUploadInitHandler.INTENT_JOB_INPUT,
                    wireArtifactKind = wireKind,
                    contentType = contentType,
                    format = outputFormat,
                    targetTable = (target as? TestdataExecuteTargetBinding.SingleTable)?.table,
                    targetTables = (target as? TestdataExecuteTargetBinding.Bundle)?.tables,
                    sourceUploadSessionId = "ai:$artifactId",
                    policyFingerprint = null,
                    sizeBytes = artifactBytes.size.toLong(),
                    sha256 = artifactSha,
                    bundleFormat = (target as? TestdataExecuteTargetBinding.Bundle)?.bundleFormat,
                ),
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
                wireArtifactKind = wireKind,
                aiIntent = AiIntent.TESTDATA_EXECUTE,
                originToolName = TOOL_NAME,
                ownerPrincipalId = principal.principalId,
                policyIntent = "ai.execute.$TOOL_NAME",
                sourceRefs = plan.planSourceRefs + plan.planResourceUri,
                targetDialect = parsed.targetDialect,
                provenance = AiArtifactProvenance.TestdataExecute(
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

    private fun buildPrompt(parsed: TestdataExecuteParsedArgs, plan: PlanResolution): String =
        buildString {
            append("d-migrate testdata-execute\n")
            append("targetDialect=").append(parsed.targetDialect).append('\n')
            append("planRef=").append(plan.planResourceUri.render()).append('\n')
            plan.planSourceRefs.forEach { append("ref=").append(it.render()).append('\n') }
            append("target=").append(parsed.target.canonicalForm()).append('\n')
            parsed.rowLimit?.let { append("rowLimit=").append(it).append('\n') }
            parsed.seed?.let { append("seed=").append(it).append('\n') }
        }

    private fun canonicalPayloadJson(parsed: TestdataExecuteParsedArgs): String =
        buildString {
            append("{\"targetDialect\":\"").append(parsed.targetDialect).append('"')
            append(",\"plan\":\"").append(parsed.planSource.canonicalForm()).append('"')
            append(",\"target\":\"").append(parsed.target.canonicalForm()).append('"')
            append(",\"providerId\":\"").append(parsed.providerId.value).append('"')
            append(",\"model\":\"").append(parsed.model).append('"')
            parsed.rowLimit?.let { append(",\"rowLimit\":").append(it) }
            parsed.seed?.let { append(",\"seed\":\"").append(it).append('"') }
            append('}')
        }

    private fun deterministicArtifactId(
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
    ): String {
        val material = "ai-testdata|${envelope.tenantId.value}|${envelope.approvalKey}|$payloadFingerprint"
        val digest = sha256Hex(material.toByteArray(Charsets.UTF_8))
        return "art-" + digest.take(ARTIFACT_ID_HEX_LENGTH)
    }

    private fun serializeOutput(ok: ProviderInvocation.Success): ByteArray =
        ok.success.output.toByteArray(Charsets.UTF_8)

    private fun mimeTypeFor(format: String): String = when (format.lowercase()) {
        "csv" -> "text/csv"
        "json" -> "application/json"
        "yaml" -> "application/yaml"
        else -> "application/octet-stream"
    }

    private fun filenameFor(artifactId: String, target: TestdataExecuteTargetBinding): String =
        when (target) {
            is TestdataExecuteTargetBinding.SingleTable -> "$artifactId.${target.outputFormat}"
            is TestdataExecuteTargetBinding.Bundle -> "$artifactId.zip"
        }

    private fun projectToWire(
        outcome: AiToolDispatchOutcome,
        context: ToolCallContext,
    ): ToolCallOutcome = when (outcome) {
        is AiToolDispatchOutcome.WireSuccess -> {
            val artifactId = outcome.resultRef.substringAfterLast("/")
            ToolCallOutcome.Success(
                content = listOf(
                    ToolContent(
                        type = "text",
                        text = buildSuccessJson(
                            resultRef = outcome.resultRef,
                            artifactId = artifactId,
                            providerName = outcome.providerName,
                            model = outcome.model,
                            providerRequestId = outcome.providerRequestId,
                            requestId = context.requestId,
                            replayed = outcome.replayed,
                        ),
                        mimeType = "application/json",
                    ),
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
        append(if (replayed) "replayed testdata output" else "testdata output generated")
        append("\"")
        append(",\"testdataArtifactId\":\"").append(artifactId).append('"')
        append(",\"testdataResourceUri\":\"").append(resultRef).append('"')
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

    companion object {
        const val TOOL_NAME: String = "testdata_execute"
        const val REQUIRED_SCOPE: String = "dmigrate:ai:execute"
        private const val ARTIFACT_ID_HEX_LENGTH: Int = 32
    }
}

internal data class TestdataExecuteParsedArgs(
    val approvalKey: String,
    val approvalToken: String?,
    val targetDialect: String,
    val planSource: TestdataExecutePlanSource,
    val target: TestdataExecuteTargetBinding,
    val providerId: AiProviderId,
    val model: String,
    val rowLimit: Long?,
    val seed: String?,
) {
    fun resourceRefHints(): List<String> = buildList {
        when (val s = planSource) {
            is TestdataExecutePlanSource.Id -> add("planArtifactId:${s.value}")
            is TestdataExecutePlanSource.Ref -> add(s.uri.render())
        }
    }
}

internal sealed interface TestdataExecutePlanSource {
    fun canonicalForm(): String

    data class Id(val value: String) : TestdataExecutePlanSource {
        override fun canonicalForm(): String = "planArtifactId:$value"
    }

    data class Ref(val uri: ServerResourceUri) : TestdataExecutePlanSource {
        override fun canonicalForm(): String = "planRef:${uri.render()}"
    }
}

internal sealed interface TestdataExecuteTargetBinding {
    fun canonicalForm(): String

    data class SingleTable(val table: String, val outputFormat: String) : TestdataExecuteTargetBinding {
        override fun canonicalForm(): String = "single:$table:$outputFormat"
    }

    data class Bundle(val tables: List<String>, val bundleFormat: String) : TestdataExecuteTargetBinding {
        override fun canonicalForm(): String =
            "bundle:$bundleFormat:${tables.map { it.lowercase() }.distinct().sorted().joinToString(",")}"
    }
}
