package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.TransactionBehavior
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Sub-Slice 5a: the table/column/primary-key operation family. Constraints
 * and indices (5b) have their own spec, [OracleDiffObjectOpsTest]; the
 * families still ahead (views and custom types 5c, sequences 5d, routines,
 * triggers) are asserted UNSUPPORTED here.
 */
class OracleDiffDdlGeneratorTest : FunSpec({

    val planner = DiffPlanner()
    val gen = OracleDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun plan(diff: SchemaDiff, current: SchemaDefinition = emptySchema(), desired: SchemaDefinition = emptySchema()): DiffResult =
        planner.plan(current, desired, diff)

    fun planAndUp(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        options: DdlGenerationOptions = DdlGenerationOptions(),
    ) = gen.generateUp(plan(diff, current, desired), options)

    fun planAndDown(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
    ) = gen.generateDown(plan(diff, current, desired), DdlGenerationOptions())

    test("dialect is ORACLE") {
        gen.dialect.name shouldBe "ORACLE"
    }

    test("empty diff yields empty result, no blockers") {
        val r = planAndUp(SchemaDiff())
        r.statements.shouldBeEmpty()
        r.isBlocked shouldBe false
    }

    test("CreateTable renders columns + PK + FK + index, byte-identical to the Generate helper") {
        val users = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true), required = true)),
            primaryKey = listOf("id"),
        )
        val orders = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true), required = true),
                "user_id" to ColumnDefinition(
                    NeutralType.Integer,
                    references = ReferenceDefinition(table = "users", column = "id"),
                ),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(name = "idx_orders_user", columns = listOf(IndexColumn("user_id")), type = IndexType.BTREE),
            ),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("users", users), NamedTable("orders", orders)))
        val r = planAndUp(diff)
        val sqls = r.statements.map { it.sql }
        sqls.any { it.startsWith("CREATE TABLE \"users\"") } shouldBe true
        sqls.any { it.startsWith("CREATE TABLE \"orders\"") } shouldBe true
        sqls.any { it.contains("FOREIGN KEY (\"user_id\") REFERENCES \"users\" (\"id\")") } shouldBe true
        sqls.any { it.startsWith("CREATE INDEX \"idx_orders_user\"") } shouldBe true
        r.isBlocked shouldBe false
    }

    test("CreateTable with PK uses a named PRIMARY KEY constraint") {
        val t = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", t))))
        r.statements.first().sql shouldContain "CONSTRAINT \"pk_t\" PRIMARY KEY (\"id\")"
    }

    test("DropTable renders DROP TABLE; non-reversible blocks down") {
        val t = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)))
        val diff = SchemaDiff(tablesRemoved = listOf(NamedTable("t", t)))
        planAndUp(diff, current = emptySchema().copy(tables = mapOf("t" to t))).statements.single().sql shouldBe
            "DROP TABLE \"t\";"
        val down = planAndDown(diff, current = emptySchema().copy(tables = mapOf("t" to t)))
        down.isBlocked shouldBe true
        down.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("AddColumn renders ALTER TABLE ... ADD (...); down emits DROP COLUMN") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", columnsAdded = mapOf("nick" to ColumnDefinition(NeutralType.Text()))),
            ),
        )
        val current = emptySchema().copy(tables = mapOf("users" to TableDefinition(columns = emptyMap())))
        val desired = emptySchema().copy(
            tables = mapOf("users" to TableDefinition(columns = mapOf("nick" to ColumnDefinition(NeutralType.Text())))),
        )
        planAndUp(diff, current, desired).statements.single().sql shouldContain "ADD (\"nick\" CLOB)"
        planAndDown(diff, current, desired).statements.single().sql shouldBe "ALTER TABLE \"users\" DROP COLUMN \"nick\";"
    }

    test("DropColumn is destructive and not reversible") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text()))),
            ),
        )
        val r = planAndUp(diff)
        r.statements.single().sql shouldBe "ALTER TABLE \"users\" DROP COLUMN \"legacy\";"
        r.destructiveOperations.size shouldBe 1
        r.nonReversibleOperations.size shouldBe 1
    }

    test("AlterColumnType: SmallInt to Integer renders MODIFY") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(ColumnDiff(name = "age", type = ValueChange(NeutralType.SmallInt, NeutralType.Integer))),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.statements.single().sql shouldBe "ALTER TABLE \"users\" MODIFY \"age\" NUMBER(9);"
        r.isBlocked shouldBe false
    }

    test("AlterColumnType: inline-values enum warns W134 (unbounded, no CHECK)") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "status",
                            type = ValueChange(NeutralType.Text(), NeutralType.Enum(values = listOf("a", "b"))),
                        ),
                    ),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.statements.single().sql shouldContain "VARCHAR2(4000)"
        r.diagnostics.any { it.code == "W134" } shouldBe true
    }

    test("AlterColumnType: refType enum degrades identically and also warns W134") {
        // Anders als PostgreSQL (natives ENUM-Objekt) hat Oracle keinen
        // nativen Enum-Typ -- auch eine refType-Enum landet auf VARCHAR2(4000).
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "status",
                            type = ValueChange(NeutralType.Text(), NeutralType.Enum(refType = "status_enum")),
                        ),
                    ),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.statements.single().sql shouldContain "VARCHAR2(4000)"
        r.diagnostics.any { it.code == "W134" } shouldBe true
    }

    test("AlterColumnType: removing identity emits DROP IDENTITY before the type change") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "id",
                            type = ValueChange(NeutralType.Identifier(autoIncrement = true), NeutralType.BigInteger),
                        ),
                    ),
                ),
            ),
        )
        val sqls = planAndUp(diff).statements.map { it.sql }
        sqls shouldBe listOf(
            "ALTER TABLE \"users\" MODIFY \"id\" DROP IDENTITY;",
            "ALTER TABLE \"users\" MODIFY \"id\" NUMBER(18);",
        )
    }

    test("AlterColumnType: adding identity blocks — Oracle cannot promote an existing column (ORA-30673)") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "id",
                            type = ValueChange(NeutralType.BigInteger, NeutralType.Identifier(autoIncrement = true)),
                        ),
                    ),
                ),
            ),
        )
        val up = planAndUp(diff)
        up.statements.shouldBeEmpty()
        up.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        up.diagnostics.any { it.code == "ORACLE_ADD_IDENTITY_UNSUPPORTED" } shouldBe true
        // Die Gegenrichtung ist der Entfernen-Fall und rendert deshalb sauber:
        // die Blockade ist richtungsabhaengig, nicht operationsweit.
        val down = planAndDown(diff)
        down.statements.map { it.sql } shouldBe listOf(
            "ALTER TABLE \"users\" MODIFY \"id\" DROP IDENTITY;",
            "ALTER TABLE \"users\" MODIFY \"id\" NUMBER(18);",
        )
    }

    test("AlterColumnNullability: required to nullable up + down toggles") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", columnsChanged = listOf(ColumnDiff(name = "email", required = ValueChange(true, false)))),
            ),
        )
        planAndUp(diff).statements.single().sql shouldBe "ALTER TABLE \"users\" MODIFY \"email\" NULL;"
        planAndDown(diff).statements.single().sql shouldBe "ALTER TABLE \"users\" MODIFY \"email\" NOT NULL;"
    }

    test("AlterColumnDefault: resolves the column's real type from the schema (SYSTIMESTAMP, not SYSDATE)") {
        val col = ColumnDefinition(NeutralType.DateTime(timezone = true))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "events",
                    columnsChanged = listOf(
                        ColumnDiff(name = "happened_at", default = ValueChange(null, DefaultValue.FunctionCall("current_timestamp"))),
                    ),
                ),
            ),
        )
        val desired = emptySchema().copy(tables = mapOf("events" to TableDefinition(columns = mapOf("happened_at" to col))))
        val r = planAndUp(diff, desired = desired)
        r.statements.single().sql shouldBe "ALTER TABLE \"events\" MODIFY \"happened_at\" DEFAULT SYSTIMESTAMP;"
    }

    test("AlterColumnDefault: null target drops the default") {
        val col = ColumnDefinition(NeutralType.Text())
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(ColumnDiff(name = "status", default = ValueChange(DefaultValue.StringLiteral("x"), null))),
                ),
            ),
        )
        val current = emptySchema().copy(tables = mapOf("users" to TableDefinition(columns = mapOf("status" to col))))
        planAndUp(diff, current = current).statements.single().sql shouldBe
            "ALTER TABLE \"users\" MODIFY \"status\" DEFAULT NULL;"
    }

    test("AlterColumnDefault: missing column in the DiffResult's schema blocks with MANUAL_ACTION_REQUIRED") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(ColumnDiff(name = "status", default = ValueChange(null, DefaultValue.StringLiteral("x")))),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }

    test("AddPrimaryKey: up adds a named constraint, down drops unnamed (no catalog lookup needed)") {
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", primaryKey = ValueChange(emptyList(), listOf("id")))))
        planAndUp(diff).statements.single().sql shouldBe "ALTER TABLE \"users\" ADD CONSTRAINT \"pk_users\" PRIMARY KEY (\"id\");"
        planAndDown(diff).statements.single().sql shouldBe "ALTER TABLE \"users\" DROP PRIMARY KEY;"
    }

    test("DropPrimaryKey: up drops unnamed, down re-adds") {
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", primaryKey = ValueChange(listOf("id"), emptyList()))))
        planAndUp(diff).statements.single().sql shouldBe "ALTER TABLE \"users\" DROP PRIMARY KEY;"
        planAndDown(diff).statements.single().sql shouldBe "ALTER TABLE \"users\" ADD CONSTRAINT \"pk_users\" PRIMARY KEY (\"id\");"
    }

    test("CreateView is not yet supported (Sub-Slice 5c) and blocks DIALECT_UNSUPPORTED_OPERATION") {
        val view = ViewDefinition(query = "SELECT 1 FROM dual")
        val diff = SchemaDiff(viewsAdded = listOf(NamedView("v_x", view)))
        val r = planAndUp(diff)
        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("Oracle DDL carries IMPLICIT_COMMIT (no cross-statement rollback), unlike PostgreSQL/MSSQL") {
        val t = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)))
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", t))))
        r.statements.single().hints.transactionBehavior shouldBe TransactionBehavior.IMPLICIT_COMMIT
    }
})
