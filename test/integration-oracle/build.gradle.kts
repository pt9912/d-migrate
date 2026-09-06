// Integration tests for the Oracle driver module.
// These tests require a live Oracle database via Testcontainers and
// are excluded from the default unit-test run. Activate with -PintegrationTests.
//
// gvenzl/oracle-free braucht -- anders als das MSSQL-Image -- keine
// programmatische EULA-Akzeptanz (siehe docs/user/quality.md).

dependencies {
    testImplementation(project(":adapters:driven:driver-oracle"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:ports"))
    testImplementation(project(":hexagon:ports-write"))
    testImplementation(project(":hexagon:application"))
    testImplementation(testFixtures(project(":hexagon:application")))

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-oracle-free:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("com.oracle.database.jdbc:ojdbc11:${rootProject.properties["oracleJdbcVersion"]}")
}
