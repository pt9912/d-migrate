package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DialectCapabilitiesTest : FunSpec({

    test("partitionChildrenAreTables gates the LN-008 fan-out to PostgreSQL only") {
        // PG addresses a partition child as `SELECT … FROM child`; MySQL children are
        // sub-objects (`FROM parent PARTITION (p)`), so per-child fan-out must not apply.
        DialectCapabilities.forDialect(DatabaseDialect.POSTGRESQL).partitionChildrenAreTables shouldBe true
        DialectCapabilities.forDialect(DatabaseDialect.MYSQL).partitionChildrenAreTables shouldBe false
        DialectCapabilities.forDialect(DatabaseDialect.SQLITE).partitionChildrenAreTables shouldBe false
    }
})
