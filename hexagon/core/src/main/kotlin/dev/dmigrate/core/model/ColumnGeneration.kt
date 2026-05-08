package dev.dmigrate.core.model

sealed interface ColumnGeneration {
    data class Identity(
        val mode: IdentityMode = IdentityMode.BY_DEFAULT,
        val sequenceName: String? = null,
        val legacySerialSyntax: Boolean = false,
    ) : ColumnGeneration
}

enum class IdentityMode {
    ALWAYS,
    BY_DEFAULT,
}
