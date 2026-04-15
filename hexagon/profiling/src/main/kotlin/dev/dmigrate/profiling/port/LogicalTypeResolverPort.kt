package dev.dmigrate.profiling.port

import dev.dmigrate.profiling.types.LogicalType

/**
 * Outbound port for resolving a raw database type string to a [LogicalType].
 *
 * Each dialect implements its own resolver. The mapping is based on the
 * actual DB type (e.g., `VARCHAR(255)`, `INT`, `JSONB`), not on the
 * neutral type system.
 */
interface LogicalTypeResolverPort {
    fun resolve(dbType: String): LogicalType
}
