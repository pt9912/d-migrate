package dev.dmigrate.migration

import java.nio.file.Path

/**
 * A validated, normalized relative path for a migration artifact.
 *
 * The factory [of] first normalizes the input (resolving `.` segments,
 * collapsing separators) and then validates the result. This means
 * `a/./b.sql` is accepted and normalized to `a/b.sql`, while `../b.sql`
 * is rejected after normalization reveals the parent escape.
 *
 * Guarantees:
 * - Path is relative (not absolute)
 * - No parent escapes (`..`) after normalization
 * - Platform-independent canonical form (forward slashes)
 * - Only constructible via the validating [of] factory
 */
@JvmInline
value class ArtifactRelativePath private constructor(val path: Path) {

    /** Canonical string representation using forward slashes. */
    val normalized: String get() = path.toString().replace('\\', '/')

    companion object {
        /**
         * Creates a validated relative path. Throws [IllegalArgumentException]
         * for absolute paths or parent escapes.
         */
        fun of(raw: String): ArtifactRelativePath {
            require(raw.isNotBlank()) { "Artifact path must not be blank" }
            val normalized = Path.of(raw).normalize()
            require(!normalized.isAbsolute) {
                "ArtifactRelativePath must be relative, got: $raw"
            }
            require(normalized.none { it.toString() == ".." }) {
                "ArtifactRelativePath must not escape the output root: $raw"
            }
            return ArtifactRelativePath(normalized)
        }

        /** Creates a validated relative path from a [Path]. */
        fun of(raw: Path): ArtifactRelativePath = of(raw.toString())
    }
}
