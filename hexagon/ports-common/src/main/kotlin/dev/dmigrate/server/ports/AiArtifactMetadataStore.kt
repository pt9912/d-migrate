package dev.dmigrate.server.ports

import dev.dmigrate.server.core.ai.AiArtifactMetadata
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * Phase G § 5.4 + § 6 G.6 (G.6.b) — durable Provenance-Store für
 * KI-Artefakte.
 *
 * Plan-§-5.4-Pfad-A (G.1-Bestandsaufnahme §3.2): wir bleiben bei
 * `ArtifactKind.OTHER` für KI-Artefakte und persistieren die
 * KI-Metadaten in diesem separaten Store. Vorteile:
 *
 * - kein Bloat im `ArtifactRecord`-Schema, das Phase A-F nicht
 *   kennt;
 * - `ArtifactStore.list()` filtert weiterhin nach Core-`ArtifactKind`,
 *   während `findByArtifactId(...)` hier den KI-spezifischen
 *   Filter macht;
 * - Atomarität: der Tool-Handler schreibt
 *   `ArtifactStore.save(record)` und `AiArtifactMetadataStore.save(metadata)`
 *   im selben durable Commit (G.6.d/e/f).
 *
 * Plan §5.4 Z. 748-752 Vertrag:
 *
 * - `save` ist atomar zusammen mit dem Artefakt-Publish oder vor
 *   der Freigabe der `resultRef`.
 * - Lookup über `(tenantId, artifactId)` und
 *   `(tenantId, resourceUri)`.
 * - Cleanup entfernt Metadata mit dem Artefakt; orphaned Metadata
 *   ist Resource-Read-Fehler.
 */
interface AiArtifactMetadataStore {

    /**
     * Speichert [metadata]. Idempotent pro
     * `(tenantId, artifactId)` — gleiches `metadata` wird
     * stillschweigend akzeptiert; abweichendes `metadata` für
     * dasselbe Tupel liefert [SaveAiArtifactMetadataOutcome.Conflict].
     */
    fun save(metadata: AiArtifactMetadata): SaveAiArtifactMetadataOutcome

    fun findByArtifactId(tenantId: TenantId, artifactId: String): AiArtifactMetadata?

    fun findByResourceUri(tenantId: TenantId, resourceUri: ServerResourceUri): AiArtifactMetadata?

    /**
     * Plan §5.4 Z. 752: Cleanup-Hook, der mit dem Artefakt-Delete
     * gebunden ist. Tool-Retention (Phase F's
     * `ArtifactRetentionService`) ruft diese Methode auf, sobald
     * der zugehörige `ArtifactRecord` gelöscht wird, damit kein
     * orphaned Metadata zurückbleibt.
     *
     * @return `true` wenn ein Eintrag gelöscht wurde, sonst
     *   `false` (z.B. weil der Eintrag gar nicht existierte —
     *   harmlos, weil idempotent).
     */
    fun deleteByArtifactId(tenantId: TenantId, artifactId: String): Boolean
}

/**
 * Phase G § 5.4 + § 6 G.6 (G.6.b) — typed Save-Outcome.
 *
 * Unterscheidet zwischen idempotentem No-Op-Replay und einem
 * harten Konflikt zweier verschiedener Metadaten-Sätze unter
 * derselben `(tenantId, artifactId)`. Tool-Handler in G.6.d/e/f
 * mappen [Conflict] auf `INTERNAL_AGENT_ERROR` (Server-State-
 * Drift, niemals Caller-Fehler — der Caller hat denselben
 * `approvalKey` benutzt, aber der Store sieht abweichende
 * Provenance).
 */
sealed interface SaveAiArtifactMetadataOutcome {

    val metadata: AiArtifactMetadata

    data class Saved(override val metadata: AiArtifactMetadata) : SaveAiArtifactMetadataOutcome

    /**
     * Identische Metadata war bereits durabel persistiert;
     * idempotenter No-Op.
     */
    data class AlreadyExists(
        override val metadata: AiArtifactMetadata,
    ) : SaveAiArtifactMetadataOutcome

    /**
     * Ein anderer Metadata-Satz für dasselbe
     * `(tenantId, artifactId)` ist bereits gespeichert. Der
     * Tool-Handler darf den Caller nicht überschreiben — Plan
     * §5.4: Artefakt-Provenance ist immutable.
     *
     * @param existing der bereits gespeicherte Eintrag.
     * @param attempted der neue, abweichende Versuch (für
     *   Audit/Logging).
     */
    data class Conflict(
        val existing: AiArtifactMetadata,
        val attempted: AiArtifactMetadata,
    ) : SaveAiArtifactMetadataOutcome {
        override val metadata: AiArtifactMetadata get() = existing
    }
}
