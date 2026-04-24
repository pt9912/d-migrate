package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.ProfilingException
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.profiling.service.ProfileDatabaseService
import java.nio.file.Path

/**
 * Immutable DTO for `d-migrate data profile`.
 */
data class DataProfileRequest(
    val source: String,
    val tables: List<String>? = null,
    val schema: String? = null,
    val topN: Int = 10,
    val format: String = "json",
    val output: Path? = null,
    val quiet: Boolean = false,
)

/**
 * Core logic for `d-migrate data profile`. All collaborators are
 * constructor-injected so every branch is unit-testable.
 *
 * Exit codes:
 * - 0 success
 * - 2 invalid request (bad topN, schema on unsupported dialect)
 * - 4 connection error
 * - 5 profiling execution error
 * - 7 config/URL/registry error
 */
class DataProfileRunner(
    private val connectionResolver: (String) -> String,
    private val dialectResolver: (String) -> DatabaseDialect,
    private val poolFactory: (String, DatabaseDialect) -> AutoCloseable,
    private val adapterLookup: (DatabaseDialect) -> ProfilingAdapterSet,
    private val databaseProduct: (AutoCloseable) -> String = { "unknown" },
    private val databaseVersion: (AutoCloseable) -> String? = { null },
    private val reportWriter: (DatabaseProfile, String, Path?) -> Unit,
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    fun execute(request: DataProfileRequest): Int {
        // ─── 1. Validate request ────────────────────────────────
        if (request.topN < 1 || request.topN > 1000) {
            stderr("[ERROR] topN must be between 1 and 1000, got: ${request.topN}")
            return 2
        }

        // ─── 2. Resolve connection URL ──────────────────────────
        val url = try {
            connectionResolver(request.source)
        } catch (e: Exception) {
            stderr("[ERROR] Failed to resolve source '${request.source}': ${e.message}")
            return 7
        }

        // ─── 3. Resolve dialect ─────────────────────────────────
        val dialect = try {
            dialectResolver(url)
        } catch (e: Exception) {
            stderr("[ERROR] Failed to parse connection URL: ${e.message}")
            return 7
        }

        // ─── 4. Validate schema flag ────────────────────────────
        if (request.schema != null && !DialectCapabilities.forDialect(dialect).supportsSchemaParameter) {
            stderr("[ERROR] --schema is not supported for ${dialect.name.lowercase()}")
            return 2
        }

        // ─── 5. Create connection pool ──────────────────────────
        val pool = try {
            poolFactory(url, dialect)
        } catch (e: Exception) {
            stderr("[ERROR] Connection failed: ${e.message}")
            return 4
        }

        // ─── 6. Lookup profiling adapters ───────────────────────
        val adapters = try {
            adapterLookup(dialect)
        } catch (e: Exception) {
            stderr("[ERROR] No profiling adapter for ${dialect.name.lowercase()}: ${e.message}")
            pool.close()
            return 7
        }

        // ─── 7. Run profiling ───────────────────────────────────
        return try {
            val service = ProfileDatabaseService(adapters,
                dev.dmigrate.profiling.service.ProfileTableService(adapters, topN = request.topN))
            @Suppress("UNCHECKED_CAST")
            val connPool = pool as dev.dmigrate.driver.connection.ConnectionPool

            val profile = service.profile(
                pool = connPool,
                databaseProduct = databaseProduct(pool),
                databaseVersion = databaseVersion(pool),
                schema = request.schema,
                tables = request.tables,
            )

            reportWriter(profile, request.format, request.output)
            if (!request.quiet) stderr("Profiling complete: ${profile.tables.size} table(s)")
            0
        } catch (e: ProfilingException) {
            stderr("[ERROR] Profiling failed: ${e.message}")
            5
        } catch (e: Exception) {
            stderr("[ERROR] Unexpected error: ${e.message}")
            5
        } finally {
            pool.close()
        }
    }
}
