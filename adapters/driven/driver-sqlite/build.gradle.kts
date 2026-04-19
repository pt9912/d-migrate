dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation("org.xerial:sqlite-jdbc:${rootProject.properties["sqliteJdbcVersion"]}")
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
