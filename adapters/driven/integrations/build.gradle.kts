plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports"))

    // Pure unit tests for migration-tool exporter rendering — no live tools
    // required. Runtime-validation tests (Flyway/Liquibase/Django/Knex
    // against a real PostgreSQL container) live in :test:integration-integrations.
    testImplementation(project(":hexagon:core"))
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
