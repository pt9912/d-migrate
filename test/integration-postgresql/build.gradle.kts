// Integration tests for the PostgreSQL driver module.
// These tests require a live PostgreSQL database via Testcontainers and
// are excluded from the default unit-test run. Activate with -PintegrationTests.

dependencies {
    testImplementation(project(":adapters:driven:driver-postgresql"))
    testImplementation(project(":adapters:driven:driver-postgresql-profiling"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":hexagon:profiling"))
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:ports"))
    // F.2 round-trip smoke: SchemaMigrateRunner / SchemaRollbackRunner +
    // request DTOs / ResolvedSchemaOperand live in the application layer.
    testImplementation(project(":hexagon:application"))

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.postgresql:postgresql:${rootProject.properties["postgresqlJdbcVersion"]}")
}
