package dev.dmigrate.mcp.resources

import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.ConnectionReferenceStore
import dev.dmigrate.server.ports.DiffStore
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.ProfileStore
import dev.dmigrate.server.ports.SchemaStore

/**
 * Aggregate of the six store ports `resources/list` walks per
 * `ImpPlan-0.9.6-B.md` §5.5 + §6.9. Bundled so the bootstrap, the
 * route, and tests can all pass a single value instead of six.
 *
 * Phase B does NOT ship a built-in production implementation —
 * callers (CLI, integration tests) wire whichever backend they prefer
 * (in-memory for demos, real stores for production). [empty] returns
 * a bundle of empty in-memory stores so the bootstrap can start
 * without listing anything; useful for the auth/transport smoke
 * tests where resources/list is irrelevant.
 */
data class ResourceStores(
    val jobStore: JobStore,
    val artifactStore: ArtifactStore,
    val schemaStore: SchemaStore,
    val profileStore: ProfileStore,
    val diffStore: DiffStore,
    val connectionStore: ConnectionReferenceStore,
) {
    companion object {
        fun empty(): ResourceStores = ResourceStores(
            jobStore = EmptyJobStore,
            artifactStore = EmptyArtifactStore,
            schemaStore = EmptySchemaStore,
            profileStore = EmptyProfileStore,
            diffStore = EmptyDiffStore,
            connectionStore = EmptyConnectionStore,
        )
    }
}
