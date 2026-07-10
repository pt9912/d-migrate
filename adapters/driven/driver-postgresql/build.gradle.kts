dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation("org.postgresql:postgresql:${rootProject.properties["postgresqlJdbcVersion"]}")

    // LN-046 / ADR 0029: geteilter Arb<NeutralType> aus core-Test-Fixtures.
    testImplementation(testFixtures(project(":hexagon:core")))
}

kover {
    reports {
        filters {
            excludes {
                // Thin wrappers with no testable logic (< 60 LOC combined):
                classes(
                    "dev.dmigrate.driver.postgresql.PostgresDataReader",
                    "dev.dmigrate.driver.postgresql.PostgresDriver",
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
