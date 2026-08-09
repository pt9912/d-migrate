// Runtime-validation integration tests for the migration-tool exporters
// (Flyway, Liquibase, Django, Knex). These tests generate export
// artifacts via the production ToolExportRunner path and then run the
// real tool against a live PostgreSQL container via Testcontainers.
//
// Excluded from the default unit-test run via the Kotest `integration`
// tag (siehe Root build.gradle.kts). Activate with -PintegrationTests.

dependencies {
    testImplementation(project(":adapters:driven:integrations"))
    testImplementation(project(":adapters:driving:cli"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:driver-postgresql"))
    testImplementation(project(":adapters:driven:driver-sqlite"))
    testImplementation(project(":adapters:driven:formats"))
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:application"))
    testImplementation(project(":hexagon:ports"))

    testImplementation("org.flywaydb:flyway-core:13.1.0")
    testImplementation("org.flywaydb:flyway-database-postgresql:13.1.0")
    testImplementation("org.liquibase:liquibase-core:4.31.1")
    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")
}
