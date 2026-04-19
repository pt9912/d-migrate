package dev.dmigrate.driver

/**
 * Inverts CREATE statements to DROP statements for rollback DDL.
 * Extracted from AbstractDdlGenerator. Subclasses can override
 * [invert] for dialect-specific inversions.
 */
internal open class StatementInverter {

    open fun invert(stmt: DdlStatement): DdlStatement? {
        val sql = stmt.sql.trim()
        return when {
            sql.startsWith("CREATE TABLE", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE TABLE")
                DdlStatement("DROP TABLE IF EXISTS $name;")
            }
            sql.startsWith("CREATE INDEX", ignoreCase = true) || sql.startsWith("CREATE UNIQUE INDEX", ignoreCase = true) -> {
                val name = extractNameAfter(sql, if ("UNIQUE" in sql.uppercase()) "CREATE UNIQUE INDEX" else "CREATE INDEX")
                DdlStatement("DROP INDEX IF EXISTS $name;")
            }
            sql.startsWith("CREATE TYPE", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE TYPE")
                DdlStatement("DROP TYPE IF EXISTS $name;")
            }
            sql.startsWith("CREATE VIEW", ignoreCase = true) || sql.startsWith("CREATE OR REPLACE VIEW", ignoreCase = true) -> {
                val keyword = if ("OR REPLACE" in sql.uppercase()) "CREATE OR REPLACE VIEW" else "CREATE VIEW"
                val name = extractNameAfter(sql, keyword)
                DdlStatement("DROP VIEW IF EXISTS $name;")
            }
            sql.startsWith("CREATE MATERIALIZED VIEW", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE MATERIALIZED VIEW")
                DdlStatement("DROP MATERIALIZED VIEW IF EXISTS $name;")
            }
            sql.startsWith("CREATE FUNCTION", ignoreCase = true) || sql.startsWith("CREATE OR REPLACE FUNCTION", ignoreCase = true) -> {
                val keyword = if ("OR REPLACE" in sql.uppercase()) "CREATE OR REPLACE FUNCTION" else "CREATE FUNCTION"
                val name = extractNameAfter(sql, keyword)
                DdlStatement("DROP FUNCTION IF EXISTS $name;")
            }
            sql.startsWith("CREATE PROCEDURE", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE PROCEDURE")
                DdlStatement("DROP PROCEDURE IF EXISTS $name;")
            }
            sql.startsWith("CREATE TRIGGER", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE TRIGGER")
                DdlStatement("DROP TRIGGER IF EXISTS $name;")
            }
            sql.startsWith("CREATE SEQUENCE", ignoreCase = true) -> {
                val name = extractNameAfter(sql, "CREATE SEQUENCE")
                DdlStatement("DROP SEQUENCE IF EXISTS $name;")
            }
            sql.startsWith("ALTER TABLE", ignoreCase = true) && sql.contains("ADD CONSTRAINT", ignoreCase = true) -> {
                val tableName = extractNameAfter(sql, "ALTER TABLE")
                val addConstraintIdx = sql.uppercase().indexOf("ADD CONSTRAINT")
                val constraintPart = sql.substring(addConstraintIdx + "ADD CONSTRAINT".length).trimStart()
                val constraintName = constraintPart.split(Regex("[\\s(]"), limit = 2).first()
                DdlStatement("ALTER TABLE $tableName DROP CONSTRAINT IF EXISTS $constraintName;")
            }
            sql.startsWith("--") -> null
            else -> null
        }
    }

    protected fun extractNameAfter(sql: String, keyword: String): String {
        val afterKeyword = sql.substring(keyword.length).trimStart()
        val cleaned = if (afterKeyword.uppercase().startsWith("IF NOT EXISTS"))
            afterKeyword.substring("IF NOT EXISTS".length).trimStart()
        else afterKeyword
        return cleaned.split(Regex("[\\s(]"), limit = 2).first()
    }
}
