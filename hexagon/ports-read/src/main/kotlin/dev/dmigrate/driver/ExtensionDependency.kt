package dev.dmigrate.driver

/**
 * Caller-provided declaration of an extension's availability in the
 * target database. File-to-file rendering leaves this empty: renderers
 * must then treat extension-dependent operations as non-verifiable
 * rather than assuming the target has the extension installed.
 */
data class ExtensionAvailabilityDeclaration(
    val dialect: String,
    val extension: String,
    val status: ExtensionAvailabilityStatus,
)

enum class ExtensionAvailabilityStatus {
    VERIFIED_PRESENT,
    MISSING,
    UNKNOWN,
}

/**
 * Renderer-produced summary of an extension dependency discovered
 * while translating a migration plan.
 */
data class ExtensionDependencyReport(
    val dialect: String,
    val extension: String,
    val status: ExtensionAvailabilityStatus,
    val operationIds: Set<String> = emptySet(),
    val installStatement: String? = null,
)
