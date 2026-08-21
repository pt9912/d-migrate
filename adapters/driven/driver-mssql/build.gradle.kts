// MSSQL-Treibermodul (vierter Dialekt, ADR 0047); Slice-Plan:
// docs/planning/in-progress/mssql-dialect-scoping.md.

dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation("com.microsoft.sqlserver:mssql-jdbc:${rootProject.properties["mssqlJdbcVersion"]}")
}

kover {
    reports {
        filters {
            excludes {
                // Thin wrapper with no testable logic (mirror of MysqlDriver):
                classes("dev.dmigrate.driver.mssql.MssqlDriver")
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
