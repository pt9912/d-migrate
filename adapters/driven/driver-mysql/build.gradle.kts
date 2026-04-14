dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation("com.mysql:mysql-connector-j:${rootProject.properties["mysqlJdbcVersion"]}")

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-mysql:${rootProject.properties["testcontainersVersion"]}")
}

kover {
    reports {
        verify {
            rule {
                // Non-integration: covers TypeMapper, TypeMapping, DdlGenerator,
                // UrlBuilder, Identifiers (unit-testable). SchemaReader,
                // DataReader, MetadataQueries, DataWriter, SchemaSync require
                // Testcontainers.
                // TODO: extract more orchestration logic from SchemaReader
                // into testable pure functions to raise non-integration coverage.
                minBound(if (project.hasProperty("integrationTests")) 90 else 45)
            }
        }
    }
}
