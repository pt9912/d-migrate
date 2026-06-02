package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.TransactionBehavior
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

class MysqlDiffDdlGeneratorTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planAndUp(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        options: DdlGenerationOptions = DdlGenerationOptions(),
    ) = gen.generateUp(planner.plan(current, desired, diff), options)

    fun planAndDown(diff: SchemaDiff, current: SchemaDefinition = emptySchema(), desired: SchemaDefinition = emptySchema()) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    test("dialect is MYSQL") {
        gen.dialect.name shouldBe "MYSQL"
    }

    test("CreateTable uses backtick quoting and PRIMARY KEY clause") {
        val t = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val sql = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("users", t)))).statements.first().sql
        sql shouldContainStr "CREATE TABLE `users`"
        sql shouldContainStr "PRIMARY KEY (`id`)"
    }

    test("DropTable in down direction yields ROLLBACK_NOT_POSSIBLE") {
        val r = planAndDown(SchemaDiff(tablesRemoved = listOf(NamedTable("legacy", TableDefinition()))))
        r.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("AddColumn / DropColumn round-trip") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", columnsAdded = mapOf("nick" to ColumnDefinition(NeutralType.Text()))),
            ),
        )
        planAndUp(diff).statements.single().sql shouldContainStr "ADD COLUMN `nick`"
        planAndDown(diff).statements.single().sql shouldBe "ALTER TABLE `users` DROP COLUMN `nick`;"
    }

    test("§C.2: MySQL geometry AddColumn uses native spatial type") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "places", columnsAdded = mapOf("shape" to ColumnDefinition(NeutralType.Geometry()))),
            ),
        )
        val r = planAndUp(diff, options = DdlGenerationOptions(spatialProfile = SpatialProfile.NATIVE))

        r.isBlocked shouldBe false
        r.statements.single().sql shouldContainStr "ADD COLUMN `shape` GEOMETRY"
        r.spatialProfile shouldBe "NATIVE"
    }

    test("§C.2: MySQL index on geometry column is blocked") {
        val before = TableDefinition(
            columns = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
        )
        val index = IndexDefinition(name = "idx_places_shape", columns = listOf(IndexColumn("shape")))
        val after = before.copy(indices = listOf(index))
        val current = emptySchema().copy(tables = mapOf("places" to before))
        val desired = emptySchema().copy(tables = mapOf("places" to after))
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "places", indicesAdded = listOf(index))))
        val r = planAndUp(diff, current, desired)

        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.single { it.code == "SPATIAL_INDEX_UNSUPPORTED" }
            .message shouldContainStr "SPATIAL INDEX"
    }

    test("AlterColumnType safe cast: MODIFY COLUMN clause; unsafe cast blocks") {
        val safe = SchemaDiff(
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
        planAndUp(safe).statements.single().sql shouldContainStr "MODIFY COLUMN `age` INT"

        val unsafe = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.Integer, NeutralType.Text()),
                        ),
                    ),
                ),
            ),
        )
        val r = planAndUp(unsafe)
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION } shouldBe true
    }

    test("§11.1 AlterColumnNullability is a documented blocker because MySQL needs the full type") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(name = "x", required = ValueChange(true, false)),
                    ),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.isBlocked shouldBe true
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.operationsSkipped.size shouldBe 1
        r.diagnostics.single().code shouldBe "MYSQL_NULLABILITY_REQUIRES_COLUMN_TYPE"
        r.diagnostics.single().message shouldContainStr "requires the column type"
    }

    test("AlterColumnDefault uses ALTER … SET / DROP DEFAULT") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "status",
                            default = ValueChange(null, DefaultValue.StringLiteral("active")),
                        ),
                    ),
                ),
            ),
        )
        planAndUp(diff).statements.single().sql shouldContainStr "ALTER `status` SET DEFAULT"
        planAndDown(diff).statements.single().sql shouldContainStr "ALTER `status` DROP DEFAULT"
    }

    test("AddPrimaryKey / DropPrimaryKey use DROP PRIMARY KEY syntax") {
        val add = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", primaryKey = ValueChange(emptyList(), listOf("id"))),
            ),
        )
        planAndUp(add).statements.single().sql shouldContainStr "ADD PRIMARY KEY"
        planAndDown(add).statements.single().sql shouldBe "ALTER TABLE `u` DROP PRIMARY KEY;"

        val drop = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", primaryKey = ValueChange(listOf("id"), emptyList())),
            ),
        )
        planAndUp(drop).statements.single().sql shouldBe "ALTER TABLE `u` DROP PRIMARY KEY;"
        planAndDown(drop).statements.single().sql shouldContainStr "ADD PRIMARY KEY"
    }

    test("AddConstraint FK: ADD … FOREIGN KEY; down DROP FOREIGN KEY") {
        val c = ConstraintDefinition(
            name = "fk_o_u",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "orders", constraintsAdded = listOf(c))))
        val up = planAndUp(diff).statements.single().sql
        up shouldContainStr "FOREIGN KEY (`user_id`)"
        up shouldContainStr "REFERENCES `users`(`id`)"
        planAndDown(diff).statements.single().sql shouldBe "ALTER TABLE `orders` DROP FOREIGN KEY `fk_o_u`;"
    }

    test("AddConstraint UNIQUE: ADD UNIQUE; down DROP INDEX") {
        val c = ConstraintDefinition(name = "uq_email", type = ConstraintType.UNIQUE, columns = listOf("email"))
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "u", constraintsAdded = listOf(c))))
        planAndUp(diff).statements.single().sql shouldContainStr "ADD CONSTRAINT `uq_email` UNIQUE"
        planAndDown(diff).statements.single().sql shouldBe "ALTER TABLE `u` DROP INDEX `uq_email`;"
    }

    test("DropConstraint: FK drops via DROP FOREIGN KEY; UNIQUE via DROP INDEX") {
        val fk = ConstraintDefinition(
            name = "fk_x",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("uid"),
            references = ConstraintReferenceDefinition(table = "u", columns = listOf("id")),
        )
        val uq = ConstraintDefinition(name = "uq_y", type = ConstraintType.UNIQUE, columns = listOf("y"))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "t", constraintsRemoved = listOf(fk, uq)),
            ),
        )
        val sqls = planAndUp(diff).statements.map { it.sql }
        sqls shouldContain "ALTER TABLE `t` DROP FOREIGN KEY `fk_x`;"
        sqls shouldContain "ALTER TABLE `t` DROP INDEX `uq_y`;"
    }

    test("AddIndex / DropIndex use DROP INDEX … ON tbl syntax") {
        val idx = IndexDefinition(
            name = "idx_email",
            columns = listOf(IndexColumn("email")),
            type = IndexType.BTREE,
            unique = true,
        )
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx))))
        planAndUp(diff).statements.single().sql shouldContainStr "CREATE UNIQUE INDEX `idx_email` ON `users`"
        planAndDown(diff).statements.single().sql shouldBe "DROP INDEX `idx_email` ON `users`;"
    }

    test("CreateCustomType ENUM is blocked on MySQL") {
        val enumT = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a"))
        val r = planAndUp(SchemaDiff(customTypesAdded = listOf(dev.dmigrate.core.diff.NamedCustomType("t", enumT))))
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("DropCustomType is blocked on MySQL") {
        val enumT = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a"))
        val r = planAndUp(SchemaDiff(customTypesRemoved = listOf(dev.dmigrate.core.diff.NamedCustomType("t", enumT))))
        r.isBlocked shouldBe true
    }

    test("CreateView and DropView round-trip") {
        val v = ViewDefinition(query = "SELECT 1")
        val rUp = planAndUp(SchemaDiff(viewsAdded = listOf(dev.dmigrate.core.diff.NamedView("v_x", v))))
        rUp.statements.single().sql shouldContainStr "CREATE VIEW `v_x`"

        val rDown = planAndDown(SchemaDiff(viewsAdded = listOf(dev.dmigrate.core.diff.NamedView("v_x", v))))
        rDown.statements.single().sql shouldBe "DROP VIEW `v_x`;"
    }

    test("ReplaceView emits CREATE OR REPLACE; target swap on Down") {
        val before = ViewDefinition(query = "SELECT 1")
        val after = ViewDefinition(query = "SELECT 2")
        val current = emptySchema().copy(views = mapOf("v" to before))
        val desired = emptySchema().copy(views = mapOf("v" to after))
        val diff = SchemaDiff(
            viewsChanged = listOf(
                dev.dmigrate.core.diff.ViewDiff(name = "v", query = ValueChange("SELECT 1", "SELECT 2")),
            ),
        )
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
            .statements.single().sql shouldContainStr "SELECT 2"
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())
            .statements.single().sql shouldContainStr "SELECT 1"
    }

    test("materialized view ops block with MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT on MySQL") {
        // Plan-2 §8 D.3b Sub-Slice A: MySQL lacks native materialized
        // view support. The new MV op classes still get planned, but
        // the renderer dispatches them to a dialect-specific block
        // path with a deterministic diagnostic code.
        val view = ViewDefinition(query = "SELECT 1", materialized = true)
        val create = planAndUp(SchemaDiff(viewsAdded = listOf(dev.dmigrate.core.diff.NamedView("mv", view))))

        create.statements.shouldBeEmpty()
        create.isBlocked shouldBe true
        create.blockers.single().reason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        val createDiag = create.diagnostics.single { it.code == "MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT" }
        createDiag.message shouldContainStr "mv"
        createDiag.message shouldContainStr "MySQL"

        // Sub-Slice B: a body change on a materialized view routes through
        // ReplaceMaterializedView, which the dispatcher catches via the
        // dialect-block path with MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT.
        val current = emptySchema().copy(views = mapOf("mv" to view))
        val desired = emptySchema().copy(views = mapOf("mv" to view.copy(query = "SELECT 2")))
        val replace = gen.generateUp(
            planner.plan(
                current,
                desired,
                SchemaDiff(viewsChanged = listOf(
                    dev.dmigrate.core.diff.ViewDiff(name = "mv", query = ValueChange("SELECT 1", "SELECT 2")),
                )),
            ),
            DdlGenerationOptions(),
        )
        replace.statements.shouldBeEmpty()
        replace.diagnostics.any { it.code == "MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT" } shouldBe true

        val drop = planAndUp(SchemaDiff(viewsRemoved = listOf(dev.dmigrate.core.diff.NamedView("mv", view))))
        drop.statements.shouldBeEmpty()
        drop.diagnostics.any { it.code == "MATERIALIZED_VIEW_NOT_SUPPORTED_BY_DIALECT" } shouldBe true
    }

    test("Sequence ops without helper_table mode block with E056 / MANUAL_ACTION_REQUIRED") {
        // E.3 Sub-Slice B: sequence diff renderer requires
        // `--mysql-named-sequences helper_table`. The default
        // `ACTION_REQUIRED` mode blocks at the renderer with E056
        // → MANUAL_ACTION_REQUIRED (no SQL emitted).
        val seq = dev.dmigrate.core.model.SequenceDefinition(start = 1)
        val r = planAndUp(SchemaDiff(sequencesAdded = listOf(dev.dmigrate.core.diff.NamedSequence("s", seq))))
        r.isBlocked shouldBe true
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "E056" } shouldBe true
        r.blockers.any {
            it.reason == dev.dmigrate.driver.migration.MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        } shouldBe true
    }

    test("Empty diff yields empty result without blockers") {
        val r = planAndUp(SchemaDiff())
        r.statements.shouldBeEmpty()
        r.isBlocked shouldBe false
    }

    test("Anonymous index uses anon_<cols>_idx fallback") {
        val idx = IndexDefinition(name = null, columns = listOf(IndexColumn("c1")), type = IndexType.BTREE)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "t", indicesAdded = listOf(idx))))
        planAndUp(diff).statements.single().sql shouldContainStr "`t_c1_idx`"
    }

    test("Hash index emits USING HASH") {
        val idx = IndexDefinition(
            name = "h",
            columns = listOf(IndexColumn("c1")),
            type = IndexType.HASH,
        )
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "t", indicesAdded = listOf(idx))))
        planAndUp(diff).statements.single().sql shouldContainStr "USING HASH"
    }

    // ── Plan-2 §A.1: dialect execution hints ──

    test("§A.1: MySQL CreateTable statements carry IMPLICIT_COMMIT + sideEffectsPossible hints") {
        val t = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", t))))
        val hints = r.statements.single().hints
        hints.transactionBehavior shouldBe TransactionBehavior.IMPLICIT_COMMIT
        hints.lockBehavior shouldBe LockBehavior.TABLE_EXCLUSIVE
        hints.implicitCommitPossible shouldBe true
        hints.sideEffectsPossible shouldBe true
        hints.requiresExclusiveAccess shouldBe true
    }
})
