plugins {
    `java-library`
}

dependencies {
    api(project(":d-migrate-core"))
    // HikariCP wird über das public ConnectionPool-Interface verwendet —
    // konkrete Treiber sehen es transitiv via api(...).
    api("com.zaxxer:HikariCP:${rootProject.properties["hikariVersion"]}")
    api("org.slf4j:slf4j-api:${rootProject.properties["slf4jVersion"]}")
}

// Coverage for this module is achieved through concrete driver tests.
// - AbstractDdlGenerator and DDL interfaces (0.2.0) are tested via
//   PostgresDdlGenerator, MysqlDdlGenerator, SqliteDdlGenerator.
// - HikariConnectionPool / HikariConnectionPoolFactory (Phase A) need a
//   real JDBC driver and are tested via the SQLite driver module in Phase B.
// - ConnectionUrlParser and LogScrubber are covered directly by tests
//   in this module (ConnectionUrlParserTest, LogScrubberTest).
//
// No koverVerify threshold here on purpose — it would falsely fail on the
// untestable Hikari adapter classes until Phase B brings real driver tests.
