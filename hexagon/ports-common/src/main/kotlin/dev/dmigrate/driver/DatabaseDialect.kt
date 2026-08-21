package dev.dmigrate.driver

enum class DatabaseDialect {
    POSTGRESQL, MYSQL, SQLITE, MSSQL;

    companion object {
        fun fromString(value: String): DatabaseDialect = when (value.lowercase()) {
            "postgresql", "postgres", "pg" -> POSTGRESQL
            "mysql", "maria", "mariadb" -> MYSQL
            "sqlite", "sqlite3" -> SQLITE
            "mssql", "sqlserver" -> MSSQL
            else -> throw IllegalArgumentException(
                "Unknown database dialect: '$value'. Supported: postgresql, mysql, sqlite, mssql"
            )
        }
    }
}
