dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":hexagon:profiling"))
    implementation("org.postgresql:postgresql:${rootProject.properties["postgresqlJdbcVersion"]}")

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                if (!project.hasProperty("integrationTests")) {
                    // Profiling adapters require Testcontainers (PostgreSQL).
                    // Excluded from non-integration coverage to keep the
                    // unit-only minBound realistic. With -PintegrationTests
                    // the exclusion is lifted and profiling code counts toward
                    // the 90% target (0.9.1 Phase A §5.4 / §6).
                    classes("dev.dmigrate.driver.postgresql.profiling.*")
                }
            }
        }
        verify {
            rule {
                minBound(if (project.hasProperty("integrationTests")) 90 else 40)
            }
        }
    }
}
