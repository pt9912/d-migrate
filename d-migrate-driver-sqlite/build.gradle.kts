dependencies {
    implementation(project(":d-migrate-driver-api"))
    // 0.3.0 Phase B: SQLite JDBC für DataReader / TableLister
    implementation("org.xerial:sqlite-jdbc:${rootProject.properties["sqliteJdbcVersion"]}")
}

kover {
    reports {
        verify {
            rule {
                // TypeMapper: 100% via own tests; DdlGenerator: tested via golden masters in d-migrate-formats
                minBound(90)
            }
        }
    }
}
