dependencies {
    implementation(project(":adapters:driven:driver-common"))
    implementation(project(":hexagon:profiling"))
    implementation("com.microsoft.sqlserver:mssql-jdbc:${rootProject.properties["mssqlJdbcVersion"]}")
}

kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
