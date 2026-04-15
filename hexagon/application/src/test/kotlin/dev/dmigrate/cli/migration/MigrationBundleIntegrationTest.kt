package dev.dmigrate.cli.migration

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.NoteType
import dev.dmigrate.migration.ArtifactRelativePath
import dev.dmigrate.migration.MigrationArtifact
import dev.dmigrate.migration.MigrationBundle
import dev.dmigrate.migration.MigrationRollback
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.MigrationVersionSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * End-to-end integration test that walks the complete Phase B path:
 * Resolver → Validator → SlugNormalizer → DdlNormalizer → Bundle → CollisionChecker.
 *
 * Individual components are unit-tested elsewhere; this test verifies
 * they compose correctly into a coherent bundle pipeline.
 */
class MigrationBundleIntegrationTest : FunSpec({

    val schema = SchemaDefinition(
        name = "My E-Commerce App",
        version = "1.0.0",
    )

    fun ddlResult(timestamp: String = "2026-04-14T12:00:00Z"): DdlResult {
        val header = "-- Target: postgresql | Generated: $timestamp"
        val body = "CREATE TABLE users (id SERIAL PRIMARY KEY);"
        return DdlResult(
            listOf(
                DdlStatement(header),
                DdlStatement(
                    body,
                    notes = listOf(
                        TransformationNote(NoteType.INFO, "T001", "users", "Auto-increment mapped to SERIAL"),
                    ),
                ),
            ),
            skippedObjects = listOf(
                SkippedObject("procedure", "calc_totals", "Procedures not supported for postgresql"),
            ),
        )
    }

    // ── Full pipeline: Flyway with CLI version ────────────────────

    test("Flyway bundle via CLI version — full pipeline") {
        val identity = MigrationIdentityResolver.resolve(
            tool = MigrationTool.FLYWAY,
            dialect = DatabaseDialect.POSTGRESQL,
            cliVersion = "2.0",
            schemaVersion = schema.version,
            schemaName = schema.name,
        )

        identity.version shouldBe "2.0"
        identity.versionSource shouldBe MigrationVersionSource.CLI
        identity.slug shouldBe "my_e_commerce_app"

        val upResult = ddlResult()
        val up = DdlNormalizer.normalize(upResult)

        up.deterministicSql shouldNotContain "Generated:"
        up.deterministicSql shouldContain "-- Target: postgresql"
        up.result shouldBe upResult

        val bundle = MigrationBundle(
            identity = identity,
            schema = schema,
            options = DdlGenerationOptions(),
            up = up,
            rollback = MigrationRollback.NotRequested,
        )

        bundle.identity.tool shouldBe MigrationTool.FLYWAY
        bundle.up.result.notes shouldHaveSize 1
        bundle.up.result.skippedObjects shouldHaveSize 1
    }

    // ── Full pipeline: Flyway with schema.version fallback ───────

    test("Flyway bundle via schema.version fallback — full pipeline") {
        val identity = MigrationIdentityResolver.resolve(
            tool = MigrationTool.FLYWAY,
            dialect = DatabaseDialect.MYSQL,
            cliVersion = null,
            schemaVersion = "v3.1.0",
            schemaName = "Shop",
        )

        identity.version shouldBe "3.1.0"
        identity.versionSource shouldBe MigrationVersionSource.SCHEMA
        identity.slug shouldBe "shop"

        val up = DdlNormalizer.normalize(ddlResult())
        val down = DdlNormalizer.normalize(
            DdlResult(listOf(DdlStatement("DROP TABLE users;")))
        )

        val bundle = MigrationBundle(
            identity = identity,
            schema = SchemaDefinition(name = "Shop", version = "v3.1.0"),
            options = DdlGenerationOptions(),
            up = up,
            rollback = MigrationRollback.Requested(down),
        )

        bundle.rollback shouldBe MigrationRollback.Requested(down)
        (bundle.rollback as MigrationRollback.Requested).down.deterministicSql shouldContain "DROP TABLE"
    }

    // ── Full pipeline: Django with collision check ────────────────

    test("Django bundle with artifact collision check — full pipeline") {
        val identity = MigrationIdentityResolver.resolve(
            tool = MigrationTool.DJANGO,
            dialect = DatabaseDialect.POSTGRESQL,
            cliVersion = "0001_initial",
            schemaVersion = schema.version,
            schemaName = schema.name,
        )

        identity.version shouldBe "0001_initial"
        identity.versionSource shouldBe MigrationVersionSource.CLI

        val up = DdlNormalizer.normalize(ddlResult())
        val bundle = MigrationBundle(
            identity = identity,
            schema = schema,
            options = DdlGenerationOptions(),
            up = up,
            rollback = MigrationRollback.NotRequested,
        )

        val artifacts = listOf(
            MigrationArtifact(ArtifactRelativePath.of("migrations/0001_initial.py"), "up", up.deterministicSql),
        )

        ArtifactCollisionChecker.findInRunCollisions(artifacts).shouldBeEmpty()
        ArtifactCollisionChecker.findExistingFileCollisions(artifacts, emptySet()).shouldBeEmpty()

        // Simulate existing file collision
        val existing = setOf("migrations/0001_initial.py")
        val collisions = ArtifactCollisionChecker.findExistingFileCollisions(artifacts, existing)
        collisions shouldHaveSize 1
        collisions[0].reason shouldContain "already exists"
    }

    // ── Determinism: same inputs → same bundle ───────────────────

    test("same inputs produce identical bundle across invocations") {
        fun buildBundle(): MigrationBundle {
            val id = MigrationIdentityResolver.resolve(
                MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, "1.0", null, "My App")
            val up = DdlNormalizer.normalize(ddlResult("2026-01-01T00:00:00Z"))
            return MigrationBundle(id, schema, DdlGenerationOptions(), up, MigrationRollback.NotRequested)
        }

        val a = buildBundle()
        val b = buildBundle()

        a.identity shouldBe b.identity
        a.up.deterministicSql shouldBe b.up.deterministicSql
    }

    test("different timestamps produce same deterministic SQL in bundle") {
        val id = MigrationIdentityResolver.resolve(
            MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, "1.0", null, "App")
        val upA = DdlNormalizer.normalize(ddlResult("2026-04-14T08:00:00Z"))
        val upB = DdlNormalizer.normalize(ddlResult("2026-04-14T23:59:59Z"))

        upA.deterministicSql shouldBe upB.deterministicSql
    }

    // ── Generator diagnostics survive the bundle ─────────────────

    test("notes and skippedObjects preserved through full pipeline") {
        val id = MigrationIdentityResolver.resolve(
            MigrationTool.LIQUIBASE, DatabaseDialect.POSTGRESQL, null, "1.0-rc1", "Shop")
        val result = ddlResult()
        val up = DdlNormalizer.normalize(result)
        val bundle = MigrationBundle(id, schema, DdlGenerationOptions(), up, MigrationRollback.NotRequested)

        bundle.up.result.notes shouldHaveSize 1
        bundle.up.result.notes[0].code shouldBe "T001"
        bundle.up.result.skippedObjects shouldHaveSize 1
        bundle.up.result.skippedObjects[0].name shouldBe "calc_totals"
    }

    // ── Failure paths ────────────────────────────────────────────

    test("Django without --version fails before bundle can be built") {
        shouldThrow<MigrationIdentityResolver.ResolutionException> {
            MigrationIdentityResolver.resolve(
                MigrationTool.DJANGO, DatabaseDialect.POSTGRESQL, null, "1.0", "App")
        }
    }

    test("in-run collision detected before write") {
        val id = MigrationIdentityResolver.resolve(
            MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, "1.0", null, "App")
        val up = DdlNormalizer.normalize(ddlResult())

        val artifacts = listOf(
            MigrationArtifact(ArtifactRelativePath.of("V1_0__app.sql"), "up", up.deterministicSql),
            MigrationArtifact(ArtifactRelativePath.of("V1_0__app.sql"), "duplicate", "-- oops"),
        )

        val collisions = ArtifactCollisionChecker.findInRunCollisions(artifacts)
        collisions shouldHaveSize 1
        collisions[0].reason shouldContain "Duplicate"
    }
})
