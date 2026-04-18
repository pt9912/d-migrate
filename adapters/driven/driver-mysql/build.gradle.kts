dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":hexagon:profiling"))
    implementation("com.mysql:mysql-connector-j:${rootProject.properties["mysqlJdbcVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                // Thin wrappers with no testable logic (< 60 LOC combined):
                classes(
                    "dev.dmigrate.driver.mysql.MysqlDataReader",
                    "dev.dmigrate.driver.mysql.MysqlDriver",
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
