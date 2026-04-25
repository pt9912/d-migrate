package dev.dmigrate.server.core.resource

import dev.dmigrate.server.core.principal.TenantId

enum class ResourceKind(val pathSegment: String) {
    JOBS("jobs"),
    ARTIFACTS("artifacts"),
    SCHEMAS("schemas"),
    PROFILES("profiles"),
    DIFFS("diffs"),
    CONNECTIONS("connections"),
    UPLOAD_SESSIONS("upload-sessions"),
    ;

    companion object {
        private val bySegment: Map<String, ResourceKind> = entries.associateBy { it.pathSegment }
        fun fromPathSegment(segment: String): ResourceKind? = bySegment[segment]
    }
}

data class ServerResourceUri(
    val tenantId: TenantId,
    val kind: ResourceKind,
    val id: String,
) {
    fun render(): String = "$SCHEME://tenants/${tenantId.value}/${kind.pathSegment}/$id"

    companion object {
        const val SCHEME: String = "dmigrate"
        private const val PREFIX: String = "dmigrate://tenants/"
        private val SEGMENT_PATTERN: Regex = Regex("^[A-Za-z0-9_\\-]+$")

        fun parse(input: String): ResourceUriParseResult {
            if (!input.startsWith(PREFIX)) {
                return ResourceUriParseResult.Invalid("missing scheme prefix")
            }
            val rest = input.removePrefix(PREFIX)
            val parts = rest.split('/')
            if (parts.size != 3) {
                return ResourceUriParseResult.Invalid("expected tenants/{tenantId}/{kind}/{id}")
            }
            val (tenantSegment, kindSegment, idSegment) = parts
            if (!SEGMENT_PATTERN.matches(tenantSegment)) {
                return ResourceUriParseResult.Invalid("invalid tenantId segment")
            }
            if (!SEGMENT_PATTERN.matches(idSegment)) {
                return ResourceUriParseResult.Invalid("invalid id segment")
            }
            val kind = ResourceKind.fromPathSegment(kindSegment)
                ?: return ResourceUriParseResult.Invalid("unknown resource kind: $kindSegment")
            return ResourceUriParseResult.Valid(
                ServerResourceUri(TenantId(tenantSegment), kind, idSegment),
            )
        }
    }
}

sealed interface ResourceUriParseResult {
    data class Valid(val uri: ServerResourceUri) : ResourceUriParseResult
    data class Invalid(val reason: String) : ResourceUriParseResult
}
