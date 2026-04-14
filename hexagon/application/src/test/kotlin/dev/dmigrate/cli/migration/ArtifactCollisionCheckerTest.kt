package dev.dmigrate.cli.migration

import dev.dmigrate.migration.ArtifactRelativePath
import dev.dmigrate.migration.MigrationArtifact
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain

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

    // ── Existing file collisions ───────────────

    test("no existing files → no collisions") {
        val artifacts = listOf(artifact("V1__init.sql"))
        ArtifactCollisionChecker.findExistingFileCollisions(artifacts, emptySet()).shouldBeEmpty()
    }

    test("existing file detected as collision") {
        val artifacts = listOf(artifact("V1__init.sql"))
        val existing = setOf("V1__init.sql")
        val collisions = ArtifactCollisionChecker.findExistingFileCollisions(artifacts, existing)
        collisions shouldHaveSize 1
        collisions[0].reason shouldContain "already exists"
    }

    test("non-matching existing file no collision") {
        val artifacts = listOf(artifact("V2__next.sql"))
        val existing = setOf("V1__init.sql")
        ArtifactCollisionChecker.findExistingFileCollisions(artifacts, existing).shouldBeEmpty()
    }

    test("nested path collision") {
        val artifacts = listOf(artifact("db/V1__init.sql"))
        val existing = setOf("db/V1__init.sql")
        val collisions = ArtifactCollisionChecker.findExistingFileCollisions(artifacts, existing)
        collisions shouldHaveSize 1
    }
})
