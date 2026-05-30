// test:integration-concurrency — Concurrent-Writer race reproducers
// for the SequencePreserveStage probe→restore window.
//
// Plan-Doc: docs/planning/in-progress/quality-coverage-expansion-plan.md
// §5.3 (Sub-Slice C).
//
// Two gates:
//   1. `-PintegrationTests` — inherited from the :test:integration-*
//      naming convention via root build.gradle.kts:85-87. Without
//      it, Gradle skips the test task entirely.
//   2. `-PconcurrencyTests` — module-specific second gate so the
//      normal integration sweep does not pick up the race tests
//      (they take longer than the normal integration smoke and
//      pin a documented LEGACY race, not a correctness contract).
//
// Run:
//   make integration INTEGRATION_TASKS="-PintegrationTests -PconcurrencyTests :test:integration-concurrency:test"

dependencies {
    testImplementation(project(":hexagon:core"))
    testImplementation(project(":adapters:driven:driver-common"))
    testImplementation(project(":adapters:driven:driver-postgresql"))
    testImplementation(project(":adapters:driven:driver-mysql"))
    testImplementation(project(":adapters:driven:driver-sqlite"))

    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-mysql:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.postgresql:postgresql:${rootProject.properties["postgresqlJdbcVersion"]}")
    testImplementation("com.mysql:mysql-connector-j:${rootProject.properties["mysqlJdbcVersion"]}")
    testImplementation("org.xerial:sqlite-jdbc:${rootProject.properties["sqliteJdbcVersion"]}")
}

tasks.named<Test>("test") {
    onlyIf("requires -PconcurrencyTests") {
        project.hasProperty("concurrencyTests")
    }
}

kover {
    reports {
        verify {
            // Reines Race-Reproducer-Modul ohne Production-Code —
            // Plan-Doc §5.0.
            rule {
                minBound(0)
            }
        }
    }
}
