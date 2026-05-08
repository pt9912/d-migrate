// LF-012 / LN-011 / LN-017 / LN-027 — End-to-End-Integration-Tests gegen den Postgres-
// gestuetzten Server-State-Stack.
//
// Wirkt das gesamte Server-State-Wiring (Idempotency + JobStore +
// JobStartTransaction + Quota + OwnerStore) gegen einen Testcontainers-
// PG-Container und exerziert die §7.x-Akzeptanz-Pfade:
//
// - Job-Start → JobStartTransaction.commit (atomar Idempotency+Job)
// - JobDispatcher.dispatch → Worker → Terminal → quota-Release
// - JobCancelService → markCancelRequested + quota-Release
// - QuotaReservationSweeper → exactly-once-Refund

dependencies {
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:ports"))
    testImplementation(project(":hexagon:ports-common"))
    testImplementation(project(":hexagon:application"))
    testImplementation(project(":adapters:driven:persistence-jdbc"))

    // Test-Fixtures aus den Bestands-Suiten (Fixtures.NOW, jobRecord, …).
    testImplementation(testFixtures(project(":hexagon:ports-common")))
    testImplementation(testFixtures(project(":hexagon:application")))

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.postgresql:postgresql:${rootProject.properties["postgresqlJdbcVersion"]}")
    testImplementation("com.zaxxer:HikariCP:${rootProject.properties["hikariVersion"]}")
    // Flyway-Core fuer den MigrateResult-Typ im JdbcMigrationRunner-
    // Aufruf (auch wenn der Test das Ergebnis ignoriert).
    testImplementation("org.flywaydb:flyway-core:11.8.2")
}

kover {
    reports {
        verify {
            // Reines Test-Modul — keine produktiven Klassen, keine
            // Coverage-Pflicht.
            rule { minBound(0) }
        }
    }
}
