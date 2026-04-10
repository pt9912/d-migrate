plugins {
    `java-library`
}

// adapters:driven:driver-common — Shared adapter infrastructure for DB drivers.
// Abstract base classes, connection pooling, registries.

dependencies {
    api(project(":hexagon:ports"))
    api("com.zaxxer:HikariCP:${rootProject.properties["hikariVersion"]}")
    api("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")

    testImplementation("org.xerial:sqlite-jdbc:${rootProject.properties["sqliteJdbcVersion"]}")
}

kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
