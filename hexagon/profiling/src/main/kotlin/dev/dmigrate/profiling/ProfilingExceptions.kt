package dev.dmigrate.profiling

/**
 * Base exception for profiling runtime errors.
 * Services throw these; the runner maps them to exit codes.
 */
open class ProfilingException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class SchemaIntrospectionError(message: String, cause: Throwable? = null) :
    ProfilingException(message, cause)

class ProfilingQueryError(message: String, cause: Throwable? = null) :
    ProfilingException(message, cause)

class TypeResolutionError(message: String, cause: Throwable? = null) :
    ProfilingException(message, cause)
