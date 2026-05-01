package dev.dmigrate.mcp.resources

import dev.dmigrate.mcp.protocol.Resource
import dev.dmigrate.mcp.protocol.ResourcesListResult
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind

/**
 * Sequential walker for `resources/list` per `ImpPlan-0.9.6-B.md` §6.9
 * + §12.17.
 *
 * The handler walks resource families in a fixed order
 * (`ResourceKind.entries` minus `UPLOAD_SESSIONS` — that family is not
 * MCP-resource-shaped). Each invocation collects up to [pageSize]
 * principal-readable items and returns an opaque `nextCursor` that
 * encodes (kind, innerToken) so the next call resumes exactly where
 * this one stopped.
 *
 * Filtering: tenant-scoping is implicit via the per-store
 * `tenantId = principal.effectiveTenantId` argument; per-record
 * visibility (`OWNER`/`TENANT`/`ADMIN`) is checked via
 * `JobRecord.isReadableBy` / `ArtifactRecord.isReadableBy` — Phase B
 * does not yet persist visibility on schema/profile/diff/connection
 * records, so those are returned as-is for the principal's tenant.
 *
 * `nextCursor=null` means "no more pages anywhere"; an empty
 * `resources` list with a non-null `nextCursor` is legal (filter
 * dropped every record on this store page) — clients SHOULD continue
 * paging.
 */
internal class ResourcesListHandler(
    private val stores: ResourceStores,
    private val defaultPageSize: Int = DEFAULT_PAGE_SIZE,
) {

    /**
     * @param cursor opaque cursor from the previous response or null
     *  for the first page. Caller should pre-validate via
     *  `ResourcesListCursor.decode`; this handler accepts the parsed
     *  form so the route can map decode failures into JSON-RPC
     *  `-32602` (Invalid params) before reaching here.
     */
    fun list(
        principal: PrincipalContext,
        cursor: ResourcesListCursor?,
        pageSize: Int = defaultPageSize,
    ): ResourcesListResult {
        require(pageSize > 0) { "pageSize must be > 0 (got $pageSize)" }
        val collected = mutableListOf<Resource>()
        var current: ResourceKind? = cursor?.kind ?: WALK_ORDER.first()
        var token: String? = cursor?.innerToken

        while (current != null && collected.size < pageSize) {
            val request = PageRequest(pageSize = pageSize - collected.size, pageToken = token)
            val pageResources = pageFor(current, principal, request)
            collected += pageResources.resources
            if (pageResources.nextToken != null) {
                // store has more pages within current kind — pin cursor
                // here and stop. Don't combine kinds in the same response;
                // keeping cursor simple beats any response-size win.
                return ResourcesListResult(
                    resources = collected,
                    nextCursor = ResourcesListCursor(current, pageResources.nextToken).encode(),
                )
            }
            // current kind drained — advance, reset token
            current = nextKind(current)
            token = null
        }
        val nextCursor = current?.let { ResourcesListCursor(it, null).encode() }
        return ResourcesListResult(resources = collected, nextCursor = nextCursor)
    }

    private data class StorePageProjection(
        val resources: List<Resource>,
        val nextToken: String?,
    )

    private fun pageFor(
        kind: ResourceKind,
        principal: PrincipalContext,
        page: PageRequest,
    ): StorePageProjection = when (kind) {
        ResourceKind.JOBS -> projectJobs(principal, page)
        ResourceKind.ARTIFACTS -> projectArtifacts(principal, page)
        ResourceKind.SCHEMAS -> projectSchemas(principal, page)
        ResourceKind.PROFILES -> projectProfiles(principal, page)
        ResourceKind.DIFFS -> projectDiffs(principal, page)
        ResourceKind.CONNECTIONS -> projectConnections(principal, page)
        ResourceKind.UPLOAD_SESSIONS -> StorePageProjection(emptyList(), null)
    }

    private fun projectJobs(principal: PrincipalContext, page: PageRequest): StorePageProjection {
        val result = stores.jobStore.list(principal.effectiveTenantId, page)
        val readable = result.items.filter { it.isReadableBy(principal) }
        return StorePageProjection(readable.map(ResourceProjector::project), result.nextPageToken)
    }

    private fun projectArtifacts(principal: PrincipalContext, page: PageRequest): StorePageProjection {
        val result = stores.artifactStore.list(principal.effectiveTenantId, page)
        val readable = result.items.filter { it.isReadableBy(principal) }
        return StorePageProjection(readable.map(ResourceProjector::project), result.nextPageToken)
    }

    private fun projectSchemas(principal: PrincipalContext, page: PageRequest): StorePageProjection {
        val result: PageResult<dev.dmigrate.server.ports.SchemaIndexEntry> =
            stores.schemaStore.list(principal.effectiveTenantId, page)
        return StorePageProjection(result.items.map(ResourceProjector::project), result.nextPageToken)
    }

    private fun projectProfiles(principal: PrincipalContext, page: PageRequest): StorePageProjection {
        val result = stores.profileStore.list(principal.effectiveTenantId, page)
        return StorePageProjection(result.items.map(ResourceProjector::project), result.nextPageToken)
    }

    private fun projectDiffs(principal: PrincipalContext, page: PageRequest): StorePageProjection {
        val result = stores.diffStore.list(principal.effectiveTenantId, page)
        return StorePageProjection(result.items.map(ResourceProjector::project), result.nextPageToken)
    }

    private fun projectConnections(principal: PrincipalContext, page: PageRequest): StorePageProjection {
        val result = stores.connectionStore.list(principal, page)
        return StorePageProjection(result.items.map(ResourceProjector::project), result.nextPageToken)
    }

    private fun nextKind(current: ResourceKind): ResourceKind? {
        val idx = WALK_ORDER.indexOf(current)
        return if (idx < 0 || idx == WALK_ORDER.lastIndex) null else WALK_ORDER[idx + 1]
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50

        /**
         * §5.5 + §6.9: jobs, artifacts, schemas, profiles, diffs,
         * connections. UPLOAD_SESSIONS exists in [ResourceKind] but is
         * NOT an MCP resource — it's an internal phase-B/C concept.
         */
        val WALK_ORDER: List<ResourceKind> = listOf(
            ResourceKind.JOBS,
            ResourceKind.ARTIFACTS,
            ResourceKind.SCHEMAS,
            ResourceKind.PROFILES,
            ResourceKind.DIFFS,
            ResourceKind.CONNECTIONS,
        )
    }
}
