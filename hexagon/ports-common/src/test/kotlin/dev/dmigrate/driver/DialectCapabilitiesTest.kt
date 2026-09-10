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

    test("einen Wegwerf-Sandkasten kann nur, wessen Schema kein Benutzer ist") {
        // Gemessen: PostgreSQL legt mit gewoehnlichen Rechten ein Schema an und
        // raeumt es per CASCADE wieder weg; die Katalogform darin ist
        // zeichengleich die des Ziels. Bei Oracle IST ein Schema ein Benutzer,
        // und `CREATE USER` scheitert am Migrationsnutzer mit ORA-01031.
        DialectCapabilities.forDialect(DatabaseDialect.POSTGRESQL).supportsRawTextSandbox shouldBe true
        DialectCapabilities.forDialect(DatabaseDialect.ORACLE).supportsRawTextSandbox shouldBe false
    }
})
