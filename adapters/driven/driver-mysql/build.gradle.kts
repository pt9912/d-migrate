dependencies {
    implementation(project(":adapters:driven:driver-common"))
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
                    // Atomic-Preserve Phase B.3 (2026-05-31): the executor's
                    // I/O-bound branches (lock acquisition, FOR UPDATE,
                    // session-timeout reset) are exhaustively covered by
                    // MysqlAtomicSequencePreserveExecutorIntegrationTest in
                    // `:test:integration-mysql` (which is part of the root
                    // Kover aggregate per E.3 — so coverage IS reported at
                    // the aggregate level, just not at this module's
                    // per-module gate where unit tests cannot reach the
                    // JDBC paths). Pure helpers (classifyLockSqlException,
                    // ceilDivToSeconds, empty-batch + require-precondition
                    // short-circuits) keep their own unit tests in
                    // MysqlAtomicSequencePreserveExecutorTest.
                    "dev.dmigrate.driver.mysql.MysqlAtomicSequencePreserveExecutor*",
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
