package dev.dmigrate.mcp.registry

/**
 * Transport-neutral tool registry per `ImpPlan-0.9.6-B.md` §4.7 +
 * §6.8.
 *
 * Both stdio and HTTP MUST share the same registry instance — see
 * §6.8 acceptance ("stdio und HTTP nutzen dieselben Registry-
 * Instanzen"). The bootstrap creates it once via [PhaseBRegistries]
 * and threads it into every `McpServiceImpl`.
 *
 * The registry is intentionally read-only after construction: handler
 * registration in 0.9.6 is static (Phase C/D will swap in real
 * handlers via configuration, not via runtime registration).
 *
 * Lookup semantics (§6.8 + §12.8):
 * - [find] returns `null` for unknown names; the caller maps that to
 *   JSON-RPC `-32601` "Method not found".
 * - [findHandler] returns the registered handler — including
 *   [UnsupportedToolHandler] for known-but-not-implemented tools. The
 *   dispatcher invokes it; the handler raises
 *   `UnsupportedToolOperationException`, which becomes a tool result
 *   with `isError=true` (NOT a JSON-RPC error).
 */
class ToolRegistry internal constructor(
    private val entries: Map<String, Entry>,
) {

    internal data class Entry(
        val descriptor: ToolDescriptor,
        val handler: ToolHandler,
    )

    /** Snapshot of all registered descriptors in registration order. */
    fun all(): List<ToolDescriptor> = entries.values.map { it.descriptor }

    /** Descriptor lookup; `null` means the tool name is unknown. */
    fun find(name: String): ToolDescriptor? = entries[name]?.descriptor

    /** Handler lookup; `null` means the tool name is unknown. */
    fun findHandler(name: String): ToolHandler? = entries[name]?.handler

    /** Registered tool names; preserves insertion order. */
    fun names(): List<String> = entries.keys.toList()

    class Builder {

        private val entries = LinkedHashMap<String, Entry>()

        fun register(descriptor: ToolDescriptor, handler: ToolHandler): Builder = apply {
            require(descriptor.name !in entries) {
                "tool '${descriptor.name}' already registered"
            }
            entries[descriptor.name] = Entry(descriptor, handler)
        }

        fun build(): ToolRegistry = ToolRegistry(entries.toMap())
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
