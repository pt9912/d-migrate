package dev.dmigrate.server.core.pagination

data class PageRequest(
    val pageSize: Int,
    val pageToken: String? = null,
)

data class PageResult<T>(
    val items: List<T>,
    val nextPageToken: String? = null,
)
