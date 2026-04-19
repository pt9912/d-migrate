package dev.dmigrate.migration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MigrationTypesTest : FunSpec({

    test("Django requires explicit version") {
        MigrationTool.DJANGO.requiresExplicitVersion shouldBe true
    }

    test("Knex requires explicit version") {
        MigrationTool.KNEX.requiresExplicitVersion shouldBe true
    }

    test("Flyway does not require explicit version") {
        MigrationTool.FLYWAY.requiresExplicitVersion shouldBe false
    }

    test("Liquibase does not require explicit version") {
        MigrationTool.LIQUIBASE.requiresExplicitVersion shouldBe false
    }

    test("MigrationIdentity is a data class with all fields") {
        val id = MigrationIdentity(
            tool = MigrationTool.FLYWAY,
            dialect = dev.dmigrate.driver.DatabaseDialect.POSTGRESQL,
            version = "1.0",
            versionSource = MigrationVersionSource.CLI,
            slug = "my_app",
        )
        id.tool shouldBe MigrationTool.FLYWAY
        id.slug shouldBe "my_app"
    }

    test("MigrationRollback.NotRequested is a singleton") {
        val r: MigrationRollback = MigrationRollback.NotRequested
        (r is MigrationRollback.NotRequested) shouldBe true
    }
})
