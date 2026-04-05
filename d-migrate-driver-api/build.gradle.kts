plugins {
    `java-library`
}

dependencies {
    api(project(":d-migrate-core"))
}

// Coverage for this module is achieved through concrete driver tests.
// AbstractDdlGenerator and interfaces are tested via PostgresDdlGenerator, MysqlDdlGenerator, SqliteDdlGenerator.
