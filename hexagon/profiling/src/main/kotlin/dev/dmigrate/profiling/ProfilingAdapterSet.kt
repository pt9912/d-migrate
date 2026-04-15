package dev.dmigrate.profiling

import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.port.SchemaIntrospectionPort

/**
 * Groups the three profiling adapters for a specific dialect.
 * Injected into services and runners — NOT looked up via DatabaseDriver.
 */
data class ProfilingAdapterSet(
    val introspection: SchemaIntrospectionPort,
    val data: ProfilingDataPort,
    val typeResolver: LogicalTypeResolverPort,
)
