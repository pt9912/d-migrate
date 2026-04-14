package dev.dmigrate.driver

/**
 * Input for the Reverse-Report writer.
 *
 * Combines the [SchemaReadResult] with a typed source reference so
 * the report can display where the schema was read from without
 * [SchemaReadResult] itself needing to know about sources.
 */
data class SchemaReadReportInput(
    val source: ReverseSourceRef,
    val result: SchemaReadResult,
)

/**
 * Typed reference to the schema source for report rendering.
 *
 * - [ReverseSourceKind.ALIAS]: a named connection alias — rendered as-is
 * - [ReverseSourceKind.URL]: a connection URL — must be scrubbed before
 *   rendering to prevent credential leaks
 */
data class ReverseSourceRef(
    val kind: ReverseSourceKind,
    val value: String,
)

enum class ReverseSourceKind { ALIAS, URL }
