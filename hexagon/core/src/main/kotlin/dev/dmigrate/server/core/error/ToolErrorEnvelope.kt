package dev.dmigrate.server.core.error

data class ToolErrorDetail(
    val key: String,
    val value: String,
)

data class ToolErrorEnvelope(
    val code: ToolErrorCode,
    val message: String,
    val details: List<ToolErrorDetail> = emptyList(),
    val requestId: String? = null,
)
