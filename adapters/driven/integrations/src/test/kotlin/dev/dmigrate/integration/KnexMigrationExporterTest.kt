package dev.dmigrate.integration

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.migration.MigrationBundle
import dev.dmigrate.migration.MigrationDdlPayload
import dev.dmigrate.migration.MigrationIdentity
import dev.dmigrate.migration.MigrationRollback
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.MigrationVersionSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class KnexMigrationExporterTest : FunSpec({

    val exporter = KnexMigrationExporter()

    fun identity(version: String = "20260414120000", slug: String = "create_users") =
        MigrationIdentity(
            tool = MigrationTool.KNEX,
            dialect = DatabaseDialect.SQLITE,
            version = version,
            versionSource = MigrationVersionSource.CLI,
            slug = slug,
        )

    fun payload(vararg sqls: String): MigrationDdlPayload {
        val statements = sqls.map { DdlStatement(it) }
        return MigrationDdlPayload(
            result = DdlResult(statements),
            deterministicSql = statements.joinToString("\n\n") { it.sql },
        )
    }

    fun bundle(
        identity: MigrationIdentity = identity(),
        up: MigrationDdlPayload = payload("CREATE TABLE users (id INTEGER PRIMARY KEY);"),
        rollback: MigrationRollback = MigrationRollback.NotRequested,
    ) = MigrationBundle(
        identity = identity,
        schema = SchemaDefinition(name = "My App", version = "1.0"),
        options = DdlGenerationOptions(),
        up = up,
        rollback = rollback,
    )

    test("tool is KNEX") {
        exporter.tool shouldBe MigrationTool.KNEX
    }

    // ── Single artifact ───────────────────────────

    test("produces single migration artifact") {
        val result = exporter.render(bundle())
        result.artifacts shouldHaveSize 1
    }

    test("file name follows Knex convention — no separate slug") {
        val result = exporter.render(bundle())
        result.artifacts[0].relativePath.normalized shouldBe "20260414120000.js"
    }

    test("version with slug suffix does not produce double-slug") {
        val result = exporter.render(bundle(
            identity = identity(version = "20260414120000_create_users"),
        ))
        result.artifacts[0].relativePath.normalized shouldBe "20260414120000_create_users.js"
    }

    test("artifact kind is migration") {
        val result = exporter.render(bundle())
        result.artifacts[0].kind shouldBe "migration"
    }

    // ── JavaScript structure ──────────────────────

    test("contains exports.up") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "exports.up = async function(knex)"
    }

    test("uses knex.raw for statements") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "await knex.raw("
    }

    test("contains SQL in knex.raw call") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "CREATE TABLE users"
    }

    // ── Multiple statements ───────────────────────

    test("multiple statements produce sequential knex.raw calls") {
        val up = payload(
            "CREATE TABLE a (id INT);",
            "CREATE TABLE b (id INT);",
        )
        val content = exporter.render(bundle(up = up)).artifacts[0].content
        val posA = content.indexOf("CREATE TABLE a")
        val posB = content.indexOf("CREATE TABLE b")
        (posA < posB) shouldBe true

        // Two separate knex.raw calls
        content.split("await knex.raw(").size shouldBe 3 // 1 before + 2 calls
    }

    // ── Rollback ──────────────────────────────────

    test("no exports.down when rollback not requested") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldNotContain "exports.down"
    }

    test("exports.down present when rollback requested") {
        val down = payload("DROP TABLE users;")
        val result = exporter.render(bundle(rollback = MigrationRollback.Requested(down)))
        result.artifacts[0].content shouldContain "exports.down = async function(knex)"
        result.artifacts[0].content shouldContain "DROP TABLE users;"
    }

    // ── JavaScript escaping ───────────────────────

    test("backticks escaped in knex.raw") {
        val up = payload("SELECT `id` FROM t;")
        val result = exporter.render(bundle(up = up))
        result.artifacts[0].content shouldContain "\\`id\\`"
    }

    test("template literals escaped") {
        val up = payload("SELECT '\${var}';")
        val result = exporter.render(bundle(up = up))
        result.artifacts[0].content shouldContain "\\\${var}"
    }

    // ── Header comment ────────────────────────────

    test("header comment includes version and target") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "Migration: 20260414120000 create_users"
        result.artifacts[0].content shouldContain "Target: sqlite"
    }

    // ── Determinism ───────────────────────────────

    test("identical inputs produce identical output") {
        val a = exporter.render(bundle())
        val b = exporter.render(bundle())
        a shouldBe b
    }

    // ── Statement sequence from result.statements ─

    test("uses result.statements not deterministicSql") {
        val up = MigrationDdlPayload(
            result = DdlResult(listOf(
                DdlStatement("CREATE TABLE a (id INT);"),
                DdlStatement("CREATE INDEX idx ON a(id);"),
            )),
            deterministicSql = "-- this should not appear in Knex output",
        )
        val content = exporter.render(bundle(up = up)).artifacts[0].content
        content shouldContain "CREATE TABLE a"
        content shouldContain "CREATE INDEX idx"
        content shouldNotContain "this should not appear"
    }
})
