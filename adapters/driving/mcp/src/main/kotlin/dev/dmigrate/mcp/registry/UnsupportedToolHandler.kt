package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.error.UnsupportedToolOperationException

/**
 * Default [ToolHandler] for tools that are registered but not yet
 * implemented per `ImpPlan-0.9.6-B.md` §6.8 + §3.1. Phase B keeps the
 * registry complete (every 0.9.6 tool is discoverable) but only
 * `capabilities_list` is wired to a real handler — every other entry
 * uses this stub so:
 *
 * - the contract is visible on `tools/list`,
 * - `tools/call` returns a `ToolErrorEnvelope` with code
 *   `UNSUPPORTED_TOOL_OPERATION` (per §12.8) instead of `-32601`
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
