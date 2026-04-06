dependencies {
    implementation(project(":d-migrate-driver-api"))
    // 0.3.0 Phase B: PostgreSQL JDBC für DataReader / TableLister
    implementation("org.postgresql:postgresql:${rootProject.properties["postgresqlJdbcVersion"]}")

    // Testcontainers für Integration-Tests (markiert mit @Tag("integration"),
    // siehe Plan §6.16 — laufen nur in integration.yml mit -PintegrationTests).
    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:postgresql:${rootProject.properties["testcontainersVersion"]}")
}

kover {
    reports {
        verify {
            rule {
                // TypeMapper: 100% via own tests; DdlGenerator: tested via golden masters in d-migrate-formats
                // PostgresDataReader/TableLister: tested via Testcontainers in @Tag("integration")
                // — Coverage wird bei -PintegrationTests gemessen.
                minBound(if (project.hasProperty("integrationTests")) 90 else 80)
            }
        }
        if (!project.hasProperty("integrationTests")) {
            filters {
                excludes {
                    classes(
                        "dev.dmigrate.driver.postgresql.PostgresDataReader",
                        "dev.dmigrate.driver.postgresql.PostgresTableLister",
                    )
                }
            }
        }
    }
}
