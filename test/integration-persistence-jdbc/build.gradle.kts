// Contract + integration tests for the JDBC-backed server-state adapters
// (IdempotencyStore, JobStore, JobStartTransaction, QuotaStore,
// QuotaReservationOwnerStore, OwnerAwareQuotaService) plus the
// JdbcMigrationRunner Flyway-Wrapper. These tests require a live
// PostgreSQL container via Testcontainers and exercise Postgres-only
// JDBC features (JSONB, SELECT FOR UPDATE, ON CONFLICT … RETURNING,
// partial indices).
//
// Excluded from the default unit-test run via the Kotest `integration`
// tag (siehe Root build.gradle.kts). Activate with -PintegrationTests.

dependencies {
    testImplementation(project(":adapters:driven:persistence-jdbc"))
    testImplementation(project(":hexagon:application"))
    testImplementation(project(":hexagon:ports-common"))
    testImplementation(testFixtures(project(":hexagon:ports-common")))
    testImplementation(testFixtures(project(":hexagon:application")))

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")
}
