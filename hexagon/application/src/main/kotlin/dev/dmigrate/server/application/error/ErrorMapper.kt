package dev.dmigrate.server.application.error

import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorEnvelope

interface ErrorMapper {

    fun map(throwable: Throwable, requestId: String? = null): ToolErrorEnvelope
}

class DefaultErrorMapper : ErrorMapper {

    override fun map(throwable: Throwable, requestId: String?): ToolErrorEnvelope =
        when (throwable) {
            is ApplicationException -> ToolErrorEnvelope(
                code = throwable.code,
                message = throwable.message ?: throwable.code.name,
                details = throwable.details(),
                requestId = requestId,
            )
            else -> ToolErrorEnvelope(
                code = ToolErrorCode.INTERNAL_AGENT_ERROR,
                message = "Internal agent error",
                requestId = requestId,
            )
        }
}
