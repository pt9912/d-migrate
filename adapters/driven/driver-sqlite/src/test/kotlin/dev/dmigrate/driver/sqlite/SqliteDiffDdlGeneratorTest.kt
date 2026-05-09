package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

class SqliteDiffDdlGeneratorTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planAndUp(diff: SchemaDiff, current: SchemaDefinition = emptySchema(), desired: SchemaDefinition = emptySchema()) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun planAndDown(diff: SchemaDiff, current: SchemaDefinition = emptySchema(), desired: SchemaDefinition = emptySchema()) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    test("dialect is SQLITE") {
        gen.dialect.name shouldBe "SQLITE"
    }

    test("CreateTable uses double-quote identifiers, full column list") {
        val t = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "name" to ColumnDefinition(NeutralType.Text()),
            ),
            primaryKey = listOf("id"),
        )
        val sql = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("users", t)))).statements.first().sql
        sql shouldContainStr "CREATE TABLE \"users\""
        sql shouldContainStr "PRIMARY KEY (\"id\")"
    }

    test("DropTable up; down yields ROLLBACK_NOT_POSSIBLE") {
        val r = planAndUp(SchemaDiff(tablesRemoved = listOf(NamedTable("legacy", TableDefinition()))))
        r.statements.single().sql shouldBe "DROP TABLE \"legacy\";"
        val rDown = planAndDown(SchemaDiff(tablesRemoved = listOf(NamedTable("legacy", TableDefinition()))))
        rDown.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("AddColumn / DropColumn round-trip uses ALTER TABLE … DROP COLUMN") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", columnsAdded = mapOf("nick" to ColumnDefinition(NeutralType.Text()))),
            ),
        )
        planAndUp(diff).statements.single().sql shouldContainStr "ADD COLUMN \"nick\""
        planAndDown(diff).statements.single().sql shouldBe "ALTER TABLE \"u\" DROP COLUMN \"nick\";"
    }

    test("DropColumn renders DROP COLUMN") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text()))),
            ),
        )
        planAndUp(diff).statements.single().sql shouldBe "ALTER TABLE \"u\" DROP COLUMN \"legacy\";"
    }

    test("AlterColumnType is deferred to D.4.b (MANUAL_ACTION_REQUIRED)") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.Integer, NeutralType.BigInteger),
                        ),
                    ),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.any { it.code == "SQLITE_REBUILD_REQUIRED" } shouldBe true
    }

    test("AlterColumnNullability and AlterColumnDefault are deferred to D.4.b") {
        val nullability = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(name = "x", required = ValueChange(true, false)),
                    ),
                ),
            ),
        )
        val rNull = planAndUp(nullability)
        rNull.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED

        val defaultChange = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "x",
                            default = ValueChange(null, dev.dmigrate.core.model.DefaultValue.StringLiteral("a")),
                        ),
                    ),
                ),
            ),
        )
        val rDef = planAndUp(defaultChange)
        rDef.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }

    test("AddPrimaryKey / DropPrimaryKey are deferred to D.4.b") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", primaryKey = ValueChange(emptyList(), listOf("id"))),
            ),
        )
        val r = planAndUp(diff)
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }

    test("AddConstraint / DropConstraint are deferred to D.4.b") {
        val c = ConstraintDefinition(name = "uq", type = ConstraintType.UNIQUE, columns = listOf("email"))
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "u", constraintsAdded = listOf(c))))
        val r = planAndUp(diff)
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }

    test("AddIndex / DropIndex round-trip without rebuild") {
        val idx = IndexDefinition(
            name = "idx_email",
            columns = listOf(IndexColumn("email")),
            type = IndexType.BTREE,
            unique = true,
        )
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "u", indicesAdded = listOf(idx))))
        planAndUp(diff).statements.single().sql shouldContainStr "CREATE UNIQUE INDEX \"idx_email\""
        planAndDown(diff).statements.single().sql shouldBe "DROP INDEX \"idx_email\";"
    }

    test("Anonymous index uses anon name fallback") {
        val idx = IndexDefinition(name = null, columns = listOf(IndexColumn("c1")), type = IndexType.BTREE)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "t", indicesAdded = listOf(idx))))
        planAndUp(diff).statements.single().sql shouldContainStr "\"t_c1_idx\""
    }

    test("Partial index emits WHERE clause") {
        val idx = IndexDefinition(
            name = "idx_active",
            columns = listOf(IndexColumn("status")),
            type = IndexType.BTREE,
            where = "status = 'active'",
        )
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "u", indicesAdded = listOf(idx))))
        planAndUp(diff).statements.single().sql shouldContainStr "WHERE status = 'active'"
    }

    test("CreateView and DropView round-trip") {
        val v = ViewDefinition(query = "SELECT 1")
        val rUp = planAndUp(SchemaDiff(viewsAdded = listOf(dev.dmigrate.core.diff.NamedView("v_x", v))))
        rUp.statements.single().sql shouldContainStr "CREATE VIEW \"v_x\""
        val rDown = planAndDown(SchemaDiff(viewsAdded = listOf(dev.dmigrate.core.diff.NamedView("v_x", v))))
        rDown.statements.single().sql shouldBe "DROP VIEW \"v_x\";"
    }

    test("ReplaceView emits two statements (DROP IF EXISTS + CREATE) tagged with the same op id") {
        val before = ViewDefinition(query = "SELECT 1")
        val after = ViewDefinition(query = "SELECT 2")
        val current = emptySchema().copy(views = mapOf("v" to before))
        val desired = emptySchema().copy(views = mapOf("v" to after))
        val diff = SchemaDiff(
            viewsChanged = listOf(
                dev.dmigrate.core.diff.ViewDiff(name = "v", query = ValueChange("SELECT 1", "SELECT 2")),
            ),
        )
        val rUp = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        rUp.statements.size shouldBe 2
        rUp.statements[0].sql shouldContainStr "DROP VIEW IF EXISTS"
        rUp.statements[1].sql shouldContainStr "SELECT 2"
        // Both statements share the same operation id (single ReplaceView).
        rUp.statements.flatMap { it.operationIds }.distinct().size shouldBe 1
    }

    test("Out-of-matrix CreateCustomType (SQLite has no CREATE TYPE) is DIALECT_UNSUPPORTED") {
        val enumT = dev.dmigrate.core.model.CustomTypeDefinition(
            kind = dev.dmigrate.core.model.CustomTypeKind.ENUM,
            values = listOf("a"),
        )
        val r = planAndUp(SchemaDiff(customTypesAdded = listOf(dev.dmigrate.core.diff.NamedCustomType("t", enumT))))
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("Out-of-matrix Sequence is DIALECT_UNSUPPORTED") {
        val seq = dev.dmigrate.core.model.SequenceDefinition(start = 1)
        val r = planAndUp(SchemaDiff(sequencesAdded = listOf(dev.dmigrate.core.diff.NamedSequence("s", seq))))
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("Empty diff yields empty result") {
        val r = planAndUp(SchemaDiff())
        r.statements.shouldBeEmpty()
        r.isBlocked shouldBe false
    }
})
