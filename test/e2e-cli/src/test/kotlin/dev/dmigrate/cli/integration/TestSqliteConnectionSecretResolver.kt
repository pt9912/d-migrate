package dev.dmigrate.cli.integration

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.ResolvedConnection
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Test-only [ConnectionSecretResolver] that maps a
 * [ConnectionReference.connectionId] to a SQLite JDBC URL
 * (`jdbc:sqlite:<file-path>`). Unknown references fail closed
 * with [ResolvedConnection.REASON_NO_CREDENTIAL_REF] so the
 * Operational-Harness validation/blocker scenarios surface a
 * typed error instead of a NullPointerException.
 *
 * Plan-Doc: `docs/planning/done-archive/quality-coverage-expansion-plan.md`
 * §5.3 (C-MCP `ConnectionSecretResolver` test seam).
 *
 * The resolver is intentionally NOT scope-aware — the
 * Operational-Harness fixture seeds the [ConnectionReference] with
 * `allowedPrincipalIds = null` and `allowedScopes = null` so the
 * authorisation check in [McpCoreJobWorkerFactory.materializer] passes
 * for any principal. Scope-restriction is the concern of separate
 * policy tests (see `ConfiguredPolicyServiceTest`).
 */
internal class TestSqliteConnectionSecretResolver(
    private val mappings: Map<String, Path>,
) : ConnectionSecretResolver {

    override fun resolve(
        reference: ConnectionReference,
        principal: PrincipalContext,
    ): ResolvedConnection {
        val path = mappings[reference.connectionId]
            ?: return ResolvedConnection.Failure(
                reason = ResolvedConnection.REASON_NO_CREDENTIAL_REF,
                detail = "TestSqliteConnectionSecretResolver has no mapping for connectionId='${reference.connectionId}'",
            )
        // ConnectionUrlParser.parse drops the `jdbc:` prefix
        // implicitly and special-cases `sqlite:` for the file-path
        // variant (see parseSqlite). Returning the URL with `sqlite:`
        // (not `jdbc:sqlite:`) routes the parser into the SQLite
        // branch and avoids the misleading "Unknown dialect 'jdbc'"
        // diagnostic.
        return ResolvedConnection.Success(url = "sqlite:${path.absolutePathString()}")
    }

    companion object {
        /** Fail-closed default — any reference is rejected. */
        val FAIL_CLOSED: ConnectionSecretResolver = TestSqliteConnectionSecretResolver(emptyMap())
    }
}
