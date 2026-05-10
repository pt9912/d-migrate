// Integration tests for the SQLite driver module.
// SQLite runs in-process — no Testcontainers required — but the tests
// still live in `:test:integration-sqlite` rather than the driver
// module so the round-trip smoke (F.4) sits next to the PG and MySQL
// equivalents and runs under the same `-PintegrationTests` toggle.
// Activate with `-PintegrationTests`.

dependencies {
    testImplementation(project(":adapters:driven:driver-sqlite"))
    testImplementation(project(":adapters:driven:driver-sqlite-profiling"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":hexagon:application"))
    // F.4 round-trip smoke: shared `executeAgainstPool` helper (mirror of
    // the CLI-internal JdbcMigrationExecutor) lives in hexagon:application's
    // testFixtures so PG, MySQL and SQLite round-trip tests share one executor.
    testImplementation(testFixtures(project(":hexagon:application")))
    testImplementation(project(":hexagon:profiling"))
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:ports"))

    testImplementation("org.xerial:sqlite-jdbc:${rootProject.properties["sqliteJdbcVersion"]}")
}
