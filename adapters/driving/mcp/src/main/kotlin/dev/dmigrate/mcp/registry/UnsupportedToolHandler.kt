package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.error.UnsupportedToolOperationException

/**
 * Default [ToolHandler] for tools that are registered but not yet
 * implemented. The registry stays complete so every contract tool is
 * discoverable, while this stub keeps unimplemented entries explicit:
 *
 * - the contract is visible on `tools/list`,
 * - `tools/call` returns a `ToolErrorEnvelope` with code
 *   `UNSUPPORTED_TOOL_OPERATION` instead of `-32601`
 *   (which is reserved for *unknown* method names),
 * - clients can distinguish "wrong tool name" from "tool not yet
 *   implemented" without parsing free-form messages.
 *
 * Throws `UnsupportedToolOperationException` rather than returning
 * [ToolCallOutcome.Error] so the dispatch path runs the standard
 * [DefaultErrorMapper] mapping (consistent with the rest of the
 * application).
 */
internal class UnsupportedToolHandler(private val operation: String = "default") : ToolHandler {

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        throw UnsupportedToolOperationException(toolName = context.name, operation = operation)
    }
}
