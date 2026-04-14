package dev.dmigrate.cli.migration

import dev.dmigrate.migration.MigrationTool
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class MigrationVersionValidatorTest : FunSpec({

    // ── Flyway ─────────────────────────────────

    test("Flyway accepts simple number") {
        MigrationVersionValidator.validate(MigrationTool.FLYWAY, "1").valid shouldBe true
    }

    test("Flyway accepts semver-like") {
        MigrationVersionValidator.validate(MigrationTool.FLYWAY, "1.0.0").valid shouldBe true
    }

    test("Flyway rejects non-numeric") {
        val r = MigrationVersionValidator.validate(MigrationTool.FLYWAY, "abc")
        r.valid shouldBe false
        r.error shouldContain "numeric"
    }

    test("Flyway rejects blank") {
        MigrationVersionValidator.validate(MigrationTool.FLYWAY, "").valid shouldBe false
    }

    // ── Liquibase ──────────────────────────────

    test("Liquibase accepts any non-blank string") {
        MigrationVersionValidator.validate(MigrationTool.LIQUIBASE, "anything-goes").valid shouldBe true
    }

    // ── Django ──────────────────────────────────

    test("Django accepts zero-padded 4+ digits") {
        MigrationVersionValidator.validate(MigrationTool.DJANGO, "0001").valid shouldBe true
    }

    test("Django accepts longer numbers") {
        MigrationVersionValidator.validate(MigrationTool.DJANGO, "00042").valid shouldBe true
    }

    test("Django rejects 3-digit number") {
        MigrationVersionValidator.validate(MigrationTool.DJANGO, "001").valid shouldBe false
    }

    test("Django rejects non-numeric") {
        MigrationVersionValidator.validate(MigrationTool.DJANGO, "init").valid shouldBe false
    }

    // ── Knex ────────────────────────────────────

    test("Knex accepts numeric timestamp") {
        MigrationVersionValidator.validate(MigrationTool.KNEX, "20260414120000").valid shouldBe true
    }

    test("Knex rejects non-numeric") {
        MigrationVersionValidator.validate(MigrationTool.KNEX, "v1").valid shouldBe false
    }

    // ── Fallback normalization ──────────────────

    test("Flyway normalizes schema version with v prefix") {
        MigrationVersionValidator.normalizeFallback(MigrationTool.FLYWAY, "v1.0.0") shouldBe "1.0.0"
    }

    test("Flyway rejects non-numeric schema version") {
        MigrationVersionValidator.normalizeFallback(MigrationTool.FLYWAY, "beta").shouldBeNull()
    }

    test("Liquibase accepts any schema version") {
        MigrationVersionValidator.normalizeFallback(MigrationTool.LIQUIBASE, "1.0-rc1") shouldBe "1.0-rc1"
    }

    test("Django never falls back") {
        MigrationVersionValidator.normalizeFallback(MigrationTool.DJANGO, "1.0").shouldBeNull()
    }

    test("Knex never falls back") {
        MigrationVersionValidator.normalizeFallback(MigrationTool.KNEX, "1234").shouldBeNull()
    }

    test("blank schema version returns null") {
        MigrationVersionValidator.normalizeFallback(MigrationTool.FLYWAY, "").shouldBeNull()
    }
})

private infix fun String?.shouldContain(sub: String) {
    this!!.contains(sub) shouldBe true
}
