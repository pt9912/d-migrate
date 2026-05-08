package dev.dmigrate.mcp.registry

/**
 * Transport-neutral resource registry per LF-012 / LN-027 / LN-028 / LN-038
 * §5.5. The registry is the single source of truth for
 * `resources/templates/list` (and LF-012 / LN-038 `resources/read` tooling);
 * stdio and HTTP MUST share the same instance so the two transports
 * can never advertise divergent template sets.
 *
 * LF-012 / LN-038 (LF-012 / LN-027 / LN-028 / LN-038) registers the 7 standard resource templates
 * (jobs, artifacts, artifact-chunks, schemas, profiles, diffs,
 * connections) via `McpContractRegistries.resourceRegistry()`. Concrete
 * resource projections are NOT registered here — they're produced on
 * the fly by `ResourcesListHandler` from the configured
 * `ResourceStores`. LF-012 / LN-038 can register concrete resources here
 * if a future feature wants them in `tools/list`-style discovery.
 */
class ResourceRegistry internal constructor(
    private val resources: List<ResourceDescriptor>,
    private val templates: List<ResourceTemplateDescriptor>,
) {

    fun resources(): List<ResourceDescriptor> = resources

    fun templates(): List<ResourceTemplateDescriptor> = templates

    fun isEmpty(): Boolean = resources.isEmpty() && templates.isEmpty()

    class Builder {

        private val resources = mutableListOf<ResourceDescriptor>()
        private val templates = mutableListOf<ResourceTemplateDescriptor>()

        fun register(descriptor: ResourceDescriptor): Builder = apply {
            resources += descriptor
        }

        fun registerTemplate(descriptor: ResourceTemplateDescriptor): Builder = apply {
            templates += descriptor
        }

        fun build(): ResourceRegistry =
            ResourceRegistry(resources.toList(), templates.toList())
    }

    companion object {
        fun builder(): Builder = Builder()
        fun empty(): ResourceRegistry = ResourceRegistry(emptyList(), emptyList())
    }
}
