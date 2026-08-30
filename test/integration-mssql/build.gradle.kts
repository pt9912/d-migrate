// Integration tests for the MSSQL driver module.
// These tests require a live SQL Server database via Testcontainers and
// are excluded from the default unit-test run. Activate with -PintegrationTests.
//
// Das MSSQL-Container-Image (mcr.microsoft.com/mssql/server) verlangt die
// EULA-Akzeptanz; die Tests setzen sie via MSSQLServerContainer.acceptLicense()
// (siehe docs/user/quality.md, Abschnitt Integrations-Tests).

dependencies {
    testImplementation(project(":adapters:driven:driver-mssql"))
    testImplementation(project(":adapters:driven:driver-mssql-profiling"))
    testImplementation(project(":hexagon:profiling"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:ports"))
    testImplementation(project(":hexagon:ports-write"))
    // Round-Trip-Smoke: SchemaMigrateRunner / SchemaRollbackRunner samt
    // Request-DTOs und ResolvedSchemaOperand liegen in der Anwendungsschicht.
    testImplementation(project(":hexagon:application"))
    // `executeAgainstPool` (Gegenstueck zum CLI-internen JdbcMigrationExecutor)
    // teilen sich die Integrationsmodule ueber die testFixtures.
    testImplementation(testFixtures(project(":hexagon:application")))

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-mssqlserver:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("com.microsoft.sqlserver:mssql-jdbc:${rootProject.properties["mssqlJdbcVersion"]}")
}
