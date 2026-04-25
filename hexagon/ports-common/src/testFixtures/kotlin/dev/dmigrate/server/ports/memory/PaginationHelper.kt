package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult

internal fun <T> paginate(items: List<T>, page: PageRequest): PageResult<T> {
    val pageSize = page.pageSize.coerceAtLeast(1)
    val offset = page.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val effectiveOffset = offset.coerceAtMost(items.size)
    val end = (effectiveOffset + pageSize).coerceAtMost(items.size)
    val slice = items.subList(effectiveOffset, end)
    val nextToken = if (end < items.size) end.toString() else null
    return PageResult(items = slice, nextPageToken = nextToken)
}
