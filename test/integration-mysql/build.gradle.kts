// Integration tests for the MySQL driver module.
// These tests require a live MySQL database via Testcontainers and
// are excluded from the default unit-test run. Activate with -PintegrationTests.

dependencies {
    testImplementation(project(":adapters:driven:driver-mysql"))
    testImplementation(project(":adapters:driven:driver-mysql-profiling"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":hexagon:application"))
    // F.3 round-trip smoke: shared `executeAgainstPool` helper (mirror of
    // the CLI-internal JdbcMigrationExecutor) lives in hexagon:application's
    // testFixtures so PG and MySQL round-trip tests share one executor.
    testImplementation(testFixtures(project(":hexagon:application")))
    testImplementation(project(":hexagon:profiling"))
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:ports"))

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-mysql:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("com.mysql:mysql-connector-j:${rootProject.properties["mysqlJdbcVersion"]}")
}
