package dev.dmigrate.driver

enum class DatabaseDialect {
    POSTGRESQL, MYSQL, SQLITE;

    companion object {
        fun fromString(value: String): DatabaseDialect = when (value.lowercase()) {
            "postgresql", "postgres", "pg" -> POSTGRESQL
            "mysql", "maria", "mariadb" -> MYSQL
            "sqlite", "sqlite3" -> SQLITE
            else -> throw IllegalArgumentException(
                "Unknown database dialect: '$value'. Supported: postgresql, mysql, sqlite"
            )
        }
    }
}
