package dev.dmigrate.mcp.registry

import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.PoolSettings
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ConnectionReferenceStore
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.ResolvedConnection
import org.slf4j.LoggerFactory

/**
 * Backing handler for the `connections/list` protocol slot
 * (ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md Slice B).
 * Not a [ToolHandler] — `connections/list` is a protocol method like
 * `resources/list`, dispatched directly in `McpServiceImpl`, not
 * through the `tools/call` envelope.
 *
 * AE-B7: an admin can address any tenant in
 * [PrincipalContext.allowedTenantIds], not just their own — the whole
 * point of requiring `dmigrate:admin` for this method. AE-B9/AE-B10:
 * the live check uses an injectable [poolFactory] with a small,
 * dedicated [PING_POOL_SETTINGS] (not the caller's/target's normal
 * pool sizing) so a reachability probe never behaves like a real
 * workload connection. AE-B3: only a coarse status category ever
 * leaves this handler — no exception message, no host/port.
 */
internal class ConnectionsListHandler(
    private val connectionStore: ConnectionReferenceStore,
    private val connectionSecretResolver: ConnectionSecretResolver,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool = HikariConnectionPoolFactory::create,
) {

    /**
     * @param tenant already resolved/validated by the caller (see
     *   [ListToolHelpers.resolveTenant]) — this handler does not
     *   re-validate `allowedTenantIds`, it only applies the addressed
     *   tenant (AE-B7).
     */
    fun list(
        principal: PrincipalContext,
        tenant: TenantId,
        pageSize: Int,
        resumeToken: String?,
        checkLive: Boolean,
    ): ConnectionsListPage {
        // AE-B7: only the addressed-tenant view changes; principalId/isAdmin
        // (the allowedPrincipalIds check inside the store) stay the caller's own.
        val scopedPrincipal = principal.copy(effectiveTenantId = tenant)
        val page = connectionStore.list(scopedPrincipal, PageRequest(pageSize = pageSize, pageToken = resumeToken))
        val summaries = page.items.map { ref -> toSummary(ref, scopedPrincipal, checkLive) }
        return ConnectionsListPage(connections = summaries, nextResumeToken = page.nextPageToken)
    }

    private fun toSummary(ref: ConnectionReference, principal: PrincipalContext, checkLive: Boolean) =
        ConnectionSummary(
            connectionId = ref.connectionId,
            displayName = ref.displayName,
            dialectId = ref.dialectId,
            sensitivity = ref.sensitivity.name,
            status = if (checkLive) checkLiveStatus(ref, principal) else null,
        )

    private fun checkLiveStatus(ref: ConnectionReference, principal: PrincipalContext): String {
        val resolved = connectionSecretResolver.resolve(ref, principal)
        val url = when (resolved) {
            is ResolvedConnection.Success -> resolved.url
            is ResolvedConnection.Failure -> return STATUS_CREDENTIAL_ERROR
        }
        return try {
            val config = ConnectionUrlParser.parse(url).copy(pool = PING_POOL_SETTINGS)
            poolFactory(config).use { pool -> pool.borrow().use { } }
            STATUS_REACHABLE
        } catch (e: Exception) {
            // AE-B3: the exception message may contain host/port/network detail —
            // it stays server-side at DEBUG, never on the wire.
            LOG.debug("connections/list live-check failed for '{}': {}", ref.connectionId, e.message)
            STATUS_UNREACHABLE
        }
    }

    companion object {
        const val STATUS_REACHABLE = "REACHABLE"
        const val STATUS_UNREACHABLE = "UNREACHABLE"
        const val STATUS_CREDENTIAL_ERROR = "CREDENTIAL_ERROR"

        /** AE-B9: a reachability probe is not a workload connection. */
        private val PING_POOL_SETTINGS = PoolSettings(
            maximumPoolSize = 1,
            minimumIdle = 0,
            connectionTimeoutMs = 2000,
        )

        private val LOG = LoggerFactory.getLogger(ConnectionsListHandler::class.java)
    }
}

internal data class ConnectionsListPage(
    val connections: List<ConnectionSummary>,
    val nextResumeToken: String?,
)

internal data class ConnectionSummary(
    val connectionId: String,
    val displayName: String,
    val dialectId: String,
    val sensitivity: String,
    val status: String?,
)
