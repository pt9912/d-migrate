plugins {
    `java-library`
}

// adapters:driven:persistence-jdbc — JDBC/Postgres-backed adapters for
// Phase-E Server-State-Ports (IdempotencyStore, JobStore, JobStartTransaction,
// QuotaService, QuotaReservationOwnerStore). See docs/planning/in-progress/
// ImpPlan-0.9.6-E2.md.

dependencies {
    api(project(":hexagon:ports-common"))
    // Phase E2.7: QuotaReservationOwnerStore + OwnerAwareQuotaService liegen
    // in hexagon:application; der JDBC-Adapter implementiert beide.
    api(project(":hexagon:application"))
    api("com.zaxxer:HikariCP:${rootProject.properties["hikariVersion"]}")
    implementation("org.postgresql:postgresql:${rootProject.properties["postgresqlJdbcVersion"]}")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    implementation("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")

    // Phase E2.3: ApprovalChallenge JSON-Codec fuer JSONB-Persistenz
    // (idempotency_reservations.challenge). Jackson-databind +
    // kotlin-Modul reichen; YAML wird hier nicht gebraucht.
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.properties["jacksonVersion"]}")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${rootProject.properties["jacksonVersion"]}")
    // Phase E2.5: jsr310-Modul fuer Instant-Felder in
    // ManagedJob/JobError/JobProgress/JobCancelRequest (managed_job-JSONB).
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${rootProject.properties["jacksonVersion"]}")

    testImplementation("org.xerial:sqlite-jdbc:${rootProject.properties["sqliteJdbcVersion"]}")

    // Phase E2.2: Flyway-Migrate gegen Testcontainers-Postgres als
    // tagged @Tag("integration") — werden mit -PintegrationTests
    // (.github/workflows/integration.yml) ausgefuehrt.
    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")

    // Phase E2.3: Contract-Test-Fixtures (IdempotencyStoreContractTests +
    // Fixtures.tenant/principal/NOW). Selber JAR fuer alle Phase-E-
    // JDBC-Adapter-Contract-Tests (E2.4–E2.7).
    testImplementation(testFixtures(project(":hexagon:ports-common")))
    // Phase E2.7: QuotaReservationOwnerStoreContractTests aus
    // hexagon:application testFixtures.
    testImplementation(testFixtures(project(":hexagon:application")))
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
                    // Phase E2.3: Postgres-only JDBC-Logik (JSONB,
                    // SELECT FOR UPDATE, INSERT…ON CONFLICT…RETURNING) —
                    // gedeckt durch JdbcIdempotencyStoreContractTest unter
                    // -PintegrationTests, kein in-process-Postgres-Aequivalent.
                    "dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore",
                    "dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore\$EntryRow",
                    "dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore\$InitRow",
                    "dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore\$Companion",
                    // Phase E2.5: JdbcJobStore — Postgres-only (JSONB,
                    // SELECT FOR UPDATE, ON CONFLICT). Gedeckt durch
                    // JdbcJobStoreContractTest unter -PintegrationTests.
                    "dev.dmigrate.server.persistence.jdbc.job.JdbcJobStore",
                    // Phase E2.6: JdbcJobStartTransaction — Cross-Store-
                    // TX-Komposition. Gedeckt durch
                    // JdbcJobStartTransactionContractTest unter
                    // -PintegrationTests.
                    "dev.dmigrate.server.persistence.jdbc.job.JdbcJobStartTransaction",
                )
                // Phase E2.7: Quota-Stack (JdbcQuotaStore +
                // JdbcQuotaReservationOwnerStore + JdbcOwnerAwareQuotaService
                // plus QuotaJson-Wire-Codec). Postgres-only Logik
                // (INSERT…ON CONFLICT mit Limit-Check, jsonb, GREATEST,
                // RETURNING). Gedeckt durch JdbcQuotaStoreContractTest +
                // JdbcQuotaReservationOwnerStoreContractTest +
                // JdbcOwnerAwareQuotaServiceTest unter -PintegrationTests.
                // QuotaJson hat zwar einen Default-Unit-Test, wird aber
                // mit dem Stack zusammen exkludiert, weil Wire-Code-
                // Versionierung mit der Schema-Version koppelt.
                packages("dev.dmigrate.server.persistence.jdbc.quota")
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
