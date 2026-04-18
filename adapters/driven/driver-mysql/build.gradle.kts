dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":hexagon:profiling"))
    implementation("com.mysql:mysql-connector-j:${rootProject.properties["mysqlJdbcVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                // Paket 1 done: MetadataQueries, SchemaReader, TableLister
                // now have MockK unit tests → exclusions removed.
                // Remaining: thin wrappers and Paket 2 helpers.
                classes(
                    "dev.dmigrate.driver.mysql.MysqlDataReader",
                    "dev.dmigrate.driver.mysql.MysqlDataWriter*",
                    "dev.dmigrate.driver.mysql.MysqlSchemaSync",
                    "dev.dmigrate.driver.mysql.MysqlTableImportSession*",
                    "dev.dmigrate.driver.mysql.MysqlDriver",
                    // Only used by Paket 2 classes (DataWriter, SchemaSync);
                    // will be un-excluded in Paket 2.
                    "dev.dmigrate.driver.mysql.MysqlQualifiedTableName",
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
