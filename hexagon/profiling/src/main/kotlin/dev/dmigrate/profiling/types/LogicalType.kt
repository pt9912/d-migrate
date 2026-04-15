package dev.dmigrate.profiling.types

/**
 * Classifies observed data content for profiling purposes.
 *
 * Deliberately separate from [dev.dmigrate.core.model.NeutralType] (schema-oriented,
 * 18+ types). LogicalType is data-oriented (10 types) and describes what kind of
 * data a column actually contains, not how the schema declares it.
 */
enum class LogicalType {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    DATE,
    DATETIME,
    BINARY,
    JSON,
    GEOMETRY,
    UNKNOWN,
}
