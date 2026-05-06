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

    // Phase E2.2: Flyway-Migrate gegen Testcontainers-Postgres als
    // tagged @Tag("integration") — werden mit -PintegrationTests
    // (.github/workflows/integration.yml) ausgefuehrt.
    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                // Phase E2.2: Thin Flyway-Wrapper — die produktive Logik
                // (Migration-Anwendung gegen ein echtes Postgres) wird durch
                // PhaseEMigrationRunnerIntegrationTest unter
                // -PintegrationTests gedeckt. Im Default-Test-Lauf gibt es
                // keine in-process-Postgres-Alternative, die JSONB +
                // partielle Indizes (siehe V1__phase_e_initial.sql) versteht.
                classes(
                    "dev.dmigrate.server.persistence.jdbc.migration.PhaseEMigrationRunner",
                    "dev.dmigrate.server.persistence.jdbc.migration.PhaseEMigrationRunner\$Companion",
                )
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
