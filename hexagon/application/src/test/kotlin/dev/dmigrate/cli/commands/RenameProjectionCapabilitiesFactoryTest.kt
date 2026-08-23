package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.RenameCapabilitySource
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Plan-2 §F.4 dependency-projection T1: pins the application-side
 * application/core bridge so the factory's `DatabaseDialect →
 * RenameProjectionDialect` mapping and the conservative FILE_ONLY
 * default don't drift silently. T-? will wire live-probe capabilities
 * and replace the FILE_ONLY return for relevant request shapes; the
 * tests here will need updating then.
 */
class RenameProjectionCapabilitiesFactoryTest : FunSpec({

    test("DatabaseDialect maps 1:1 to RenameProjectionDialect") {
        RenameProjectionCapabilitiesFactory.dialectFor(DatabaseDialect.POSTGRESQL) shouldBe
            RenameProjectionDialect.POSTGRESQL
        RenameProjectionCapabilitiesFactory.dialectFor(DatabaseDialect.MYSQL) shouldBe
            RenameProjectionDialect.MYSQL
        RenameProjectionCapabilitiesFactory.dialectFor(DatabaseDialect.SQLITE) shouldBe
            RenameProjectionDialect.SQLITE
        RenameProjectionCapabilitiesFactory.dialectFor(DatabaseDialect.MSSQL) shouldBe
            RenameProjectionDialect.MSSQL
    }

    test("capabilitiesFor returns FILE_ONLY regardless of dialect or request shape (T1 default)") {
        val request = SchemaMigrateRequest(source = "src.yaml", target = "tgt.yaml")
        for (dialect in DatabaseDialect.entries) {
            val capabilities = RenameProjectionCapabilitiesFactory.capabilitiesFor(request, dialect)
            capabilities.source shouldBe RenameCapabilitySource.FILE_ONLY
            capabilities.sqliteVersion shouldBe null
            capabilities.sqliteLegacyAlterTable shouldBe null
            capabilities.mysqlServerFamily shouldBe null
            capabilities.mysqlVersion shouldBe null
        }
    }

})
