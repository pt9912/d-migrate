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
import dev.dmigrate.server.ports.ProfileStore
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

/**
 * Phase G § 5.6 + § 6 G.6 (G.6.f) — Handler für `testdata_plan`.
 *
 * Eigenarten gegenüber [ProcedureTransformPlanHandler] (G.6.d):
 *
 * - Eingabe: `schemaRef` Pflicht, `profileRef` und `rules` optional.
 *   Plan §5.6 Z. 825-829 — keine Source-Variante-Wahl wie in G.6.d
 *   (procedureRef|artifactRef|schemaRef+procedureName), sondern nur
 *   ein Schema plus optionales Profile.
 * - Output-Artefakt: `wireArtifactKind=testdata-plan`,
 *   `aiIntent=testdata_plan`. Provenance ist
 *   [AiArtifactProvenance.TestdataPlan] mit `testdataPromptFingerprint`
 *   und `testdataPayloadFingerprint`.
 * - Wire-Envelope: `testdataPlanArtifactId` + `testdataPlanResourceUri`
 *   (statt `planRef`/`targetArtifactId` der G.6.d/e).
 * - Plan §5.6 Z. 833-836: Tool erzeugt Plan, KEINE produktiven
 *   Datenbank-Schreiboperationen. Plan §5.6 Z. 836-837: Profiling-
 *   Daten dürfen nur als verdichtete Summary oder erlaubte
 *   Resource-Ref genutzt werden — der Handler prüft beim
 *   `profileRef`-Lookup nur Existenz/Tenant-Scope, keinen
 *   Inhalts-Snapshot.
 *
 * Wiederholt das G.6.d-Pipeline-Skelett — die Crosscutting-Logik
 * (Single-Writer-Acquire, Output-Hygiene Plan §7.4, Audit-Felder)
 * ist identisch.
 */
internal class TestdataPlanHandler(
    private val orchestrator: AiToolOrchestrator,
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
    private val schemaStore: SchemaStore,
    private val profileStore: ProfileStore,
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
        val refs = buildList {
            add(parsed.schemaRef)
            parsed.profileRef?.let { add(it) }
        }
        context.auditFields.resourceRefs = context.auditFields.resourceRefs + refs

        val dispatch = orchestrator.dispatch(envelope) { claim ->
            performWork(parsed, context.principal, envelope, payloadFingerprint, claim)
        }

        // Plan §6 G.8: Provider-/Modell-Metadaten ins Audit-Event;
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
        val schemaRef = obj.requireString("schemaRef")
        val approvalToken = obj.optString("approvalToken")
        val profileRef = obj.optString("profileRef")
        val providerId = obj.optString("providerId") ?: AiProviderId.NOOP.value
        val model = obj.optString("model") ?: "noop:default"
        val rules = obj.get("rules")?.takeUnless { it.isJsonNull }

        validateUriSyntax("schemaRef", schemaRef, ResourceKind.SCHEMAS)
        validateUriSyntax("profileRef", profileRef, ResourceKind.PROFILES)

        return ParsedArgs(
            approvalKey = approvalKey,
            approvalToken = approvalToken,
            targetDialect = targetDialect,
            schemaRef = schemaRef,
            profileRef = profileRef,
            providerId = AiProviderId(providerId),
            model = model,
            rulesJson = rules?.toString(),
        )
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
            append("schemaRef=").append(parsed.schemaRef).append('|')
            append("profileRef=").append(parsed.profileRef ?: "").append('|')
            append("providerId=").append(parsed.providerId.value).append('|')
            append("model=").append(parsed.model).append('|')
            append("rules=").append(parsed.rulesJson ?: "")
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
        val sourceRefs: List<ServerResourceUri> = try {
            resolveSources(parsed, principal)
        } catch (e: SourceResolutionFailure) {
            return AiToolWorkResult.FailedTerminal(e.code, e.message ?: e.code.name)
        }

        decidePolicyOrFail(parsed, envelope, payloadFingerprint, sourceRefs, claim)?.let { return it }

        return performAfterPolicy(parsed, principal, envelope, payloadFingerprint, sourceRefs)
    }

    @Suppress("ReturnCount", "LongParameterList")
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

        return publishTestdataPlanArtifact(parsed, principal, envelope, payloadFingerprint, sourceRefs, ok)
    }

    // ---- Source resolution --------------------------------------------

    private class SourceResolutionFailure(
        val code: ToolErrorCode,
        message: String,
    ) : RuntimeException(message)

    @Suppress("ThrowsCount")
    private fun resolveSources(
        parsed: ParsedArgs,
        principal: PrincipalContext,
    ): List<ServerResourceUri> {
        val tenantId = principal.effectiveTenantId
        val refs = mutableListOf<ServerResourceUri>()

        val schemaUri = (ServerResourceUri.parse(parsed.schemaRef) as ResourceUriParseResult.Valid).uri
        if (schemaUri.tenantId != tenantId) {
            throw SourceResolutionFailure(
                ToolErrorCode.TENANT_SCOPE_DENIED,
                "schemaRef tenant prefix mismatch",
            )
        }
        schemaStore.findById(tenantId, schemaUri.id)
            ?: throw SourceResolutionFailure(
                ToolErrorCode.RESOURCE_NOT_FOUND,
                "schemaRef not found",
            )
        refs += schemaUri

        parsed.profileRef?.let { ref ->
            val u = (ServerResourceUri.parse(ref) as ResourceUriParseResult.Valid).uri
            if (u.tenantId != tenantId) {
                throw SourceResolutionFailure(
                    ToolErrorCode.TENANT_SCOPE_DENIED,
                    "profileRef tenant prefix mismatch",
                )
            }
            // Plan §5.6 Z. 836-837: Profile-Daten als verdichtete Summary
            // oder Resource-Ref — wir pruefen nur Existenz, kein Inhalts-
            // Snapshot.
            profileStore.findById(tenantId, u.id)
                ?: throw SourceResolutionFailure(
                    ToolErrorCode.RESOURCE_NOT_FOUND,
                    "profileRef not found",
                )
            refs += u
        }

        return refs.toList()
    }

    // ---- Policy -------------------------------------------------------

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
            is PolicyDecision.RequiresApproval -> AiToolApprovalSupport.requiresApproval(decision)
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
        // Plan §7.4: Output-Hygiene über die Provider-Antwort.
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

    @Suppress("LongParameterList")
    private fun publishTestdataPlanArtifact(
        parsed: ParsedArgs,
        principal: PrincipalContext,
        envelope: AiToolEnvelope,
        payloadFingerprint: String,
        sourceRefs: List<ServerResourceUri>,
        ok: ProviderInvocation.Success,
    ): AiToolWorkResult {
        val artifactBytes = serializeTestdataPlanArtifact(parsed, ok)
        val artifactSha = sha256Hex(artifactBytes)
        val artifactId = deterministicArtifactId(envelope, payloadFingerprint)
        val resourceUri = ServerResourceUri(envelope.tenantId, ResourceKind.ARTIFACTS, artifactId)

        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = "$artifactId.testdata-plan.json",
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
                wireArtifactKind = AiWireArtifactKind.TESTDATA_PLAN,
                aiIntent = AiIntent.TESTDATA_PLAN,
                originToolName = TOOL_NAME,
                ownerPrincipalId = principal.principalId,
                policyIntent = "ai.execute.$TOOL_NAME",
                sourceRefs = sourceRefs,
                targetDialect = parsed.targetDialect,
                provenance = AiArtifactProvenance.TestdataPlan(
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

    // ---- Prompt + Artifact -------------------------------------------

    private fun buildPrompt(parsed: ParsedArgs, sourceRefs: List<ServerResourceUri>): String =
        buildString {
            append("d-migrate testdata-plan\n")
            append("targetDialect=").append(parsed.targetDialect).append('\n')
            sourceRefs.forEach { append("ref=").append(it.render()).append('\n') }
            // Plan §5.6 Z. 836-837: Profile-Inhalt nicht im Prompt
            // referenzieren; nur Anwesenheit. Fingerprint erfasst den
            // exakten Wert.
            if (parsed.profileRef != null) append("profileRef=present\n")
            if (parsed.rulesJson != null) append("rules=present\n")
        }

    private fun canonicalPayloadJson(parsed: ParsedArgs): String =
        buildString {
            append("{\"targetDialect\":\"").append(parsed.targetDialect).append('"')
            append(",\"schemaRef\":\"").append(parsed.schemaRef).append('"')
            parsed.profileRef?.let { append(",\"profileRef\":\"").append(it).append('"') }
            append(",\"providerId\":\"").append(parsed.providerId.value).append('"')
            append(",\"model\":\"").append(parsed.model).append('"')
            parsed.rulesJson?.let { append(",\"rules\":").append(it) }
            append('}')
        }

    private fun serializeTestdataPlanArtifact(
        parsed: ParsedArgs,
        ok: ProviderInvocation.Success,
    ): ByteArray {
        val text = buildString {
            append("{\"summary\":\"testdata plan generated\"")
            append(",\"providerOutput\":\"").append(escapeJson(ok.success.output)).append('"')
            append(",\"targetDialect\":\"").append(parsed.targetDialect).append('"')
            append(",\"schemaRef\":\"").append(parsed.schemaRef).append('"')
            parsed.profileRef?.let { append(",\"profileRef\":\"").append(it).append('"') }
            append(",\"testdataPromptFingerprint\":\"").append(ok.allow.promptFingerprint).append('"')
            append(",\"testdataPayloadFingerprint\":\"").append(ok.allow.payloadFingerprint).append('"')
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
        val material = "ai-testdata-plan|${envelope.tenantId.value}|" +
            "${envelope.approvalKey}|$payloadFingerprint"
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

    @Suppress("LongParameterList")
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
        append(if (replayed) "replayed testdata plan" else "testdata plan generated")
        append("\"")
        append(",\"findings\":[]")
        // Plan §5.6: Wire-Form heisst testdataPlanArtifactId +
        // testdataPlanResourceUri (statt planRef/targetArtifactId).
        append(",\"testdataPlanArtifactId\":\"").append(artifactId).append('"')
        append(",\"testdataPlanResourceUri\":\"").append(resultRef).append('"')
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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- Parsed args --------------------------------------------------

    private data class ParsedArgs(
        val approvalKey: String,
        val approvalToken: String?,
        val targetDialect: String,
        val schemaRef: String,
        val profileRef: String?,
        val providerId: AiProviderId,
        val model: String,
        val rulesJson: String?,
    )

    companion object {
        const val TOOL_NAME: String = "testdata_plan"
        const val REQUIRED_SCOPE: String = "dmigrate:ai:execute"
        private const val ARTIFACT_ID_HEX_LENGTH: Int = 32
    }
}
