package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/**
 * 0.9.7 preserve-current-value Sub-Slice B (2026-05-21): live-PostgreSQL
 * integration coverage for [PostgresSequenceCurrentValueProbe]. Pins the
 * JDBC-mock unit-test against what PG actually returns so a future
 * driver upgrade can't drift the expectations.
 *
 * Three lifecycle states are probed:
 *
 * - Freshly-created sequence: `last_value = start` (the column carries
 *   the seed even before `nextval` has run), `is_called = false`.
 *   This is the canonical Sub-Slice D pre-state for `CreateSequence`
 *   with `preserveCurrentValue = true` against a sequence that already
 *   exists on the target.
 * - After `nextval`: `last_value = 1` (the returned value),
 *   `is_called = true`. The renderer's `setval(seq, last_value, true)`
 *   reproduces this exactly.
 * - Non-existent sequence: SQLSTATE `42P01` → `NotFound`. Mapped by
 *   the planner to `SEQUENCE_PRESERVE_NOT_FOUND` (info for
 *   `CreateSequence`, blocker for `AlterSequence` / `RenameSequence`).
 */
class PostgresSequenceCurrentValueProbeIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("preserve_it")
        .withUsername("test")
        .withPassword("test")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun conn() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    fun exec(sql: String) {
        conn().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    fun query(sql: String, mapper: (java.sql.ResultSet) -> Unit) {
        conn().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    if (rs.next()) mapper(rs)
                }
            }
        }
    }

    fun pgRef(name: String, schema: String? = null) =
        SequenceObjectRef(name, schema, RenameProjectionDialect.POSTGRESQL)

    test("freshly-created sequence: last_value = start, is_called = false") {
        exec("DROP SEQUENCE IF EXISTS preserve_seq")
        exec("CREATE SEQUENCE preserve_seq START WITH 100 INCREMENT BY 5")
        conn().use { c ->
            val result = PostgresSequenceCurrentValueProbe.probe(c, pgRef("preserve_seq"))
            result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
            // PG seeds `last_value` to `start` until the first `nextval`.
            result.value shouldBe 100L
            result.isCalled shouldBe false
            result.matchedRows shouldBe 1
        }
    }

    test("after nextval: last_value = returned value, is_called = true") {
        exec("DROP SEQUENCE IF EXISTS preserve_seq")
        exec("CREATE SEQUENCE preserve_seq START WITH 1 INCREMENT BY 1")
        var returnedFromNextval = -1L
        query("SELECT nextval('preserve_seq')") { rs ->
            returnedFromNextval = rs.getLong(1)
        }
        returnedFromNextval shouldBe 1L

        conn().use { c ->
            val result = PostgresSequenceCurrentValueProbe.probe(c, pgRef("preserve_seq"))
            result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
            result.value shouldBe 1L
            result.isCalled shouldBe true
        }
    }

    test("missing sequence: SQLSTATE 42P01 → NotFound") {
        exec("DROP SEQUENCE IF EXISTS not_there")
        conn().use { c ->
            val result = PostgresSequenceCurrentValueProbe.probe(c, pgRef("not_there"))
            result shouldBe SequenceCurrentValueProbeResult.NotFound
        }
    }

    test("schema-qualified sequence in non-default namespace") {
        exec("CREATE SCHEMA IF NOT EXISTS audit")
        exec("DROP SEQUENCE IF EXISTS audit.audit_seq")
        exec("CREATE SEQUENCE audit.audit_seq START WITH 42")
        conn().use { c ->
            val result = PostgresSequenceCurrentValueProbe.probe(c, pgRef("audit_seq", schema = "audit"))
            result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
            result.value shouldBe 42L
            result.isCalled shouldBe false
        }
    }

    test("unqualified probe relies on search_path resolution") {
        // Search-path-only resolution: a sequence in the `audit`
        // schema is NOT found when the probe uses the bare name and
        // `audit` is not on the connection's search_path. PG raises
        // 42P01 → NotFound, identical to "sequence missing entirely".
        exec("CREATE SCHEMA IF NOT EXISTS audit")
        exec("DROP SEQUENCE IF EXISTS audit.search_path_seq")
        exec("CREATE SEQUENCE audit.search_path_seq START WITH 1")
        conn().use { c ->
            val result = PostgresSequenceCurrentValueProbe.probe(c, pgRef("search_path_seq"))
            result shouldBe SequenceCurrentValueProbeResult.NotFound
        }
    }

    test("insufficient privilege: SQLSTATE 42501 → Failed(PROBE_PERMISSION_DENIED)") {
        // Create a role with no privileges on `preserve_seq` and
        // probe through a connection authenticated as that role.
        exec("DROP SEQUENCE IF EXISTS preserve_seq")
        exec("CREATE SEQUENCE preserve_seq")
        exec("DROP ROLE IF EXISTS preserve_test_unprivileged")
        exec("CREATE ROLE preserve_test_unprivileged LOGIN PASSWORD 'pw'")
        exec("REVOKE ALL ON SEQUENCE preserve_seq FROM preserve_test_unprivileged")

        val limitedConn = DriverManager.getConnection(
            container.jdbcUrl, "preserve_test_unprivileged", "pw",
        )
        limitedConn.use { c ->
            val result = PostgresSequenceCurrentValueProbe.probe(c, pgRef("preserve_seq"))
            result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
            result.code shouldBe PostgresSequenceCurrentValueProbe.CODE_PERMISSION_DENIED
            result.message shouldContain "preserve_seq"
        }
    }
})
