dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":hexagon:profiling"))
    implementation("com.mysql:mysql-connector-j:${rootProject.properties["mysqlJdbcVersion"]}")

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-mysql:${rootProject.properties["testcontainersVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                if (!project.hasProperty("integrationTests")) {
                    // Profiling adapters require Testcontainers (MySQL).
                    // Excluded from non-integration coverage to keep the
                    // unit-only minBound realistic. With -PintegrationTests
                    // the exclusion is lifted and profiling code counts toward
                    // the 90% target (0.9.1 Phase A §5.4 / §6).
                    classes("dev.dmigrate.driver.mysql.profiling.*")
                }
            }
        }
        verify {
            rule {
                minBound(if (project.hasProperty("integrationTests")) 90 else 45)
            }
        }
    }
}
