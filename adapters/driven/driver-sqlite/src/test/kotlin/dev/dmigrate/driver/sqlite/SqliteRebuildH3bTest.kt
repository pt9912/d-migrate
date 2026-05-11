package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Phase H.3b: emission-mode switch between STANDALONE and EXECUTE.
 *
 * - STANDALONE emits the canonical 9-statement sequence bit-identical
 *   to pre-H.3b (PRAGMA foreign_keys = OFF / BEGIN / … / COMMIT /
 *   PRAGMA foreign_keys = ON).
 * - EXECUTE emits runner-hook marker comments instead of the
 *   trailing PRAGMA = ON, and prepends a save-state marker before
 *   the PRAGMA = OFF. The d-migrate runner parses the markers to
 *   read the prior FK-state and restore it after Commit/Rollback.
 *
 * Tests pin the emitted statement shape so the runner-vertrag
 * implementation in a follow-up slice has a stable contract.
 */
class SqliteRebuildH3bTest : FunSpec({

    val sql = SqliteDiffSqlBuilders()
    val table = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "age" to ColumnDefinition(NeutralType.SmallInt),
        ),
        primaryKey = listOf("id"),
    )
    val targetTable = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "age" to ColumnDefinition(NeutralType.Integer),
        ),
        primaryKey = listOf("id"),
    )

    fun renderForMode(mode: SqliteRebuildEmissionMode): List<String> {
        val plan = SqliteRebuildPlanner.planRebuild(
            table = "u",
            bucket = emptyList(),
            source = table,
            target = targetTable,
            bucketRisk = OperationRisk.SAFE,
            sql = sql,
        ).copy(emissionMode = mode)
        val ctx = SqliteDiffRenderContext(
            direction = SqliteRenderDirection.UP,
            sql = sql,
            options = dev.dmigrate.driver.DdlGenerationOptions(),
        )
        SqliteRebuildRenderer(sql).render(plan, ctx)
        val diff = dev.dmigrate.core.diff.migration.DiffResult(
            current = dev.dmigrate.core.diff.migration.DiffEndpoint(schemaName = "App"),
            desired = dev.dmigrate.core.diff.migration.DiffEndpoint(schemaName = "App"),
            schemaDiff = dev.dmigrate.core.diff.SchemaDiff(),
            operations = emptyList(),
        )
        return ctx.toResult(diff).statements.map { it.sql }
    }

    test("H.3b — STANDALONE mode emits 9 statements (bit-identical to pre-H.3b)") {
        val sqls = renderForMode(SqliteRebuildEmissionMode.STANDALONE)
        sqls.size shouldBe 9
        sqls[0] shouldBe "PRAGMA foreign_keys = OFF;"
        sqls[1] shouldBe "BEGIN IMMEDIATE;"
        sqls[6] shouldBe "PRAGMA foreign_key_check;"
        sqls[7] shouldBe "COMMIT;"
        sqls[8] shouldBe "PRAGMA foreign_keys = ON;"
        // No runner-hook markers in standalone output.
        sqls.any { it.contains("dmigrate:runner-hook") } shouldBe false
    }

    test("H.3b — EXECUTE mode emits save-marker before PRAGMA OFF and restore-marker instead of PRAGMA ON") {
        val sqls = renderForMode(SqliteRebuildEmissionMode.EXECUTE)
        // 10 statements: save-marker + 8 canonical (PRAGMA OFF → COMMIT) + restore-marker.
        sqls.size shouldBe 10
        sqls[0] shouldBe "-- dmigrate:runner-hook=save-fk-state-before-pragma-off"
        sqls[1] shouldBe "PRAGMA foreign_keys = OFF;"
        sqls[2] shouldBe "BEGIN IMMEDIATE;"
        sqls[7] shouldBe "PRAGMA foreign_key_check;"
        sqls[8] shouldBe "COMMIT;"
        sqls[9] shouldBe "-- dmigrate:runner-hook=restore-fk-state"
        // The pauschal `PRAGMA = ON` must NOT appear in execute output —
        // it would force ON regardless of prior state and defeat the
        // runner-vertrag.
        sqls.any { it == "PRAGMA foreign_keys = ON;" } shouldBe false
    }

    test("H.3b — default emission mode is STANDALONE (sicheres Default fuer Artefakte)") {
        val plan = SqliteRebuildPlanner.planRebuild(
            table = "u",
            bucket = emptyList(),
            source = table,
            target = targetTable,
            bucketRisk = OperationRisk.SAFE,
            sql = sql,
        )
        plan.emissionMode shouldBe SqliteRebuildEmissionMode.STANDALONE
    }

    test("H.3b — STANDALONE and EXECUTE share the same TABLES/INDEXES phase output") {
        val standalone = renderForMode(SqliteRebuildEmissionMode.STANDALONE)
        val execute = renderForMode(SqliteRebuildEmissionMode.EXECUTE)
        // Strip prefix-markers from execute: skip first marker, then
        // the rest of execute should overlap standalone's [0..7] with
        // a one-position shift (PRAGMA OFF / BEGIN / … / COMMIT).
        val executeAfterPrefixMarker = execute.drop(1)
        // First 8 statements (PRAGMA OFF, BEGIN, CREATE temp, INSERT,
        // DROP, RENAME, foreign_key_check, COMMIT) must match.
        executeAfterPrefixMarker.take(8) shouldBe standalone.take(8)
    }
})
