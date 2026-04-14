dependencies {
    implementation(project(":adapters:driven:driver-common"))
    // 0.3.0 Phase B: SQLite JDBC für DataReader / TableLister
    implementation("org.xerial:sqlite-jdbc:${rootProject.properties["sqliteJdbcVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // SchemaReader has many type-mapping branches that require diverse
                    // real-world schemas; core paths are tested in SqliteSchemaReaderTest
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
