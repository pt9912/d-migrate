package dev.dmigrate.cli.migration

import dev.dmigrate.migration.ArtifactRelativePath
import dev.dmigrate.migration.MigrationArtifact

/**
 * Collision between a planned artifact and either another planned
 * artifact or an existing file in the output directory.
 */
data class ArtifactCollision(
    val relativePath: ArtifactRelativePath,
    val reason: String,
)

/**
 * Checks for collisions before any file writes happen.
 *
 * All methods are I/O-free and operate on normalized relative paths.
 * The caller (CLI/Application layer) is responsible for resolving
 * existing paths from the filesystem and passing them in.
 */
object ArtifactCollisionChecker {

    /**
     * Checks for in-run duplicates (two artifacts with the same canonical path).
     */
    fun findInRunCollisions(artifacts: List<MigrationArtifact>): List<ArtifactCollision> {
        val seen = mutableMapOf<String, MigrationArtifact>()
        val collisions = mutableListOf<ArtifactCollision>()
        for (artifact in artifacts) {
            val canonical = artifact.relativePath.normalized
            val existing = seen[canonical]
            if (existing != null) {
                collisions += ArtifactCollision(
                    artifact.relativePath,
                    "Duplicate artifact path in this export run: $canonical (kinds: ${existing.kind}, ${artifact.kind})",
                )
            } else {
                seen[canonical] = artifact
            }
        }
        return collisions
    }

    /**
     * Checks for collisions against already-existing relative paths
     * in the output directory. The [existingPaths] set contains
     * normalized relative path strings as reported by the caller.
     */
    fun findExistingFileCollisions(
        artifacts: List<MigrationArtifact>,
        existingPaths: Set<String>,
    ): List<ArtifactCollision> {
        return artifacts.mapNotNull { artifact ->
            val canonical = artifact.relativePath.normalized
            if (canonical in existingPaths) {
                ArtifactCollision(
                    artifact.relativePath,
                    "File already exists: $canonical",
                )
            } else null
        }
    }
}
