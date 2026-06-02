package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import java.sql.DriverManager

/**
 * 0.9.7 preserve-current-value Sub-Slice C (2026-05-21): live-MySQL
 * integration coverage for [MysqlSequenceCurrentValueProbe]. Pins
 * the JDBC-mock unit-test against what MySQL actually returns when
 * the canonical helper-table emulation (`dmg_sequences` table +
 * `dmg_nextval`/`dmg_setval` routines) is bootstrapped via the
 * production DDL generator.
 *
 * The container is provisioned with the canonical schema, so the
 * probe operates against the same bytes the Sub-Slice A renderer
 * would emit. `MysqlSequenceCanonicityProbeIntegrationTest` follows
 * the same fixture pattern from the drift-check workstream.
 */
class MysqlSequenceCurrentValueProbeIntegrationTest : FunSpec({

    val container = MySQLContainer("mysql:8")
        .withDatabaseName("preserve_it")
        .withUsername("test")
        .withPassword("test")
        .withCommand("--log-bin-trust-function-creators=1")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun conn() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    fun exec(sql: String) {
        conn().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    fun queryLong(sql: String): Long? {
        var result: Long? = null
        conn().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    if (rs.next()) result = rs.getLong(1)
                }
            }
        }
        return result
    }

    val canonicalSchema = SchemaDefinition(
        name = "PreserveIT", version = "1",
        sequences = mapOf("preserve_seq" to SequenceDefinition(start = 100L, increment = 1L)),
    )

    fun bootstrapCanonical() {
        // Tear down anything left behind from a previous case.
        exec("DROP FUNCTION IF EXISTS `dmg_nextval`")
        exec("DROP FUNCTION IF EXISTS `dmg_setval`")
        exec("DROP TABLE IF EXISTS `dmg_sequences`")
        // Drive the production DDL generator so the helper-table
        // installed here is byte-equivalent to what the renderer
        // would emit during a real migration.
        val result = MysqlDdlGenerator().generate(
            canonicalSchema,
            DdlGenerationOptions(dialectContext = DdlDialectContext.MySql(namedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE)),
        )
        for (block in splitMysqlStatements(result.render())) {
            if (block.isNotBlank()) {
                conn().use { c -> c.createStatement().use { it.execute(block) } }
            }
        }
    }

    fun mysqlRef(name: String) =
        SequenceObjectRef(name, null, RenameProjectionDialect.MYSQL)

    test("canonical helper-table + seeded row → Read with start as next_value") {
        bootstrapCanonical()
        conn().use { c ->
            val result = MysqlSequenceCurrentValueProbe.probe(c, mysqlRef("preserve_seq"))
            result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
            // The emulation seeds next_value = start at bootstrap.
            result.value shouldBe 100L
            result.matchedRows shouldBe 1
            // MySQL helper-table has no is_called analogue.
            result.isCalled shouldBe null
            result.managedBy shouldBe "d-migrate"
            result.formatVersion shouldBe 1
        }
    }

    test("after dmg_nextval call: next_value advances by increment") {
        bootstrapCanonical()
        val returned = queryLong("SELECT `dmg_nextval`('preserve_seq')")
        // dmg_nextval returns the pre-increment value and bumps
        // next_value by `increment_by` for the next call.
        returned shouldBe 100L
        conn().use { c ->
            val result = MysqlSequenceCurrentValueProbe.probe(c, mysqlRef("preserve_seq"))
            result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
            result.value shouldBe 101L
        }
    }

    test("dmg_sequences table missing → NotFound (helper-table not bootstrapped)") {
        exec("DROP FUNCTION IF EXISTS `dmg_nextval`")
        exec("DROP FUNCTION IF EXISTS `dmg_setval`")
        exec("DROP TABLE IF EXISTS `dmg_sequences`")
        conn().use { c ->
            val result = MysqlSequenceCurrentValueProbe.probe(c, mysqlRef("preserve_seq"))
            result shouldBe SequenceCurrentValueProbeResult.NotFound
        }
    }

    test("row missing for the queried sequence → NotFound (table exists, name not present)") {
        bootstrapCanonical()
        exec("DELETE FROM `dmg_sequences` WHERE `name` = 'preserve_seq'")
        conn().use { c ->
            val result = MysqlSequenceCurrentValueProbe.probe(c, mysqlRef("preserve_seq"))
            result shouldBe SequenceCurrentValueProbeResult.NotFound
        }
    }

    test("operator-inserted row (managed_by != 'd-migrate') → Failed(PROBE_UNMANAGED_ROW)") {
        bootstrapCanonical()
        exec("DELETE FROM `dmg_sequences` WHERE `name` = 'preserve_seq'")
        exec(
            """
            INSERT INTO `dmg_sequences` (
                `managed_by`, `format_version`, `name`, `next_value`,
                `increment_by`, `min_value`, `max_value`, `cycle_enabled`, `cache_size`
            ) VALUES ('legacy_tool', 'mysql-sequence-v1', 'preserve_seq', 50, 1, NULL, NULL, 0, NULL)
            """.trimIndent(),
        )
        conn().use { c ->
            val result = MysqlSequenceCurrentValueProbe.probe(c, mysqlRef("preserve_seq"))
            result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
            result.code shouldBe MysqlSequenceCurrentValueProbe.CODE_UNMANAGED_ROW
            result.message shouldContain "legacy_tool"
        }
    }

    test("unknown format_version → Failed(PROBE_UNKNOWN_FORMAT_VERSION)") {
        bootstrapCanonical()
        exec("UPDATE `dmg_sequences` SET `format_version` = 'mysql-sequence-v99' WHERE `name` = 'preserve_seq'")
        conn().use { c ->
            val result = MysqlSequenceCurrentValueProbe.probe(c, mysqlRef("preserve_seq"))
            result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
            result.code shouldBe MysqlSequenceCurrentValueProbe.CODE_UNKNOWN_FORMAT_VERSION
            result.message shouldContain "mysql-sequence-v99"
        }
    }

    // PROBE_PERMISSION_DENIED (MySQL error 1142) is integration-test-
    // covered ONLY at the unit-test level (MysqlSequenceCurrentValueProbeTest):
    // the testcontainers MySQL test user doesn't have CREATE USER, so
    // setting up an unprivileged role to provoke the live 1142
    // response is out of reach without surfacing root credentials.
    // The unit test pins the SQLException-errorCode → outcome
    // mapping; surfacing the same routing through a live MySQL would
    // be nice-to-have but not load-bearing — driver-side behaviour
    // for 1142 is stable across MySQL 8.x.

    test("multiple sequences in helper table: probe finds the right row by name") {
        bootstrapCanonical()
        // Add a second managed sequence row with a different
        // next_value so the probe can't accidentally pick it.
        exec(
            """
            INSERT INTO `dmg_sequences` (
                `managed_by`, `format_version`, `name`, `next_value`,
                `increment_by`, `min_value`, `max_value`, `cycle_enabled`, `cache_size`
            ) VALUES ('d-migrate', 'mysql-sequence-v1', 'other_seq', 9999, 1, NULL, NULL, 0, NULL)
            """.trimIndent(),
        )
        conn().use { c ->
            val preserve = MysqlSequenceCurrentValueProbe.probe(c, mysqlRef("preserve_seq"))
            preserve.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
            preserve.value shouldBe 100L

            val other = MysqlSequenceCurrentValueProbe.probe(c, mysqlRef("other_seq"))
            other.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
            other.value shouldBe 9999L
        }
    }
})
