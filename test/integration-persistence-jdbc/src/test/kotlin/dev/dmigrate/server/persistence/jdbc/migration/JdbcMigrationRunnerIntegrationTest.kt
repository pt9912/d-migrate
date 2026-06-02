package dev.dmigrate.server.persistence.jdbc.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.testcontainers.postgresql.PostgreSQLContainer
import javax.sql.DataSource


/**
 * Integration test for the LF-012 / LN-011 / LN-017 / LN-027 Flyway initial migration.
 *
 * Verifies:
 * - V1__server_state_initial.sql applies cleanly against a fresh
 *   PostgreSQL 16 container
 * - All 5 tables (idempotency_reservations, init_resume_reservations,
 *   jobs, quota_reservation_owners, quota_counters) exist with the
 *   expected primary-key columns and supporting indexes
 * - The migration is idempotent: re-running migrate() against an
 *   already-migrated DB is a no-op (Plan LF-012 / LN-011 / LN-017 / LN-027 Akzeptanz)
 * - validate() passes without drift
 *
 * Tagged `integration` so the default `./gradlew test` excludes it.
 * Activated via `./gradlew test -PintegrationTests`.
 */
class JdbcMigrationRunnerIntegrationTest : FunSpec({


    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_state")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    var dataSource: HikariDataSource? = null

    beforeSpec {
        container.start()
        val cfg = HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = 2
            poolName = "server-state-migrate-test"
        }
        dataSource = HikariDataSource(cfg)
    }

    afterSpec {
        dataSource?.close()
        container.stop()
    }

    test("first migrate() applies V1 and reports success=true with 1 migration") {
        val ds = dataSource!!
        val runner = JdbcMigrationRunner(ds)

        val result = runner.migrate()

        result.success shouldBe true
        result.migrationsExecuted shouldBe 1
        result.migrations.first().version shouldBe "1"
    }

    test("all 5 server-state tables exist after V1") {
        val ds = dataSource!!
        val tables = listTables(ds, schema = "public")
        tables shouldContainAll listOf(
            "idempotency_reservations",
            "init_resume_reservations",
            "jobs",
            "quota_reservation_owners",
            "quota_counters",
        )
    }

    test("primary keys and indexes are wired for LF-012 / LN-011 / LN-017 / LN-027") {
        val ds = dataSource!!

        // Composite PKs.
        primaryKeyColumns(ds, "idempotency_reservations") shouldBe
            listOf("tenant_id", "caller_id", "tool_name", "idempotency_key")
        primaryKeyColumns(ds, "init_resume_reservations") shouldBe
            listOf("tenant_id", "caller_id", "tool_name", "client_request_id")
        primaryKeyColumns(ds, "jobs") shouldBe listOf("tenant_id", "job_id")
        primaryKeyColumns(ds, "quota_reservation_owners") shouldBe listOf("owner_id")
        primaryKeyColumns(ds, "quota_counters") shouldBe listOf("quota_key")

        // Supporting indexes.
        val indexes = listIndexes(ds, schema = "public")
        indexes shouldContainAll listOf(
            "idempotency_expiry",
            "init_resume_expiry",
            "jobs_expiry",
            "jobs_status",
            "quota_owners_expiry",
        )
    }

    test("partial index quota_owners_expiry filters on state = PENDING") {
        val ds = dataSource!!
        val partial = readPartialIndexPredicate(ds, "quota_owners_expiry")
        // Postgres pretty-prints the predicate; assert it constrains state to PENDING.
        (partial?.contains("state") ?: false) shouldBe true
        (partial?.contains("PENDING") ?: false) shouldBe true
    }

    test("flyway history table uses the dedicated server-state name") {
        val ds = dataSource!!
        val tables = listTables(ds, schema = "public")
        tables shouldContainAll listOf("flyway_server_state_history")
    }

    test("running migrate() again is idempotent — zero new migrations applied") {
        val ds = dataSource!!
        val runner = JdbcMigrationRunner(ds)

        val rerun = runner.migrate()

        rerun.success shouldBe true
        rerun.migrationsExecuted shouldBe 0
    }

    test("validate() reports no drift after migrate") {
        val ds = dataSource!!
        // Throws FlywayValidateException on drift; success is reaching here.
        JdbcMigrationRunner(ds).validate()
    }
})

private fun listTables(ds: DataSource, schema: String): List<String> =
    ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT table_name
              FROM information_schema.tables
             WHERE table_schema = ?
               AND table_type = 'BASE TABLE'
             ORDER BY table_name
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

private fun listIndexes(ds: DataSource, schema: String): List<String> =
    ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT indexname FROM pg_indexes WHERE schemaname = ? ORDER BY indexname
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, schema)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

private fun primaryKeyColumns(ds: DataSource, table: String): List<String> =
    ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT a.attname
              FROM pg_index i
              JOIN pg_attribute a
                ON a.attrelid = i.indrelid
               AND a.attnum = ANY(i.indkey)
             WHERE i.indrelid = ?::regclass
               AND i.indisprimary
             ORDER BY array_position(i.indkey, a.attnum)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

private fun readPartialIndexPredicate(ds: DataSource, indexName: String): String? =
    ds.connection.use { conn ->
        conn.prepareStatement(
            """
            SELECT pg_get_expr(i.indpred, i.indrelid)
              FROM pg_index i
              JOIN pg_class c ON c.oid = i.indexrelid
             WHERE c.relname = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, indexName)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    }
