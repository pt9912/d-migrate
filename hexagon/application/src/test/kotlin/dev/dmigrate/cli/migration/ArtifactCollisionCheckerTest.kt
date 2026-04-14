package dev.dmigrate.cli.migration

import dev.dmigrate.migration.ArtifactRelativePath
import dev.dmigrate.migration.MigrationArtifact
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class ArtifactCollisionCheckerTest : FunSpec({

    fun artifact(path: String, kind: String = "up") =
        MigrationArtifact(ArtifactRelativePath.of(path), kind, "-- sql")

    // ── In-run collisions ──────────────────────

    test("no duplicates → no collisions") {
        val artifacts = listOf(artifact("V1__up.sql"), artifact("V1__down.sql", "down"))
        ArtifactCollisionChecker.findInRunCollisions(artifacts).shouldBeEmpty()
    }

    test("duplicate paths detected") {
        val artifacts = listOf(
            artifact("V1__init.sql", "up"),
            artifact("V1__init.sql", "down"),
        )
        val collisions = ArtifactCollisionChecker.findInRunCollisions(artifacts)
        collisions shouldHaveSize 1
        collisions[0].reason shouldContain "Duplicate"
    }

    test("different paths no collision") {
        val artifacts = listOf(
            artifact("flyway/V1__init.sql"),
            artifact("flyway/U1__init.sql"),
        )
        ArtifactCollisionChecker.findInRunCollisions(artifacts).shouldBeEmpty()
    }

    // ── Filesystem collisions ──────────────────

    test("no existing files → no collisions") {
        val dir = Files.createTempDirectory("collision-test-")
        try {
            val artifacts = listOf(artifact("V1__init.sql"))
            ArtifactCollisionChecker.findFileSystemCollisions(dir, artifacts).shouldBeEmpty()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("existing file detected as collision") {
        val dir = Files.createTempDirectory("collision-test-")
        try {
            Files.writeString(dir.resolve("V1__init.sql"), "old content")
            val artifacts = listOf(artifact("V1__init.sql"))
            val collisions = ArtifactCollisionChecker.findFileSystemCollisions(dir, artifacts)
            collisions shouldHaveSize 1
            collisions[0].reason shouldContain "already exists"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("non-existing file no collision") {
        val dir = Files.createTempDirectory("collision-test-")
        try {
            Files.writeString(dir.resolve("V1__init.sql"), "old content")
            val artifacts = listOf(artifact("V2__next.sql"))
            ArtifactCollisionChecker.findFileSystemCollisions(dir, artifacts).shouldBeEmpty()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
