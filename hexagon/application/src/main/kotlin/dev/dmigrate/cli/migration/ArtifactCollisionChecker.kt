package dev.dmigrate.cli.migration

import dev.dmigrate.migration.ArtifactRelativePath
import dev.dmigrate.migration.MigrationArtifact
import java.nio.file.Files
import java.nio.file.Path

/**
 * Collision between a planned artifact and either another planned
 * artifact or an existing file on disk.
 */
data class ArtifactCollision(
    val relativePath: ArtifactRelativePath,
    val reason: String,
)

/**
 * Checks for collisions before any file writes happen.
 *
 * Detects:
 * - Two artifacts in the same run targeting the same relative path
 * - A planned artifact colliding with an existing file in the output directory
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
     * Checks for collisions against existing files in the output directory.
     */
    fun findFileSystemCollisions(
        outputDir: Path,
        artifacts: List<MigrationArtifact>,
    ): List<ArtifactCollision> {
        return artifacts.mapNotNull { artifact ->
            val target = outputDir.resolve(artifact.relativePath.path)
            if (Files.exists(target)) {
                ArtifactCollision(
                    artifact.relativePath,
                    "File already exists: $target",
                )
            } else null
        }
    }
}
