package dev.dmigrate.driver.profiling

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProfilingSqlNamesTest : FunSpec({

    test("tablePath quotes schema and table independently for PostgreSQL") {
        val names = ProfilingSqlNames(DatabaseDialect.POSTGRESQL)

        names.tablePath("""select"; DROP""", """tenant.one""") shouldBe
            "\"tenant.one\".\"select\"\"; DROP\""
    }

    test("tablePath quotes schema and table independently for MySQL") {
        val names = ProfilingSqlNames(DatabaseDialect.MYSQL)

        names.tablePath("select`; DROP", "tenant.one") shouldBe
            "`tenant.one`.`select``; DROP`"
    }

    test("tablePath preserves empty identifiers as quoted tokens") {
        val names = ProfilingSqlNames(DatabaseDialect.SQLITE)

        names.tablePath("", "") shouldBe "\"\""
        names.tablePath("", "main") shouldBe "\"main\".\"\""
    }
})
