package dev.dmigrate.server.core.ai

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Instant

/**
 * LF-017 / LF-024 / LN-030 / LN-031— durable Provenance-Metadaten für ein
 * KI-Artefakt.
 *
 * LF-017 / LF-024 / LN-030 / LN-031 listet die Pflichtfelder; dieser Record
 * spiegelt sie 1:1 wider plus das passende
 * [AiArtifactProvenance]-Sub-Pattern. Wird zusammen mit dem
 * `ArtifactRecord` (Core-`ArtifactKind.OTHER` plus
 * `wireArtifactKind`-Marker) im Artefakt-Publish-Schritt
 * geschrieben (LF-017 / LF-024 / LN-030 / LN-031/f).
 *
 * LF-017 / LF-024 / LN-030 / LN-031 Store-Vertrag:
 *
 * - `save` ist atomar zusammen mit dem Artefakt-Publish oder vor
 *   der Freigabe der `resultRef`.
 * - Lookup ueber `(tenantId, artifactId)` und
 *   `(tenantId, resourceUri)`.
 * - `wireArtifactKind`/`aiIntent` sind Wert-Pflicht.
 * - Cleanup entfernt Metadata mit dem Artefakt; orphaned Metadata
 *   wird als Resource-Read-Fehler gemeldet.
 *
 * **Verboten in jedem Feld**: Roh-Secrets, expandierte API-Keys,
 * Connection-Secrets. LF-017 / LF-024 / LN-030 / LN-031: Audit speichert Fingerprints,
 * keine Secrets.
 *
 * @param tenantId Eigentuemer-Tenant. Pflicht-Lookup-Komponente.
 * @param artifactId stabile Artefakt-ID (= `ArtifactRecord.managedArtifact.artifactId`).
 * @param resourceUri Tenant-scoped Resource-URI; muss
 *   `ResourceKind.ARTIFACTS` sein. Wird als Sekundär-Lookup-Key
 *   verwendet.
 * @param wireArtifactKind eines aus [AiWireArtifactKind.ALL].
 * @param aiIntent eines aus [AiIntent.ALL]; passt zu [originToolName].
 * @param originToolName MCP-Tool-Name, der das Artefakt erzeugt
 *   hat. In der Regel identisch zu `aiIntent`, bleibt separat
 *   fuer Audit-Spuren bei zukuenftigen Tool-Aliasen.
 * @param ownerPrincipalId Caller, der das Artefakt erzeugt hat
 *   (= `PrincipalContext.principalId` zur Erzeugungszeit).
 * @param policyIntent LF-017 / LF-024 / LN-030 / LN-031: Approval-Scope-Identifier
 *   (etwa `"ai.execute.procedure_transform_plan"` oder ein
 *   Policy-Rule-Name). Wird ins Audit gespiegelt.
 * @param sourceRefs Tenant-scoped Resource-URIs, aus denen das
 *   Artefakt erzeugt wurde — etwa `schemaRef`, `profileRef`,
 *   `diffRef`, `procedureRef`, `artifactRef`. LF-017 / LF-024 / LN-030 / LN-031: bei
 *   Execute-Artefakten kommen Source-Refs aus der Plan-Provenance,
 *   nicht aus dem Execute-Payload.
 * @param targetDialect zugehoeriger `DatabaseDialect`-Wert (etwa
 *   `"POSTGRESQL"`).
 * @param provenance operations-spezifische Fingerprints.
 * @param providerName / [model] / [modelVersion] — Provider-/
 *   Modell-Identitaet aus dem produzierenden Provider-Aufruf.
 *   LF-017 / LF-024 / LN-030 / LN-031: keine Endpoints, keine secretRef-Werte.
 * @param outputFingerprint hex-codierter SHA-256 ueber den
 *   Provider-Output. Bindet die Resource-Bytes an eine
 *   nachpruefbare Identitaet.
 * @param createdAt Erzeugungszeitpunkt; bleibt nach Replay stabil.
 */
data class AiArtifactMetadata(
    val tenantId: TenantId,
    val artifactId: String,
    val resourceUri: ServerResourceUri,
    val wireArtifactKind: String,
    val aiIntent: String,
    val originToolName: String,
    val ownerPrincipalId: PrincipalId,
    val policyIntent: String,
    val sourceRefs: List<ServerResourceUri>,
    val targetDialect: String,
    val provenance: AiArtifactProvenance,
    val providerName: String,
    val model: String,
    val modelVersion: String?,
    val outputFingerprint: String,
    val createdAt: Instant,
) {
    init {
        require(artifactId.isNotBlank()) { "artifactId must not be blank" }
        require(resourceUri.tenantId == tenantId) {
            "resourceUri.tenantId must match metadata tenantId"
        }
        require(resourceUri.kind == ResourceKind.ARTIFACTS) {
            "resourceUri.kind must be ARTIFACTS, was ${resourceUri.kind}"
        }
        require(resourceUri.id == artifactId) {
            "resourceUri.id must match artifactId"
        }
        require(wireArtifactKind in AiWireArtifactKind.ALL) {
            "wireArtifactKind must be one of ${AiWireArtifactKind.ALL}, was '$wireArtifactKind'"
        }
        require(aiIntent in AiIntent.ALL) {
            "aiIntent must be one of ${AiIntent.ALL}, was '$aiIntent'"
        }
        require(originToolName.isNotBlank()) { "originToolName must not be blank" }
        require(policyIntent.isNotBlank()) { "policyIntent must not be blank" }
        require(targetDialect.isNotBlank()) { "targetDialect must not be blank" }
        require(providerName.isNotBlank()) { "providerName must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(modelVersion?.isNotBlank() != false) {
            "modelVersion must be non-blank or null"
        }
        require(outputFingerprint.length == FP_LEN) {
            "outputFingerprint must be a $FP_LEN-char hex SHA-256"
        }
        // LF-017 / LF-024 / LN-030 / LN-031 verbindliches Mapping: aiIntent ↔ wireArtifactKind ↔ Provenance.
        validateIntentKindProvenance()
    }

    private fun validateIntentKindProvenance() {
        when (aiIntent) {
            AiIntent.PROCEDURE_TRANSFORM_PLAN -> {
                require(wireArtifactKind == AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN) {
                    "aiIntent=${AiIntent.PROCEDURE_TRANSFORM_PLAN} requires " +
                        "wireArtifactKind=${AiWireArtifactKind.PROCEDURE_TRANSFORM_PLAN}"
                }
                require(provenance is AiArtifactProvenance.Plan) {
                    "aiIntent=${AiIntent.PROCEDURE_TRANSFORM_PLAN} requires Plan provenance"
                }
            }
            AiIntent.PROCEDURE_TRANSFORM_EXECUTE -> {
                require(wireArtifactKind == AiWireArtifactKind.PROCEDURE_TRANSFORM_OUTPUT) {
                    "aiIntent=${AiIntent.PROCEDURE_TRANSFORM_EXECUTE} requires " +
                        "wireArtifactKind=${AiWireArtifactKind.PROCEDURE_TRANSFORM_OUTPUT}"
                }
                require(provenance is AiArtifactProvenance.Execute) {
                    "aiIntent=${AiIntent.PROCEDURE_TRANSFORM_EXECUTE} requires Execute provenance"
                }
            }
            AiIntent.TESTDATA_PLAN -> {
                require(wireArtifactKind == AiWireArtifactKind.TESTDATA_PLAN) {
                    "aiIntent=${AiIntent.TESTDATA_PLAN} requires " +
                        "wireArtifactKind=${AiWireArtifactKind.TESTDATA_PLAN}"
                }
                require(provenance is AiArtifactProvenance.TestdataPlan) {
                    "aiIntent=${AiIntent.TESTDATA_PLAN} requires TestdataPlan provenance"
                }
            }
            AiIntent.TESTDATA_EXECUTE -> {
                require(
                    wireArtifactKind == AiWireArtifactKind.GENERATED_TESTDATA ||
                        wireArtifactKind == AiWireArtifactKind.SEED_DATA_BUNDLE
                ) {
                    "aiIntent=${AiIntent.TESTDATA_EXECUTE} requires wireArtifactKind in " +
                        "{${AiWireArtifactKind.GENERATED_TESTDATA}," +
                        "${AiWireArtifactKind.SEED_DATA_BUNDLE}}"
                }
                require(provenance is AiArtifactProvenance.TestdataExecute) {
                    "aiIntent=${AiIntent.TESTDATA_EXECUTE} requires TestdataExecute provenance"
                }
            }
        }
    }

    private companion object {
        const val FP_LEN: Int = 64
    }
}
