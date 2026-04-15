package dev.dmigrate.profiling.types

/**
 * Target types for compatibility checking during migration feasibility analysis.
 *
 * These represent the common destination types when migrating data between
 * database systems. A [TargetTypeCompatibility] check evaluates whether actual
 * column values can be safely converted to a given target type.
 */
enum class TargetLogicalType {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    DATE,
    DATETIME,
}
