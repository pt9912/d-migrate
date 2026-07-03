package dev.dmigrate.core.diff.migration.artifact

/**
 * Public, versioned plan artifact contract.
 *
 * This is deliberately narrower than the internal DiffResult: it carries
 * stable identifiers, diagnostics, reversibility, and rendered-SQL bindings
 * without embedding arbitrary SQL or secret-bearing payloads.
 */
data class MigrationPlanArtifact(
    val formatVersion: String = FORMAT_VERSION,
    val dMigrateVersion: String,
    val sourceFingerprint: String,
    val targetFingerprint: String,
    /**
     * Fingerprint-Algorithmus, mit dem source-/targetFingerprint berechnet
     * wurden (`MigrationFingerprint.ALGORITHM`) — ohne diese Kennung sind die
     * persistierten Werte extern nicht interpretierbar (Konsistenz zum
     * Rollback-Artefakt; Typ-Kanonisierungs-Slice AP3).
     */
    val fingerprintAlgorithm: String,
    val dialect: String,
    val operations: List<MigrationPlanArtifactOperation>,
    val diagnostics: List<MigrationPlanArtifactDiagnostic>,
    val reversibilitySummary: MigrationPlanReversibilitySummary,
    val requiredFeatures: Set<String> = emptySet(),
    val semanticExtensions: Set<String> = emptySet(),
    val renderedStatements: List<MigrationPlanRenderedStatement> = emptyList(),
    /**
     * F.4 Sub-Slice E (2026-05-19): versioned rename-projection carrier.
     * Surfaces overlay-bound rename candidates (table / column / view /
     * trigger / function / procedure / sequence) and the corresponding
     * Drop+Create fallback they were lowered to (when applicable). The
     * field is gated behind the [MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1]
     * semantic extension so old consumers that do not understand the
     * fallback contract reject the artifact instead of running the
     * Drop+Create pair as an ordinary destructive change.
     */
    val renameProjections: List<MigrationPlanArtifactRenameProjection> = emptyList(),
    val createdAt: String,
    val artifactHash: String? = null,
    val producerMetadata: Map<String, String> = emptyMap(),
) {
    fun withComputedHash(): MigrationPlanArtifact =
        copy(artifactHash = MigrationPlanArtifactCanonicalJson.computeHash(this))

    /**
     * Convenience: if [renameProjections] is non-empty, returns a copy
     * with the [MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1]
     * extension added to [semanticExtensions]. Otherwise returns this
     * unchanged. Producers should call this before [withComputedHash]
     * so the semantic-extension gate is part of the signed payload.
     */
    fun withRenameProjectionExtension(): MigrationPlanArtifact {
        if (renameProjections.isEmpty()) return this
        if (MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1 in semanticExtensions) return this
        return copy(
            semanticExtensions = semanticExtensions + MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1,
        )
    }

    companion object {
        const val FORMAT_VERSION: String = "migration-plan.v1"
    }
}

/**
 * F.4 Sub-Slice E rename-projection artifact entry. Mirrors
 * [dev.dmigrate.core.diff.migration.RenameProjectionReport] but
 * narrowed to the public contract: `automatic` / `explicit` /
 * `blockers` projection details stay in the internal report carrier
 * for now (the artifact contract documents only the rename outcome
 * and its operation-id bindings).
 *
 * Consumers MUST treat any operation listed in
 * [fallbackOperationIds] as part of a logical rename, not as an
 * ordinary `Drop*` / `Create*` pair. The
 * [MigrationPlanArtifactFeatures.RENAME_PROJECTIONS_V1] extension
 * exists precisely so an old consumer that cannot honour this rule
 * is forced to reject the artifact.
 *
 * Field shape:
 *
 * - [candidateId] — stable per-overlay-entry candidate id (matches
 *   the mapper-internal rename candidate).
 * - [objectType] — the rename target kind
 *   (`TABLE`/`COLUMN`/`VIEW`/`TRIGGER`/`FUNCTION`/`PROCEDURE`/`SEQUENCE`).
 * - [fromPath] / [toPath] — pre- / post-rename visible identity
 *   paths. Single-element for schema-wide objects, `[table, name]`
 *   for column / trigger / index-style children.
 * - [overlaySource] / [overlayEntryId] / [overlayHash] — provenance
 *   of the authorising rename-mapping overlay entry. `overlayEntryId`
 *   is mandatory because a single overlay can hold multiple rename
 *   mappings.
 * - [renameOperationId] — when non-null, the id of the emitted
 *   `Rename*` operation. When null, the rename was lowered to
 *   Drop+Create and [fallbackOperationIds] / [fallbackReason] carry
 *   the substitute contract.
 * - [fallbackOperationIds] — operation ids of the `Drop*` / `Create*`
 *   ops emitted in place of a native rename. Empty for native
 *   renames.
 * - [fallbackReason] — short human-readable rationale for the
 *   fallback (e.g. "MySQL has no `ALTER TRIGGER … RENAME`"). Null
 *   for native renames.
 */
data class MigrationPlanArtifactRenameProjection(
    val candidateId: String,
    val objectType: String,
    val fromPath: List<String>,
    val toPath: List<String>,
    val overlaySource: String,
    val overlayEntryId: String,
    val overlayHash: String? = null,
    val renameOperationId: String? = null,
    val fallbackOperationIds: List<String> = emptyList(),
    val fallbackReason: String? = null,
)

data class MigrationPlanArtifactOperation(
    val id: String,
    val kind: String,
    val objectType: String,
    val objectPath: List<String>,
    val phase: String,
    val reversibility: String,
    val upRisk: MigrationPlanRisk,
    val downRisk: MigrationPlanRisk? = null,
)

data class MigrationPlanRisk(
    val destructive: Boolean = false,
    val dataLossPossible: Boolean = false,
    val requiresTableRewrite: Boolean = false,
    val requiresManualConfirmation: Boolean = false,
    val dataTransformationMode: String = "NONE",
    val dataTransformationModelVersion: String? = null,
    val dataTransformationModelId: String? = null,
)

data class MigrationPlanArtifactDiagnostic(
    val code: String,
    val severity: String,
    val operationId: String? = null,
)

data class MigrationPlanReversibilitySummary(
    val fullyReversible: Boolean,
    val manualRequiredOperationIds: List<String> = emptyList(),
    val notReversibleOperationIds: List<String> = emptyList(),
)

/**
 * One rendered SQL statement projected into the public plan artifact.
 *
 * [transactionScope] mirrors the runtime
 * [dev.dmigrate.driver.migration.TransactionScope] enum as a string
 * — the canonical values are `RUNNER_OWNED`, `STREAM_OWNED` and
 * `NO_TRANSACTION` (`enum.name`). The field is typed as `String`
 * (not the enum directly) so artifact consumers can read forward-
 * compatibly: a future runtime that adds a fourth scope value
 * surfaces as an unknown string rather than a deserialisation
 * failure, and the artifact validator can warn without rejecting.
 */
data class MigrationPlanRenderedStatement(
    val statementId: String,
    val operationIds: List<String>,
    val sqlHash: String,
    val transactionScope: String,
)
