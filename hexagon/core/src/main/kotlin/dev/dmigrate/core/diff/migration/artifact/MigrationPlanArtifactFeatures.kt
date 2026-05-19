package dev.dmigrate.core.diff.migration.artifact

/**
 * Canonical registry of versioned-semantics identifiers used in
 * [MigrationPlanArtifact.requiredFeatures] and
 * [MigrationPlanArtifact.semanticExtensions].
 *
 * Constants land here when a new payload introduces semantics that
 * consumers MUST opt into; the validator side reuses these identifiers
 * via [MigrationPlanArtifactValidationContext.supportedRequiredFeatures]
 * and `.supportedSemanticExtensions` so producer / consumer stay in
 * sync on a single string.
 *
 * Naming convention: `<feature>.v<n>` where `<n>` bumps when the
 * payload shape (not just an additive field) changes in a way that
 * an older consumer cannot interpret correctly.
 */
object MigrationPlanArtifactFeatures {

    /**
     * F.4 Sub-Slice E (2026-05-19): identifies the
     * `MigrationPlanArtifact.renameProjections` payload contract.
     *
     * Producers MUST add this to
     * [MigrationPlanArtifact.semanticExtensions] whenever
     * [MigrationPlanArtifact.renameProjections] is non-empty;
     * [MigrationPlanArtifact.withRenameProjectionExtension] is the
     * convenience entry point that auto-applies the rule before
     * signing.
     *
     * Consumers MUST list this string in
     * [MigrationPlanArtifactValidationContext.supportedSemanticExtensions]
     * before accepting any artifact whose `renameProjections` field is
     * non-empty, AND treat the operations referenced in
     * [MigrationPlanArtifactRenameProjection.fallbackOperationIds] as
     * part of a logical rename rather than ordinary `Drop*` / `Create*`
     * pairs.
     *
     * If the extension is missing the validator surfaces
     * [MigrationPlanArtifactDiagnostics.RENAME_PROJECTIONS_REQUIRE_EXTENSION]
     * (producer bug) or
     * [MigrationPlanArtifactDiagnostics.UNKNOWN_SEMANTIC_EXTENSION]
     * (consumer does not support the contract).
     */
    const val RENAME_PROJECTIONS_V1: String = "rename-projections.v1"
}
