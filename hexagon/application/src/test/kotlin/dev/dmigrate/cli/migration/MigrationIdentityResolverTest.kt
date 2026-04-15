package dev.dmigrate.cli.migration

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.MigrationVersionSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MigrationIdentityResolverTest : FunSpec({

    // ── CLI version takes precedence ───────────

    test("CLI version used when provided") {
        val id = MigrationIdentityResolver.resolve(
            MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, "2.0", "1.0", "Shop")
        id.version shouldBe "2.0"
        id.versionSource shouldBe MigrationVersionSource.CLI
    }

    // ── Flyway fallback ────────────────────────

    test("Flyway falls back to schema.version") {
        val id = MigrationIdentityResolver.resolve(
            MigrationTool.FLYWAY, DatabaseDialect.MYSQL, null, "1.0.0", "Shop")
        id.version shouldBe "1.0.0"
        id.versionSource shouldBe MigrationVersionSource.SCHEMA
    }

    test("Flyway strips v prefix from schema.version") {
        val id = MigrationIdentityResolver.resolve(
            MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, null, "v3.1", "App")
        id.version shouldBe "3.1"
        id.versionSource shouldBe MigrationVersionSource.SCHEMA
    }

    test("Flyway rejects non-numeric schema.version") {
        shouldThrow<MigrationIdentityResolver.ResolutionException> {
            MigrationIdentityResolver.resolve(
                MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, null, "beta", "App")
        }.message shouldContain "not suitable"
    }

    // ── Liquibase fallback ─────────────────────

    test("Liquibase falls back to any schema.version") {
        val id = MigrationIdentityResolver.resolve(
            MigrationTool.LIQUIBASE, DatabaseDialect.POSTGRESQL, null, "1.0-rc1", "Shop")
        id.version shouldBe "1.0-rc1"
        id.versionSource shouldBe MigrationVersionSource.SCHEMA
    }

    // ── Django requires explicit version ───────

    test("Django fails without --version") {
        shouldThrow<MigrationIdentityResolver.ResolutionException> {
            MigrationIdentityResolver.resolve(
                MigrationTool.DJANGO, DatabaseDialect.MYSQL, null, "1.0", "Shop")
        }.message shouldContain "--version is required"
    }

    test("Django succeeds with explicit version") {
        val id = MigrationIdentityResolver.resolve(
            MigrationTool.DJANGO, DatabaseDialect.SQLITE, "0001", "1.0", "Shop")
        id.version shouldBe "0001"
        id.versionSource shouldBe MigrationVersionSource.CLI
    }

    // ── Knex requires explicit version ─────────

    test("Knex fails without --version") {
        shouldThrow<MigrationIdentityResolver.ResolutionException> {
            MigrationIdentityResolver.resolve(
                MigrationTool.KNEX, DatabaseDialect.POSTGRESQL, null, "1.0", "Shop")
        }.message shouldContain "--version is required"
    }

    // ── Invalid version format ─────────────────

    test("Flyway rejects invalid CLI version") {
        shouldThrow<MigrationIdentityResolver.ResolutionException> {
            MigrationIdentityResolver.resolve(
                MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, "abc", null, "App")
        }.message shouldContain "numeric"
    }

    test("Django rejects invalid version format") {
        shouldThrow<MigrationIdentityResolver.ResolutionException> {
            MigrationIdentityResolver.resolve(
                MigrationTool.DJANGO, DatabaseDialect.MYSQL, "init", null, "App")
        }.message shouldContain "4+ digits"
    }

    // ── Slug generation ────────────────────────

    test("slug derived from schema name") {
        val id = MigrationIdentityResolver.resolve(
            MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, "1", null, "My E-Commerce App")
        id.slug shouldBe "my_e_commerce_app"
    }

    // ── Determinism ────────────────────────────

    test("same inputs produce same identity") {
        val a = MigrationIdentityResolver.resolve(
            MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, "1.0", null, "Shop")
        val b = MigrationIdentityResolver.resolve(
            MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, "1.0", null, "Shop")
        a shouldBe b
    }

    // ── No version at all ──────────────────────

    test("no CLI version and no schema version fails") {
        shouldThrow<MigrationIdentityResolver.ResolutionException> {
            MigrationIdentityResolver.resolve(
                MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, null, null, "App")
        }.message shouldContain "not suitable"
    }
})
