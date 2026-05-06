plugins {
    `java-library`
}

// adapters:driven:persistence-jdbc — JDBC/Postgres-backed adapters for
// Phase-E Server-State-Ports (IdempotencyStore, JobStore, JobStartTransaction,
// QuotaService, QuotaReservationOwnerStore). See docs/planning/in-progress/
// ImpPlan-0.9.6-E2.md.

dependencies {
    api(project(":hexagon:ports-common"))
    api("com.zaxxer:HikariCP:${rootProject.properties["hikariVersion"]}")
    implementation("org.postgresql:postgresql:${rootProject.properties["postgresqlJdbcVersion"]}")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    implementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")

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
