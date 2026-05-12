package dev.dmigrate.core.diff.migration.overlay

/**
 * Versioned migration overlay contract.
 *
 * Overlay files are external operator input for risky migration decisions
 * such as USING expressions or rename mappings. The core contract keeps the
 * document signed by schema fingerprints and a canonical JSON hash before any
 * renderer or planner may consume the entries.
 */
data class MigrationOverlay(
    val formatVersion: String = FORMAT_VERSION,
    val overlayKind: String,
    val sourceFingerprint: String,
    val targetFingerprint: String,
    val dialect: String,
    val entries: List<MigrationOverlayEntry>,
    val createdAt: String,
    val createdByVersion: String,
    val overlayHash: String? = null,
    val producerMetadata: Map<String, String> = emptyMap(),
) {
    fun withComputedHash(): MigrationOverlay =
        copy(overlayHash = MigrationOverlayCanonicalJson.computeHash(this))

    companion object {
        const val FORMAT_VERSION: String = "migration-overlay.v1"
    }
}

object MigrationOverlayKinds {
    const val USING_EXPRESSION: String = "using-expression"
    const val RENAME_MAPPING: String = "rename-mapping"
}

sealed interface MigrationOverlayEntry {
    val id: String
    val kind: String
    val requiredFeatures: Set<String>
}

data class OverlayText(
    val value: String,
    val secret: Boolean = false,
)

data class UsingExpressionOverlayEntry(
    override val id: String,
    val table: String,
    val column: String,
    val expression: OverlayText,
    override val requiredFeatures: Set<String> = emptySet(),
) : MigrationOverlayEntry {
    override val kind: String = MigrationOverlayKinds.USING_EXPRESSION
}

data class RenameMappingOverlayEntry(
    override val id: String,
    val objectType: String,
    val fromName: String,
    val toName: String,
    override val requiredFeatures: Set<String> = emptySet(),
) : MigrationOverlayEntry {
    override val kind: String = MigrationOverlayKinds.RENAME_MAPPING
}
