package dev.dmigrate.mcp.registry

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ArtifactListFilter
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.DiffListFilter
import dev.dmigrate.server.ports.DiffStore
import dev.dmigrate.server.ports.JobListFilter
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.ProfileListFilter
import dev.dmigrate.server.ports.ProfileStore
import dev.dmigrate.server.ports.SchemaListFilter
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation

/**
 * Phase-D §10.6 list-tool handlers. Five concrete handlers, one
 * per discovery tool, sharing [ListToolHelpers] for tenant
 * resolution + pageSize bounds + filter parsing. Each handler:
 *
 *   1. Resolves the addressed tenant (§6.1: explicit `tenantId` in
 *      `allowedTenantIds`, missing → `effectiveTenantId`).
 *   2. Parses the resource-specific filter into the matching
 *      `*ListFilter` AP-D4 added to the store ports.
 *   3. Decodes any incoming HMAC-sealed cursor (AP D8) — verifying
 *      the cursor was minted for the same tenant + filter + pageSize.
 *      Tampered or expired cursors fail with `VALIDATION_ERROR`.
 *   4. Calls the store's filtered `list(tenantId, filter, page)`
 *      method (default sort `createdAt DESC, id ASC`).
 *   5. Projects each row via [ListItemProjector] (Secret-scrubbed
 *      free-text fields, neutralised visibility class).
 *   6. Wraps the projection list in the §6.4 typed collection
 *      response shape, sealing any next-page cursor with the
 *      same binding.
 *
 * Cursor handling (AP D8 sub-commit 2): when the registry was
 * built with a [SealedListToolCursor] (production path: every
 * Phase-C bootstrap ships an HMAC keyring), each handler binds
 * the cursor to (cursorType, tenantId, family, filters,
 * pageSize). When the codec is null (Phase-B / legacy tests),
 * `nextCursor` stays null per AP D6 — clients see only the
 * first page. The two paths share the same handler shape so
 * existing tests that don't wire the codec keep their semantics.
 */

private val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

internal class JobListHandler(
    private val jobStore: JobStore,
    private val cursorCodec: SealedListToolCursor? = null,
) : ToolHandler {
    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = context.arguments.asListToolArgs()
        val tenantId = ListToolHelpers.resolveTenant(args, context.principal)
        val pageSize = ListToolHelpers.resolvePageSize(args)
        val filter = JobListFilter(
            ownerFilter = null,
            status = ListToolHelpers.resolveString(args, "status")?.let { parseJobStatus(it) },
            operation = ListToolHelpers.resolveString(args, "operation"),
            createdAfter = ListToolHelpers.resolveInstant(args, "createdAfter"),
            createdBefore = ListToolHelpers.resolveInstant(args, "createdBefore"),
        )
        val filterBinding = filter.toBinding()
        val resumeToken = decodeIncomingCursor(
            args, cursorCodec, CURSOR_JOB_LIST, tenantId, FAMILY_JOBS, filterBinding, pageSize,
        )
        val page = jobStore.list(tenantId, filter, PageRequest(pageSize = pageSize, pageToken = resumeToken))
        // Apply per-record visibility: principal-filtered, no
        // existence detail (§6.1 + §10.6). Pass the addressed
        // tenant so an explicit `tenantId` (in allowedTenantIds but
        // distinct from effectiveTenantId) doesn't drop every row.
        val visible = page.items.filter { it.isReadableBy(context.principal, tenantId) }
        val items = visible.map(ListItemProjector::projectJobListItem)
        val nextCursor = sealOutgoingCursor(
            cursorCodec, CURSOR_JOB_LIST, tenantId, FAMILY_JOBS, filterBinding, pageSize, page.nextPageToken,
        )
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(mapOf("jobs" to items, "nextCursor" to nextCursor)),
                    mimeType = "application/json",
                ),
            ),
        )
    }
}

internal class ArtifactListHandler(
    private val artifactStore: ArtifactStore,
    private val cursorCodec: SealedListToolCursor? = null,
) : ToolHandler {
    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = context.arguments.asListToolArgs()
        val tenantId = ListToolHelpers.resolveTenant(args, context.principal)
        val pageSize = ListToolHelpers.resolvePageSize(args)
        val filter = ArtifactListFilter(
            kindFilter = ListToolHelpers.resolveString(args, "kind")?.let { parseArtifactKind(it) },
            jobRef = ListToolHelpers.resolveString(args, "jobId"),
            createdAfter = ListToolHelpers.resolveInstant(args, "createdAfter"),
            createdBefore = ListToolHelpers.resolveInstant(args, "createdBefore"),
        )
        val filterBinding = filter.toBinding()
        val resumeToken = decodeIncomingCursor(
            args, cursorCodec, CURSOR_ARTIFACT_LIST, tenantId, FAMILY_ARTIFACTS, filterBinding, pageSize,
        )
        val page = artifactStore.list(tenantId, filter, PageRequest(pageSize = pageSize, pageToken = resumeToken))
        val visible = page.items.filter { it.isReadableBy(context.principal, tenantId) }
        val items = visible.map(ListItemProjector::projectArtifactListItem)
        val nextCursor = sealOutgoingCursor(
            cursorCodec, CURSOR_ARTIFACT_LIST, tenantId, FAMILY_ARTIFACTS, filterBinding, pageSize, page.nextPageToken,
        )
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(mapOf("artifacts" to items, "nextCursor" to nextCursor)),
                    mimeType = "application/json",
                ),
            ),
        )
    }
}

internal class SchemaListHandler(
    private val schemaStore: SchemaStore,
    private val cursorCodec: SealedListToolCursor? = null,
) : ToolHandler {
    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = context.arguments.asListToolArgs()
        val tenantId = ListToolHelpers.resolveTenant(args, context.principal)
        val pageSize = ListToolHelpers.resolvePageSize(args)
        val filter = SchemaListFilter(
            jobRef = ListToolHelpers.resolveString(args, "jobId"),
            createdAfter = ListToolHelpers.resolveInstant(args, "createdAfter"),
            createdBefore = ListToolHelpers.resolveInstant(args, "createdBefore"),
        )
        val filterBinding = filter.toBinding()
        val resumeToken = decodeIncomingCursor(
            args, cursorCodec, CURSOR_SCHEMA_LIST, tenantId, FAMILY_SCHEMAS, filterBinding, pageSize,
        )
        val page = schemaStore.list(tenantId, filter, PageRequest(pageSize = pageSize, pageToken = resumeToken))
        val items = page.items.map(ListItemProjector::projectSchemaListItem)
        val nextCursor = sealOutgoingCursor(
            cursorCodec, CURSOR_SCHEMA_LIST, tenantId, FAMILY_SCHEMAS, filterBinding, pageSize, page.nextPageToken,
        )
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(mapOf("schemas" to items, "nextCursor" to nextCursor)),
                    mimeType = "application/json",
                ),
            ),
        )
    }
}

internal class ProfileListHandler(
    private val profileStore: ProfileStore,
    private val cursorCodec: SealedListToolCursor? = null,
) : ToolHandler {
    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = context.arguments.asListToolArgs()
        val tenantId = ListToolHelpers.resolveTenant(args, context.principal)
        val pageSize = ListToolHelpers.resolvePageSize(args)
        val filter = ProfileListFilter(
            jobRef = ListToolHelpers.resolveString(args, "jobId"),
            createdAfter = ListToolHelpers.resolveInstant(args, "createdAfter"),
            createdBefore = ListToolHelpers.resolveInstant(args, "createdBefore"),
        )
        val filterBinding = filter.toBinding()
        val resumeToken = decodeIncomingCursor(
            args, cursorCodec, CURSOR_PROFILE_LIST, tenantId, FAMILY_PROFILES, filterBinding, pageSize,
        )
        val page = profileStore.list(tenantId, filter, PageRequest(pageSize = pageSize, pageToken = resumeToken))
        val items = page.items.map(ListItemProjector::projectProfileListItem)
        val nextCursor = sealOutgoingCursor(
            cursorCodec, CURSOR_PROFILE_LIST, tenantId, FAMILY_PROFILES, filterBinding, pageSize, page.nextPageToken,
        )
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(mapOf("profiles" to items, "nextCursor" to nextCursor)),
                    mimeType = "application/json",
                ),
            ),
        )
    }
}

internal class DiffListHandler(
    private val diffStore: DiffStore,
    private val cursorCodec: SealedListToolCursor? = null,
) : ToolHandler {
    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = context.arguments.asListToolArgs()
        val tenantId = ListToolHelpers.resolveTenant(args, context.principal)
        val pageSize = ListToolHelpers.resolvePageSize(args)
        val filter = DiffListFilter(
            jobRef = ListToolHelpers.resolveString(args, "jobId"),
            sourceRef = ListToolHelpers.resolveString(args, "sourceRef"),
            targetRef = ListToolHelpers.resolveString(args, "targetRef"),
            createdAfter = ListToolHelpers.resolveInstant(args, "createdAfter"),
            createdBefore = ListToolHelpers.resolveInstant(args, "createdBefore"),
        )
        val filterBinding = filter.toBinding()
        val resumeToken = decodeIncomingCursor(
            args, cursorCodec, CURSOR_DIFF_LIST, tenantId, FAMILY_DIFFS, filterBinding, pageSize,
        )
        val page = diffStore.list(tenantId, filter, PageRequest(pageSize = pageSize, pageToken = resumeToken))
        val items = page.items.map(ListItemProjector::projectDiffListItem)
        val nextCursor = sealOutgoingCursor(
            cursorCodec, CURSOR_DIFF_LIST, tenantId, FAMILY_DIFFS, filterBinding, pageSize, page.nextPageToken,
        )
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(mapOf("diffs" to items, "nextCursor" to nextCursor)),
                    mimeType = "application/json",
                ),
            ),
        )
    }
}

@Suppress("SwallowedException")
private fun parseJobStatus(raw: String): JobStatus = try {
    JobStatus.valueOf(raw)
} catch (e: IllegalArgumentException) {
    // SwallowedException: the JVM's "No enum constant …" message
    // would echo the raw enum class path back to the client; the
    // typed VALIDATION_ERROR only needs the offending field+value.
    throw ValidationErrorException(
        listOf(ValidationViolation("status", "unknown JobStatus '$raw'")),
    )
}

@Suppress("SwallowedException")
private fun parseArtifactKind(raw: String): ArtifactKind = try {
    ArtifactKind.valueOf(raw)
} catch (e: IllegalArgumentException) {
    // SwallowedException: same trust-boundary rule as parseJobStatus.
    throw ValidationErrorException(
        listOf(ValidationViolation("kind", "unknown ArtifactKind '$raw'")),
    )
}

private const val CURSOR_JOB_LIST: String = "job_list"
private const val CURSOR_ARTIFACT_LIST: String = "artifact_list"
private const val CURSOR_SCHEMA_LIST: String = "schema_list"
private const val CURSOR_PROFILE_LIST: String = "profile_list"
private const val CURSOR_DIFF_LIST: String = "diff_list"

private const val FAMILY_JOBS: String = "jobs"
private const val FAMILY_ARTIFACTS: String = "artifacts"
private const val FAMILY_SCHEMAS: String = "schemas"
private const val FAMILY_PROFILES: String = "profiles"
private const val FAMILY_DIFFS: String = "diffs"

private fun decodeIncomingCursor(
    args: com.google.gson.JsonObject?,
    codec: SealedListToolCursor?,
    cursorType: String,
    tenantId: TenantId,
    family: String,
    filters: Map<String, String>,
    pageSize: Int,
): String? {
    val raw = ListToolHelpers.resolveString(args, "cursor") ?: return null
    if (codec == null) {
        // Phase-B / legacy harness without a configured keyring:
        // refuse to honour a client-supplied cursor rather than
        // silently treat it as "first page" (which would mask a
        // pagination bug). The Phase-D wire contract is that
        // cursors round-trip; null is the only legal "first" value.
        throw ValidationErrorException(
            listOf(ValidationViolation("cursor", "cursor verification is not configured on this server")),
        )
    }
    return codec.unseal(
        sealed = raw,
        cursorType = cursorType,
        tenantId = tenantId,
        family = family,
        filters = filters,
        pageSize = pageSize,
    )
}

private fun sealOutgoingCursor(
    codec: SealedListToolCursor?,
    cursorType: String,
    tenantId: TenantId,
    family: String,
    filters: Map<String, String>,
    pageSize: Int,
    resumeToken: String?,
): String? {
    if (resumeToken == null) return null
    if (codec == null) return null
    return codec.seal(cursorType, tenantId, family, filters, pageSize, resumeToken)
}

// Filter → binding-map projections. Map ordering is stable
// (LinkedHashMap) so the codec's filter equality check is
// deterministic across encode/decode round-trips.

private fun JobListFilter.toBinding(): Map<String, String> = buildMap {
    if (ownerFilter != null) put("ownerFilter", ownerFilter!!.value)
    if (status != null) put("status", status!!.name)
    if (operation != null) put("operation", operation!!)
    if (createdAfter != null) put("createdAfter", createdAfter!!.toString())
    if (createdBefore != null) put("createdBefore", createdBefore!!.toString())
}

private fun ArtifactListFilter.toBinding(): Map<String, String> = buildMap {
    if (kindFilter != null) put("kind", kindFilter!!.name)
    if (jobRef != null) put("jobId", jobRef!!)
    if (createdAfter != null) put("createdAfter", createdAfter!!.toString())
    if (createdBefore != null) put("createdBefore", createdBefore!!.toString())
}

private fun SchemaListFilter.toBinding(): Map<String, String> = buildMap {
    if (jobRef != null) put("jobId", jobRef!!)
    if (createdAfter != null) put("createdAfter", createdAfter!!.toString())
    if (createdBefore != null) put("createdBefore", createdBefore!!.toString())
}

private fun ProfileListFilter.toBinding(): Map<String, String> = buildMap {
    if (jobRef != null) put("jobId", jobRef!!)
    if (createdAfter != null) put("createdAfter", createdAfter!!.toString())
    if (createdBefore != null) put("createdBefore", createdBefore!!.toString())
}

private fun DiffListFilter.toBinding(): Map<String, String> = buildMap {
    if (jobRef != null) put("jobId", jobRef!!)
    if (sourceRef != null) put("sourceRef", sourceRef!!)
    if (targetRef != null) put("targetRef", targetRef!!)
    if (createdAfter != null) put("createdAfter", createdAfter!!.toString())
    if (createdBefore != null) put("createdBefore", createdBefore!!.toString())
}
