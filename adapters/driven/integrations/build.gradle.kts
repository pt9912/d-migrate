plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports"))

    // Phase E: runtime validation — test-only dependencies
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":hexagon:application"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:driver-postgresql"))
    testImplementation(project(":adapters:driven:driver-sqlite"))
    testImplementation(project(":adapters:driven:formats"))
    testImplementation("org.flywaydb:flyway-core:11.8.2")
    testImplementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    testImplementation("org.liquibase:liquibase-core:4.31.1")
    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")
}

kover {
    reports {
        verify {
            rule {
                minBound(if (project.hasProperty("integrationTests")) 90 else 90)
            }
        }
    }
}
