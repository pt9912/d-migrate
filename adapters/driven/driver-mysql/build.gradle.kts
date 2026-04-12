dependencies {
    implementation(project(":adapters:driven:driver-common"))
    // 0.3.0 Phase B: MySQL JDBC für DataReader / TableLister
    implementation("com.mysql:mysql-connector-j:${rootProject.properties["mysqlJdbcVersion"]}")

    // Testcontainers für Integration-Tests (markiert mit @Tag("integration"),
    // siehe Plan §6.16 — laufen nur in integration.yml mit -PintegrationTests).
    // 2.0.0 hat alle Module umbenannt: `org.testcontainers:mysql` →
    // `org.testcontainers:testcontainers-mysql`. Klassen sind ebenfalls
    // verschoben: `containers.MySQLContainer` → `mysql.MySQLContainer`.
    testImplementation("org.testcontainers:testcontainers:${rootProject.properties["testcontainersVersion"]}")
    testImplementation("org.testcontainers:testcontainers-mysql:${rootProject.properties["testcontainersVersion"]}")
}

kover {
    reports {
        total {
            verify {
                rule {
                    // TypeMapper: 100% via own tests; DdlGenerator: tested via golden masters in d-migrate-formats
                    // MysqlDataReader/TableLister/DataWriter/SchemaSync: tested via Testcontainers in @Tag("integration")
                    // — Coverage wird bei -PintegrationTests gemessen.
                    minBound(if (project.hasProperty("integrationTests")) 90 else 80)
                }
            }
            if (!project.hasProperty("integrationTests")) {
                filters {
                    excludes {
                        classes(
                            "dev.dmigrate.driver.mysql.MysqlDataReader*",
                            "dev.dmigrate.driver.mysql.MysqlTableLister*",
                            "dev.dmigrate.driver.mysql.MysqlDataWriter*",
                            "dev.dmigrate.driver.mysql.MysqlTableImportSession*",
                            "dev.dmigrate.driver.mysql.MysqlSchemaSync*",
                        )
                    }
                }
            }
        }
    }
}
