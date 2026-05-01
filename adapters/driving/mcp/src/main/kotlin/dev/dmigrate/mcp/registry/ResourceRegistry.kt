package dev.dmigrate.mcp.registry

/**
 * Transport-neutral resource registry per `ImpPlan-0.9.6-B.md` §4.7 +
 * §5.5. Phase B prepares the type contract (so AP 6.9 only needs to
 * fill it with real projections); the registry itself is empty in AP
 * 6.8 because Phase B does not implement `resources/list` yet.
 *
 * AP 6.9 will register one [ResourceTemplateDescriptor] per dmigrate
 * resource family (jobs, artifacts, schemas, profiles, diffs,
 * connections) and per-tenant resource projections via
 * [ResourceDescriptor]. Both stdio and HTTP read the same registry
 * instance.
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
