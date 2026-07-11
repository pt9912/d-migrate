dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation("org.xerial:sqlite-jdbc:${rootProject.properties["sqliteJdbcVersion"]}")

    // LN-046 / ADR 0029: geteilter Arb<NeutralType> aus core-Test-Fixtures.
    testImplementation(testFixtures(project(":hexagon:core")))
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // SchemaReader orchestration is tested in SqliteSchemaReaderTest;
                    // type mapping logic is tested in SqliteTypeMappingTest.
                    // Remaining uncovered branches are edge cases requiring exotic
                    // real-world schemas.
                    "dev.dmigrate.driver.sqlite.SqliteSchemaReader",
                    // Atomic-Preserve Phase B.4 (2026-05-31): the executor's
                    // I/O-bound branches (BEGIN IMMEDIATE, PRAGMA busy_timeout,
                    // probe/restore on the locked connection) are covered by
                    // SqliteAtomicSequencePreserveExecutorIntegrationTest in
                    // `:test:integration-sqlite` — root-Kover-aggregated per E.3.
                    // Pure surfaces (empty-batch short-circuit, `require`
                    // precondition) are covered by SqliteSequenceCurrentValueProbe
                    // tests transitively where they intersect.
                    "dev.dmigrate.driver.sqlite.SqliteAtomicSequencePreserveExecutor*",
                )
            }
        }
        verify {
            rule {
                // TypeMapper: 100% via own tests; DdlGenerator: tested via golden masters in d-migrate-formats
                minBound(90)
            }
        }
    }
}
