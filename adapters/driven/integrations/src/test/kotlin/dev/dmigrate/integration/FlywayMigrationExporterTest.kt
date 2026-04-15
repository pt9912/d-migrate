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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith

class FlywayMigrationExporterTest : FunSpec({

    val exporter = FlywayMigrationExporter()

    fun identity(version: String = "1.0", slug: String = "my_app") = MigrationIdentity(
        tool = MigrationTool.FLYWAY,
        dialect = DatabaseDialect.POSTGRESQL,
        version = version,
        versionSource = MigrationVersionSource.CLI,
        slug = slug,
    )

    fun payload(sql: String = "CREATE TABLE users (id SERIAL PRIMARY KEY);") =
        MigrationDdlPayload(
            result = DdlResult(listOf(DdlStatement(sql))),
            deterministicSql = sql,
        )

    fun bundle(
        identity: MigrationIdentity = identity(),
        up: MigrationDdlPayload = payload(),
        rollback: MigrationRollback = MigrationRollback.NotRequested,
    ) = MigrationBundle(
        identity = identity,
        schema = SchemaDefinition(name = "My App", version = "1.0"),
        options = DdlGenerationOptions(),
        up = up,
        rollback = rollback,
    )

    test("tool is FLYWAY") {
        exporter.tool shouldBe MigrationTool.FLYWAY
    }

    // ── Up-only ───────────────────────────────────

    test("up-only produces single artifact") {
        val result = exporter.render(bundle())
        result.artifacts shouldHaveSize 1
        result.exportNotes.shouldBeEmpty()
    }

    test("up artifact has correct file name") {
        val result = exporter.render(bundle())
        result.artifacts[0].relativePath.normalized shouldBe "V1.0__my_app.sql"
    }

    test("up artifact has correct kind") {
        val result = exporter.render(bundle())
        result.artifacts[0].kind shouldBe "up"
    }

    test("up artifact contains deterministic SQL with trailing newline") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldBe "CREATE TABLE users (id SERIAL PRIMARY KEY);\n"
    }

    test("up content ends with LF") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldEndWith "\n"
    }

    // ── With rollback ─────────────────────────────

    test("rollback produces two artifacts") {
        val down = payload("DROP TABLE users;")
        val result = exporter.render(bundle(rollback = MigrationRollback.Requested(down)))
        result.artifacts shouldHaveSize 2
    }

    test("undo artifact has U prefix") {
        val down = payload("DROP TABLE users;")
        val result = exporter.render(bundle(rollback = MigrationRollback.Requested(down)))
        result.artifacts[1].relativePath.normalized shouldBe "U1.0__my_app.sql"
        result.artifacts[1].kind shouldBe "undo"
    }

    test("undo artifact contains down SQL") {
        val down = payload("DROP TABLE users;")
        val result = exporter.render(bundle(rollback = MigrationRollback.Requested(down)))
        result.artifacts[1].content shouldBe "DROP TABLE users;\n"
    }

    test("rollback adds Flyway Teams edition note") {
        val down = payload("DROP TABLE users;")
        val result = exporter.render(bundle(rollback = MigrationRollback.Requested(down)))
        result.exportNotes shouldHaveSize 1
        result.exportNotes[0].code shouldBe "TE-FW-001"
        result.exportNotes[0].message shouldContain "Teams"
    }

    // ── Determinism ───────────────────────────────

    test("identical inputs produce identical output") {
        val a = exporter.render(bundle())
        val b = exporter.render(bundle())
        a shouldBe b
    }

    // ── Version variants ──────────────────────────

    test("dotted version in file name") {
        val result = exporter.render(bundle(identity = identity(version = "2.1.0")))
        result.artifacts[0].relativePath.normalized shouldBe "V2.1.0__my_app.sql"
    }

    // ── Multi-statement SQL ───────────────────────

    test("multi-statement SQL preserved") {
        val sql = "CREATE TABLE a (id INT);\n\nCREATE TABLE b (id INT);"
        val result = exporter.render(bundle(up = payload(sql)))
        result.artifacts[0].content shouldContain "CREATE TABLE a"
        result.artifacts[0].content shouldContain "CREATE TABLE b"
    }
})
