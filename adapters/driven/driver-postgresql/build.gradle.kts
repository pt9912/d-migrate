dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":hexagon:profiling"))
    implementation("org.postgresql:postgresql:${rootProject.properties["postgresqlJdbcVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                // JDBC-dependent classes — require a live PostgreSQL database.
                // Tested via test:integration-postgresql (Testcontainers).
                // Root-level :koverVerify covers them at 90%+ with -PintegrationTests.
                classes(
                    "dev.dmigrate.driver.postgresql.Postgres*Reader",
                    "dev.dmigrate.driver.postgresql.Postgres*Writer",
                    "dev.dmigrate.driver.postgresql.PostgresSchemaSync",
                    "dev.dmigrate.driver.postgresql.PostgresTableLister",
                    "dev.dmigrate.driver.postgresql.PostgresTableImportSession*",
                    "dev.dmigrate.driver.postgresql.PostgresDriver",
                    "dev.dmigrate.driver.postgresql.PostgresMetadataQueries",
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
