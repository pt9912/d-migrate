dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":hexagon:profiling"))
    implementation("org.postgresql:postgresql:${rootProject.properties["postgresqlJdbcVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                // Paket 1 done: MetadataQueries, SchemaReader, TableLister
                // now have MockK unit tests → exclusions removed.
                // Remaining: thin wrappers and Paket 2 helpers.
                classes(
                    "dev.dmigrate.driver.postgresql.PostgresDataReader",
                    "dev.dmigrate.driver.postgresql.PostgresDataWriter*",
                    "dev.dmigrate.driver.postgresql.PostgresSchemaSync",
                    "dev.dmigrate.driver.postgresql.PostgresTableImportSession*",
                    "dev.dmigrate.driver.postgresql.PostgresDriver",
                    // Only used by Paket 2 classes (DataWriter, SchemaSync);
                    // will be un-excluded in Paket 2.
                    "dev.dmigrate.driver.postgresql.QualifiedTableName",
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
