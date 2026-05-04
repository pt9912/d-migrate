package dev.dmigrate.mcp.registry

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.pagination.PageRequest
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
 *   3. Calls the store's filtered `list(tenantId, filter, page)`
 *      method (default sort `createdAt DESC, id ASC`).
 *   4. Projects each row via [ListItemProjector] (Secret-scrubbed
 *      free-text fields, neutralised visibility class).
 *   5. Wraps the projection list in the §6.4 typed collection
 *      response shape.
 *
 * Cursor handling: AP D6 returns `nextCursor=null` when more
 * pages exist (the underlying store's `nextPageToken` is dropped
 * for now). AP D8 wires the HMAC-sealed cursor codec from AP D3
 * around these handlers and replaces the no-op with a
 * tenant/family/filter-bound cursor. Until then, callers see only
 * the first page — useful for read-only smoke usage and the
 * Phase-D acceptance bullets §10.6 covers (empty store / one
 * match / per-tool filter classes).
 */

private val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

internal class JobListHandler(
    private val jobStore: JobStore,
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
        val page = jobStore.list(tenantId, filter, PageRequest(pageSize = pageSize))
        // Apply per-record visibility: principal-filtered, no
        // existence detail (§6.1 + §10.6). Pass the addressed
        // tenant so an explicit `tenantId` (in allowedTenantIds but
        // distinct from effectiveTenantId) doesn't drop every row.
        val visible = page.items.filter { it.isReadableBy(context.principal, tenantId) }
        val items = visible.map(ListItemProjector::projectJobListItem)
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(mapOf("jobs" to items, "nextCursor" to null)),
                    mimeType = "application/json",
                ),
            ),
        )
    }
}

internal class ArtifactListHandler(
    private val artifactStore: ArtifactStore,
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
        val page = artifactStore.list(tenantId, filter, PageRequest(pageSize = pageSize))
        val visible = page.items.filter { it.isReadableBy(context.principal, tenantId) }
        val items = visible.map(ListItemProjector::projectArtifactListItem)
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(mapOf("artifacts" to items, "nextCursor" to null)),
                    mimeType = "application/json",
                ),
            ),
        )
    }
}

internal class SchemaListHandler(
    private val schemaStore: SchemaStore,
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
        val page = schemaStore.list(tenantId, filter, PageRequest(pageSize = pageSize))
        val items = page.items.map(ListItemProjector::projectSchemaListItem)
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(mapOf("schemas" to items, "nextCursor" to null)),
                    mimeType = "application/json",
                ),
            ),
        )
    }
}

internal class ProfileListHandler(
    private val profileStore: ProfileStore,
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
        val page = profileStore.list(tenantId, filter, PageRequest(pageSize = pageSize))
        val items = page.items.map(ListItemProjector::projectProfileListItem)
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(mapOf("profiles" to items, "nextCursor" to null)),
                    mimeType = "application/json",
                ),
            ),
        )
    }
}

internal class DiffListHandler(
    private val diffStore: DiffStore,
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
        val page = diffStore.list(tenantId, filter, PageRequest(pageSize = pageSize))
        val items = page.items.map(ListItemProjector::projectDiffListItem)
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(mapOf("diffs" to items, "nextCursor" to null)),
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
