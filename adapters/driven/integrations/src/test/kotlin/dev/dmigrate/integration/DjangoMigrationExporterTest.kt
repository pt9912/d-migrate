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

class DjangoMigrationExporterTest : FunSpec({

    val exporter = DjangoMigrationExporter()

    fun identity(version: String = "0001", slug: String = "initial") = MigrationIdentity(
        tool = MigrationTool.DJANGO,
        dialect = DatabaseDialect.POSTGRESQL,
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
        up: MigrationDdlPayload = payload("CREATE TABLE users (id SERIAL PRIMARY KEY);"),
        rollback: MigrationRollback = MigrationRollback.NotRequested,
    ) = MigrationBundle(
        identity = identity,
        schema = SchemaDefinition(name = "My App", version = "1.0"),
        options = DdlGenerationOptions(),
        up = up,
        rollback = rollback,
    )

    test("tool is DJANGO") {
        exporter.tool shouldBe MigrationTool.DJANGO
    }

    // ── Single artifact ───────────────────────────

    test("produces single migration artifact") {
        val result = exporter.render(bundle())
        result.artifacts shouldHaveSize 1
    }

    test("file name follows Django convention") {
        val result = exporter.render(bundle())
        result.artifacts[0].relativePath.normalized shouldBe "0001_initial.py"
    }

    test("artifact kind is migration") {
        val result = exporter.render(bundle())
        result.artifacts[0].kind shouldBe "migration"
    }

    // ── Python structure ──────────────────────────

    test("imports django.db migrations") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "from django.db import migrations"
    }

    test("defines Migration class") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "class Migration(migrations.Migration):"
    }

    test("has empty dependencies") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "dependencies = []"
    }

    test("uses RunSQL operation") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "migrations.RunSQL("
    }

    // ── SQL content ───────────────────────────────

    test("up SQL embedded in RunSQL") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "CREATE TABLE users"
    }

    test("multiple statements preserved in order") {
        val up = payload(
            "CREATE TABLE a (id INT);",
            "CREATE TABLE b (id INT);",
        )
        val content = exporter.render(bundle(up = up)).artifacts[0].content
        val posA = content.indexOf("CREATE TABLE a")
        val posB = content.indexOf("CREATE TABLE b")
        (posA < posB) shouldBe true
    }

    // ── Rollback ──────────────────────────────────

    test("no reverse_sql when rollback not requested") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldNotContain "reverse_sql"
    }

    test("reverse_sql present when rollback requested") {
        val down = payload("DROP TABLE users;")
        val result = exporter.render(bundle(rollback = MigrationRollback.Requested(down)))
        result.artifacts[0].content shouldContain "reverse_sql"
        result.artifacts[0].content shouldContain "DROP TABLE users;"
    }

    // ── Python escaping ───────────────────────────

    test("backslashes escaped in Python output") {
        val up = payload("SELECT '\\n';")
        val result = exporter.render(bundle(up = up))
        result.artifacts[0].content shouldContain "\\\\n"
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
            deterministicSql = "-- this should not appear in Django output",
        )
        val content = exporter.render(bundle(up = up)).artifacts[0].content
        content shouldContain "CREATE TABLE a"
        content shouldContain "CREATE INDEX idx"
        content shouldNotContain "this should not appear"
    }
})
