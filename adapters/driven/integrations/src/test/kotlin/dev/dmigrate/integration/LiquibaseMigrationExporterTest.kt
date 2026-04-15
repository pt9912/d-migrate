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
import io.kotest.matchers.string.shouldStartWith

class LiquibaseMigrationExporterTest : FunSpec({

    val exporter = LiquibaseMigrationExporter()

    fun identity(
        version: String = "1.0",
        slug: String = "my_app",
        dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL,
    ) = MigrationIdentity(
        tool = MigrationTool.LIQUIBASE,
        dialect = dialect,
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

    test("tool is LIQUIBASE") {
        exporter.tool shouldBe MigrationTool.LIQUIBASE
    }

    // ── Single artifact ───────────────────────────

    test("produces single changelog artifact") {
        val result = exporter.render(bundle())
        result.artifacts shouldHaveSize 1
    }

    test("file name follows convention") {
        val result = exporter.render(bundle())
        result.artifacts[0].relativePath.normalized shouldBe "changelog-1.0-my_app.xml"
    }

    test("artifact kind is changelog") {
        val result = exporter.render(bundle())
        result.artifacts[0].kind shouldBe "changelog"
    }

    // ── XML structure ─────────────────────────────

    test("XML starts with declaration") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldStartWith """<?xml version="1.0" encoding="UTF-8"?>"""
    }

    test("XML contains databaseChangeLog root") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "<databaseChangeLog"
        result.artifacts[0].content shouldContain "</databaseChangeLog>"
    }

    test("XML contains Liquibase namespace") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "http://www.liquibase.org/xml/ns/dbchangelog"
    }

    // ── ChangeSet identity ────────────────────────

    test("changeSet id is deterministic from identity") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain """id="1.0-my_app-postgresql""""
    }

    test("changeSet author is d-migrate") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain """author="d-migrate""""
    }

    test("changeSet id varies with dialect") {
        val mysqlBundle = bundle(identity = identity(dialect = DatabaseDialect.MYSQL))
        val result = exporter.render(mysqlBundle)
        result.artifacts[0].content shouldContain """id="1.0-my_app-mysql""""
    }

    // ── SQL content ───────────────────────────────

    test("SQL embedded in changeSet") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldContain "CREATE TABLE users"
    }

    test("XML-unsafe characters escaped") {
        val sql = "SELECT * FROM t WHERE a < 10 & b > 5"
        val result = exporter.render(bundle(up = payload(sql)))
        result.artifacts[0].content shouldContain "&lt;"
        result.artifacts[0].content shouldContain "&amp;"
        result.artifacts[0].content shouldContain "&gt;"
    }

    // ── Rollback ──────────────────────────────────

    test("no rollback block when not requested") {
        val result = exporter.render(bundle())
        result.artifacts[0].content shouldNotContain "<rollback>"
    }

    test("rollback block present when requested") {
        val down = payload("DROP TABLE users;")
        val result = exporter.render(bundle(rollback = MigrationRollback.Requested(down)))
        result.artifacts[0].content shouldContain "<rollback>"
        result.artifacts[0].content shouldContain "DROP TABLE users;"
    }

    // ── Determinism ───────────────────────────────

    test("identical inputs produce identical output") {
        val a = exporter.render(bundle())
        val b = exporter.render(bundle())
        a shouldBe b
    }

    // ── ChangeSet ID derivation ───────────────────

    test("deriveChangeSetId combines version, slug, and dialect") {
        LiquibaseMigrationExporter.deriveChangeSetId(identity()) shouldBe
            "1.0-my_app-postgresql"
    }
})
