package dev.dmigrate.mcp.resources

import dev.dmigrate.mcp.registry.PhaseCWiring
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.ArtifactContentStore
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
    val artifactContentStore: ArtifactContentStore = EmptyArtifactContentStore,
) {
    companion object {
        fun empty(): ResourceStores = ResourceStores(
            jobStore = EmptyJobStore,
            artifactStore = EmptyArtifactStore,
            schemaStore = EmptySchemaStore,
            profileStore = EmptyProfileStore,
            diffStore = EmptyDiffStore,
            connectionStore = EmptyConnectionStore,
            artifactContentStore = EmptyArtifactContentStore,
        )

        /**
         * Builds a [ResourceStores] from a [PhaseCWiring]. Job,
         * artifact and schema stores are taken from the wiring so
         * `resources/read` sees the same records the Phase-C tools
         * write. Profile, diff and connection stores fall back to
         * empty placeholders because [PhaseCWiring] does not carry
         * them — Phase-C tools never touch those families. AP 6.9
         * acceptance: production bootstrap uses this factory so
         * `resources/read` is never wired against [empty] when a
         * Phase-C wiring is in scope.
         */
        fun fromPhaseCWiring(wiring: PhaseCWiring): ResourceStores = ResourceStores(
            jobStore = wiring.jobStore,
            artifactStore = wiring.artifactStore,
            schemaStore = wiring.schemaStore,
            // AP D6 added profile/diff stores to PhaseCWiring (default
            // Empty); wire them through so Phase-D `resources/read` and
            // the discovery list handlers see the same backing store.
            profileStore = wiring.profileStore,
            diffStore = wiring.diffStore,
            // AP D10: thread the connection-reference store from the
            // wiring so production bootstrap (which wires a
            // `LoaderBackedConnectionReferenceStore`) seeds the
            // discovery surface with secret-free connection records.
            connectionStore = wiring.connectionStore,
            artifactContentStore = wiring.artifactContentStore,
        )
    }
}
