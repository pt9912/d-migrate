package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SqliteRebuildRendererTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    fun schemaWith(tables: Map<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables)

    test("AlterColumnType emits the canonical 10-step rebuild sequence with CAST") {
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "age" to ColumnDefinition(NeutralType.SmallInt),
            ),
            primaryKey = listOf("id"),
        )
        val after = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "age" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("u" to before))
        val desired = schemaWith(mapOf("u" to after))
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

        // 9 statements when there are no extra indices in the desired table.
        r.statements.size shouldBe 9
        val sqls = r.statements.map { it.sql }
        sqls[0] shouldBe "PRAGMA foreign_keys = OFF;"
        sqls[1] shouldBe "BEGIN IMMEDIATE;"
        sqls[2] shouldContain "CREATE TABLE \"u__dmg_rebuild_"
        sqls[3] shouldContain "INSERT INTO \"u__dmg_rebuild_"
        sqls[3] shouldContain "CAST(\"age\" AS INTEGER)"
        sqls[4] shouldContain "DROP TABLE \"u\""
        sqls[5] shouldContain "RENAME TO \"u\""
        sqls[6] shouldBe "PRAGMA foreign_key_check;"
        sqls[7] shouldBe "COMMIT;"
        sqls[8] shouldBe "PRAGMA foreign_keys = ON;"
    }

    test("rebuild emits indexes from the desired table after RENAME") {
        val before = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val after = before.copy(
            indices = listOf(
                IndexDefinition(name = "idx_u_id", columns = listOf(IndexColumn("id")), type = IndexType.BTREE),
            ),
        )
        // Trigger rebuild via PK change (force reshape).
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    primaryKey = ValueChange(listOf("id"), listOf("id")),
                    indicesAdded = listOf(
                        IndexDefinition(name = "idx_u_id", columns = listOf(IndexColumn("id")), type = IndexType.BTREE),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("u" to before))
        val desired = schemaWith(mapOf("u" to after))
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        // The AddIndex op stays simple (not absorbed) — so it shows up after the rebuild.
        r.statements.any { it.sql.startsWith("CREATE INDEX \"idx_u_id\"") } shouldBe true
    }

    test("new NOT NULL column without default blocks the rebuild") {
        val before = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val after = before.copy(
            columns = before.columns + ("status" to ColumnDefinition(NeutralType.Text(), required = true)),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsAdded = mapOf("status" to ColumnDefinition(NeutralType.Text(), required = true)),
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "id",
                            type = ValueChange(NeutralType.Integer, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("u" to before))
        val desired = schemaWith(mapOf("u" to after))
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.any { it.code == "NOT_NULL_BACKFILL_REQUIRED" } shouldBe true
    }

    test("new NOT NULL column WITH default fills via DEFAULT literal") {
        val before = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val after = before.copy(
            columns = before.columns + (
                "status" to ColumnDefinition(
                    NeutralType.Text(),
                    required = true,
                    default = DefaultValue.StringLiteral("active"),
                )
                ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsAdded = mapOf(
                        "status" to ColumnDefinition(
                            NeutralType.Text(),
                            required = true,
                            default = DefaultValue.StringLiteral("active"),
                        ),
                    ),
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "id",
                            type = ValueChange(NeutralType.Integer, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("u" to before))
        val desired = schemaWith(mapOf("u" to after))
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val insertSelect = r.statements.single { it.sql.startsWith("INSERT INTO") }.sql
        insertSelect shouldContain "'active'"
    }

    test("new nullable column without default fills via NULL") {
        val before = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val after = before.copy(
            columns = before.columns + ("nick" to ColumnDefinition(NeutralType.Text())),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsAdded = mapOf("nick" to ColumnDefinition(NeutralType.Text())),
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "id",
                            type = ValueChange(NeutralType.Integer, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("u" to before))
        val desired = schemaWith(mapOf("u" to after))
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        val insertSelect = r.statements.single { it.sql.startsWith("INSERT INTO") }.sql
        insertSelect shouldContain "NULL"
    }

    test("rebuild bucket statements all share the same union of operation IDs") {
        val before = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
        )
        val after = before.copy(
            columns = mapOf("id" to ColumnDefinition(NeutralType.BigInteger)),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "id",
                            type = ValueChange(NeutralType.Integer, NeutralType.BigInteger),
                        ),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("u" to before))
        val desired = schemaWith(mapOf("u" to after))
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        val firstIds = r.statements.first().operationIds
        r.statements.all { it.operationIds == firstIds } shouldBe true
        firstIds.size shouldBe 1
    }

    test("Down direction on a rebuild bucket yields ROLLBACK_NOT_POSSIBLE") {
        val before = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
        )
        val after = before.copy(columns = mapOf("id" to ColumnDefinition(NeutralType.BigInteger)))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "id",
                            type = ValueChange(NeutralType.Integer, NeutralType.BigInteger),
                        ),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("u" to before))
        val desired = schemaWith(mapOf("u" to after))
        val r = gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("Constraint reshape (AddConstraint UNIQUE) triggers rebuild") {
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "email" to ColumnDefinition(NeutralType.Text()),
            ),
            primaryKey = listOf("id"),
        )
        val after = before.copy(
            constraints = listOf(
                dev.dmigrate.core.model.ConstraintDefinition(
                    name = "uq_email",
                    type = dev.dmigrate.core.model.ConstraintType.UNIQUE,
                    columns = listOf("email"),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    constraintsAdded = listOf(
                        dev.dmigrate.core.model.ConstraintDefinition(
                            name = "uq_email",
                            type = dev.dmigrate.core.model.ConstraintType.UNIQUE,
                            columns = listOf("email"),
                        ),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("u" to before))
        val desired = schemaWith(mapOf("u" to after))
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        r.statements.first().sql shouldBe "PRAGMA foreign_keys = OFF;"
        r.statements.last().sql shouldBe "PRAGMA foreign_keys = ON;"
    }
})
