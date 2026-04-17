dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":hexagon:profiling"))
    implementation("com.mysql:mysql-connector-j:${rootProject.properties["mysqlJdbcVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                // JDBC-dependent classes — require a live MySQL database.
                // Tested via test:integration-mysql (Testcontainers).
                // Root-level :koverVerify covers them at 90%+ with -PintegrationTests.
                classes(
                    "dev.dmigrate.driver.mysql.MysqlDataReader",
                    "dev.dmigrate.driver.mysql.MysqlDataWriter",
                    "dev.dmigrate.driver.mysql.MysqlDataWriter\$Companion",
                    "dev.dmigrate.driver.mysql.MysqlSchemaReader",
                    "dev.dmigrate.driver.mysql.MysqlSchemaSync",
                    "dev.dmigrate.driver.mysql.MysqlTableLister",
                    "dev.dmigrate.driver.mysql.MysqlTableImportSession",
                    "dev.dmigrate.driver.mysql.MysqlTableImportSession\$State",
                    "dev.dmigrate.driver.mysql.MysqlDriver",
                    "dev.dmigrate.driver.mysql.MysqlMetadataQueries",
                )
            }
        }
        verify {
            rule {
                // 84% after JdbcOperations refactor — remaining gap is in
                // DdlGenerator spatial branches and TypeMapping edge cases.
                minBound(83)
            }
        }
    }
}
