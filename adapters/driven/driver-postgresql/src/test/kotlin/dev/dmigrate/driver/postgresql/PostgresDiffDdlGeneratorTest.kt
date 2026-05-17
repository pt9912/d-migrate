package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayConversionReversibility
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDataRisk
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDiagnostics
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.OverlayText
import dev.dmigrate.core.diff.migration.overlay.UsingExpressionOverlayEntry
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
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewColumnDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.TransactionBehavior
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

class PostgresDiffDdlGeneratorTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun plan(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        overlays: (DiffResult) -> List<MigrationOverlayDocument> = { emptyList() },
    ): DiffResult {
        val planned = planner.plan(current, desired, diff)
        return planned.copy(migrationOverlays = overlays(planned))
    }

    fun planAndUp(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        options: DdlGenerationOptions = DdlGenerationOptions(),
        overlays: (DiffResult) -> List<MigrationOverlayDocument> = { emptyList() },
    ) = gen.generateUp(plan(diff, current, desired, overlays), options)

    fun planAndDown(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        overlays: (DiffResult) -> List<MigrationOverlayDocument> = { emptyList() },
    ) = gen.generateDown(plan(diff, current, desired, overlays), DdlGenerationOptions())

    test("dialect is POSTGRESQL") {
        gen.dialect.name shouldBe "POSTGRESQL"
    }

    test("empty diff yields empty result, no blockers") {
        val r = planAndUp(SchemaDiff())
        r.statements.shouldBeEmpty()
        r.isBlocked shouldBe false
    }

    test("CreateTable renders columns + PK + FK + indices") {
        val users = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier(), required = true)),
            primaryKey = listOf("id"),
        )
        val orders = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
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
        sqls.any { it.contains("REFERENCES \"users\"(\"id\")") } shouldBe true
        sqls.any { it.startsWith("CREATE INDEX \"idx_orders_user\"") } shouldBe true
    }

    test("CreateTable with PK uses PRIMARY KEY clause") {
        val t = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", t))))
        r.statements.first().sql shouldContainStr "PRIMARY KEY (\"id\")"
    }

    test("DropTable renders DROP TABLE; non-reversible blocks down") {
        val r = planAndUp(SchemaDiff(tablesRemoved = listOf(NamedTable("legacy", TableDefinition()))))
        r.statements.single().sql shouldBe "DROP TABLE \"legacy\";"

        val rDown = planAndDown(SchemaDiff(tablesRemoved = listOf(NamedTable("legacy", TableDefinition()))))
        rDown.blockers.any { it.reason == MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE } shouldBe true
        rDown.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("AddColumn renders ALTER TABLE … ADD COLUMN; down emits DROP COLUMN") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("nick" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
        )
        val up = planAndUp(diff).statements.single().sql
        up shouldContainStr "ADD COLUMN \"nick\""
        val down = planAndDown(diff).statements.single().sql
        down shouldBe "ALTER TABLE \"users\" DROP COLUMN \"nick\";"
    }

    test("DropColumn is destructive and not reversible") {
        val r = planAndUp(
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "users",
                        columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text())),
                    ),
                ),
            ),
        )
        val opId = r.operationsRendered.single()
        r.destructiveOperations shouldContain opId
        r.nonReversibleOperations shouldContain opId
        r.requiresConfirmation shouldBe true
    }

    test("CreateSequence renders PostgreSQL sequence DDL and down drops it") {
        val sequence = SequenceDefinition(start = 10, increment = 5, minValue = 10, maxValue = 999, cycle = true, cache = 20)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("invoice_seq", sequence)))

        val up = planAndUp(diff)
        val down = planAndDown(diff)

        up.isBlocked shouldBe false
        up.statements.single().sql shouldBe
            "CREATE SEQUENCE \"invoice_seq\" START WITH 10 INCREMENT BY 5 MINVALUE 10 MAXVALUE 999 CYCLE CACHE 20;"
        up.statements.single().hints.transactionBehavior shouldBe TransactionBehavior.FULLY_TRANSACTIONAL
        up.statements.single().hints.lockBehavior shouldBe LockBehavior.METADATA
        down.statements.single().sql shouldBe "DROP SEQUENCE \"invoice_seq\";"
    }

    test("AlterSequence renders declarative attributes in both directions") {
        val before = SequenceDefinition(start = 1, increment = 1, minValue = 1, maxValue = 100, cycle = false, cache = 5)
        val after = SequenceDefinition(start = 50, increment = 10, minValue = null, maxValue = null, cycle = true, cache = 20)
        val current = emptySchema().copy(sequences = mapOf("invoice_seq" to before))
        val desired = emptySchema().copy(sequences = mapOf("invoice_seq" to after))
        val diff = SchemaDiff(
            sequencesChanged = listOf(
                SequenceDiff(
                    name = "invoice_seq",
                    start = ValueChange(1L, 50L),
                    increment = ValueChange(1L, 10L),
                    minValue = ValueChange(1L, null),
                    maxValue = ValueChange(100L, null),
                    cycle = ValueChange(false, true),
                    cache = ValueChange(5, 20),
                ),
            ),
        )

        planAndUp(diff, current, desired).statements.single().sql shouldBe
            "ALTER SEQUENCE \"invoice_seq\" START WITH 50 INCREMENT BY 10 NO MINVALUE NO MAXVALUE CYCLE CACHE 20;"
        planAndDown(diff, current, desired).statements.single().sql shouldBe
            "ALTER SEQUENCE \"invoice_seq\" START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 100 NO CYCLE CACHE 5;"
    }

    test("DropSequence renders DROP and can recreate declarative state on down") {
        val sequence = SequenceDefinition(start = 10, increment = 5, minValue = 10, maxValue = 999, cycle = false)
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("invoice_seq", sequence)))

        val up = planAndUp(diff)
        val down = planAndDown(diff)

        up.statements.single().sql shouldBe "DROP SEQUENCE \"invoice_seq\";"
        up.requiresConfirmation shouldBe true
        down.statements.single().sql shouldBe
            "CREATE SEQUENCE \"invoice_seq\" START WITH 10 INCREMENT BY 5 MINVALUE 10 MAXVALUE 999 NO CYCLE;"
    }

    test("AlterColumnType: SmallInt → Integer is safe, rendered with TYPE clause") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.statements.single().sql shouldContainStr "ALTER COLUMN \"age\" TYPE"
        r.isBlocked shouldBe false
    }

    test("AlterColumnType: Integer → Text needs USING overlay and is blocked without it") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "age",
                            type = ValueChange(NeutralType.Integer, NeutralType.Text()),
                        ),
                    ),
                ),
            ),
        )
        val r = planAndUp(diff)
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code }.shouldContain("PG_USING_OVERLAY_MISSING")
    }

    test("AlterColumnType: explicit using-expression overlay renders USING clause") {
        val diff = integerToTextDiff()

        val r = planAndUp(diff) { planned ->
            listOf(
                MigrationOverlayDocument(
                    source = "overlays/age-using.json",
                    overlay = usingOverlay(planned, upExpression = "\"age\"::TEXT"),
                ),
            )
        }

        r.isBlocked shouldBe false
        r.statements.single().sql shouldBe
            "ALTER TABLE \"users\" ALTER COLUMN \"age\" TYPE TEXT USING \"age\"::TEXT;"
        r.diagnostics.single { it.code == "PG_USING_OVERLAY_APPLIED" }.message shouldContainStr
            "source=overlays/age-using.json"
    }

    test("AlterColumnType: missing downUsingExpression blocks rollback render") {
        val diff = integerToTextDiff()

        val r = planAndDown(diff) { planned ->
            listOf(
                MigrationOverlayDocument(
                    source = "overlays/age-using.json",
                    overlay = usingOverlay(
                        planned,
                        upExpression = "\"age\"::TEXT",
                        reversibility = MigrationOverlayConversionReversibility.NOT_REVERSIBLE,
                    ),
                ),
            )
        }

        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code }.shouldContain("PG_USING_OVERLAY_DOWN_MISSING")
    }

    test("AlterColumnType: using-expression overlay may not reference another column") {
        val diff = integerToTextDiff()

        val r = planAndUp(diff) { planned ->
            listOf(
                MigrationOverlayDocument(
                    source = "overlays/age-using.json",
                    overlay = usingOverlay(planned, upExpression = "\"other_column\"::TEXT"),
                ),
            )
        }

        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code }.shouldContain("PG_USING_OVERLAY_INVALID_EXPRESSION")
    }

    test("AlterColumnType: duplicate matching using entries are ambiguous") {
        val diff = integerToTextDiff()

        val r = planAndUp(diff) { planned ->
            listOf(
                MigrationOverlayDocument(
                    source = "overlays/age-using.json",
                    overlay = usingOverlay(planned, upExpression = "\"age\"::TEXT", duplicateEntry = true),
                ),
            )
        }

        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code }.shouldContain("PG_USING_OVERLAY_AMBIGUOUS")
    }

    test("AlterColumnType: secret using-expression is not copied into rendered SQL") {
        val diff = integerToTextDiff()
        val secret = "secret_cast_expression"

        val r = planAndUp(diff) { planned ->
            listOf(
                MigrationOverlayDocument(
                    source = "overlays/age-using.json",
                    overlay = usingOverlay(planned, upExpression = secret, upSecret = true),
                ),
            )
        }

        r.isBlocked shouldBe true
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code }.shouldContain(MigrationOverlayDiagnostics.SECRET_BEARING_FIELD)
        r.toString().contains(secret) shouldBe false
    }

    test("AlterColumnNullability: required→nullable up + down toggles") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "email",
                            required = ValueChange(true, false),
                        ),
                    ),
                ),
            ),
        )
        planAndUp(diff).statements.single().sql shouldContainStr "DROP NOT NULL"
        planAndDown(diff).statements.single().sql shouldContainStr "SET NOT NULL"
    }

    test("AlterColumnDefault: null→value SET DEFAULT, down DROP DEFAULT") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "status",
                            default = ValueChange(null, DefaultValue.StringLiteral("active")),
                        ),
                    ),
                ),
            ),
        )
        planAndUp(diff).statements.single().sql shouldContainStr "SET DEFAULT"
        planAndDown(diff).statements.single().sql shouldContainStr "DROP DEFAULT"
    }

    test("AddPrimaryKey: up adds, down drops _pkey constraint") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", primaryKey = ValueChange(emptyList(), listOf("id"))),
            ),
        )
        planAndUp(diff).statements.single().sql shouldContainStr "ADD PRIMARY KEY"
        planAndDown(diff).statements.single().sql shouldContainStr "DROP CONSTRAINT IF EXISTS \"users_pkey\""
    }

    test("DropPrimaryKey: up drops, down re-adds") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", primaryKey = ValueChange(listOf("id"), emptyList())),
            ),
        )
        planAndUp(diff).statements.single().sql shouldContainStr "DROP CONSTRAINT IF EXISTS \"users_pkey\""
        planAndDown(diff).statements.single().sql shouldContainStr "ADD PRIMARY KEY"
    }

    test("AddConstraint UNIQUE: up adds, down drops") {
        val c = ConstraintDefinition(name = "uq_email", type = ConstraintType.UNIQUE, columns = listOf("email"))
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", constraintsAdded = listOf(c))),
        )
        planAndUp(diff).statements.single().sql shouldContainStr "ADD CONSTRAINT \"uq_email\" UNIQUE"
        planAndDown(diff).statements.single().sql shouldContainStr "DROP CONSTRAINT \"uq_email\""
    }

    test("AddConstraint FOREIGN_KEY emits REFERENCES clause") {
        val c = ConstraintDefinition(
            name = "fk_orders_users",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "orders", constraintsAdded = listOf(c))),
        )
        val sql = planAndUp(diff).statements.single().sql
        sql shouldContainStr "FOREIGN KEY (\"user_id\")"
        sql shouldContainStr "REFERENCES \"users\"(\"id\")"
    }

    test("AddIndex with explicit name; DropIndex round-trip") {
        val idx = IndexDefinition(
            name = "idx_users_email",
            columns = listOf(IndexColumn("email")),
            type = IndexType.BTREE,
            unique = true,
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx))),
        )
        val up = planAndUp(diff).statements.single().sql
        up shouldContainStr "CREATE UNIQUE INDEX \"idx_users_email\""
        val down = planAndDown(diff).statements.single().sql
        down shouldBe "DROP INDEX \"idx_users_email\";"
    }

    test("Anonymous index uses anon-name fallback") {
        val idx = IndexDefinition(name = null, columns = listOf(IndexColumn("c1")), type = IndexType.BTREE)
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "t", indicesAdded = listOf(idx))),
        )
        planAndUp(diff).statements.single().sql shouldContainStr "\"t_c1_idx\""
    }

    test("CreateCustomType ENUM is rendered; non-ENUM blocked") {
        val enumT = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "b"))
        val r = planAndUp(SchemaDiff(customTypesAdded = listOf(dev.dmigrate.core.diff.NamedCustomType("status_t", enumT))))
        r.statements.single().sql shouldContainStr "CREATE TYPE \"status_t\" AS ENUM"

        val composite = CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE, fields = mapOf())
        val r2 = planAndUp(SchemaDiff(customTypesAdded = listOf(dev.dmigrate.core.diff.NamedCustomType("c_t", composite))))
        r2.isBlocked shouldBe true
    }

    test("DropCustomType ENUM is rendered; non-ENUM blocked") {
        val enumT = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a"))
        val r = planAndUp(SchemaDiff(customTypesRemoved = listOf(dev.dmigrate.core.diff.NamedCustomType("e_t", enumT))))
        r.statements.single().sql shouldBe "DROP TYPE \"e_t\";"

        val domain = CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "TEXT")
        val r2 = planAndUp(SchemaDiff(customTypesRemoved = listOf(dev.dmigrate.core.diff.NamedCustomType("d_t", domain))))
        r2.isBlocked shouldBe true
    }

    test("CreateView and DropView round-trip") {
        val v = ViewDefinition(query = "SELECT 1")
        val rUp = planAndUp(SchemaDiff(viewsAdded = listOf(dev.dmigrate.core.diff.NamedView("v_x", v))))
        rUp.statements.single().sql shouldContainStr "CREATE VIEW \"v_x\""

        val rDown = planAndDown(SchemaDiff(viewsAdded = listOf(dev.dmigrate.core.diff.NamedView("v_x", v))))
        rDown.statements.single().sql shouldBe "DROP VIEW \"v_x\";"
    }

    test("ReplaceView emits CREATE OR REPLACE in both directions, with target swap on Down") {
        val signature = listOf(ViewColumnDefinition("x", "integer"))
        val before = ViewDefinition(query = "SELECT 1 AS x", columns = signature)
        val after = ViewDefinition(query = "SELECT 2 AS x", columns = signature)
        val current = emptySchema().copy(views = mapOf("v_x" to before))
        val desired = emptySchema().copy(views = mapOf("v_x" to after))
        val diff = SchemaDiff(
            viewsChanged = listOf(
                dev.dmigrate.core.diff.ViewDiff(name = "v_x", query = ValueChange(before.query, after.query)),
            ),
        )
        val up = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        up.statements.single().sql shouldContainStr "SELECT 2"
        val down = gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())
        down.statements.single().sql shouldContainStr "SELECT 1"
    }

    test("ReplaceView blocks without PostgreSQL visible signature metadata") {
        val before = ViewDefinition(query = "SELECT 1")
        val after = ViewDefinition(query = "SELECT 2")
        val current = emptySchema().copy(views = mapOf("v_x" to before))
        val desired = emptySchema().copy(views = mapOf("v_x" to after))
        val diff = SchemaDiff(
            viewsChanged = listOf(
                dev.dmigrate.core.diff.ViewDiff(name = "v_x", query = ValueChange(before.query, after.query)),
            ),
        )

        val result = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

        result.statements.shouldBeEmpty()
        result.isBlocked shouldBe true
        result.diagnostics.single { it.code == "VIEW_SIGNATURE_UNKNOWN" }
            .message shouldContainStr "v_x"
    }

    test("ReplaceView blocks incompatible PostgreSQL visible signatures") {
        val before = ViewDefinition(
            query = "SELECT 1 AS x",
            columns = listOf(ViewColumnDefinition("x", "integer")),
        )
        val after = ViewDefinition(
            query = "SELECT 1 AS y",
            columns = listOf(ViewColumnDefinition("y", "integer")),
        )
        val current = emptySchema().copy(views = mapOf("v_x" to before))
        val desired = emptySchema().copy(views = mapOf("v_x" to after))
        val diff = SchemaDiff(
            viewsChanged = listOf(
                dev.dmigrate.core.diff.ViewDiff(
                    name = "v_x",
                    query = ValueChange(before.query, after.query),
                    columnsChanged = true,
                ),
            ),
        )

        val result = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

        result.statements.shouldBeEmpty()
        result.isBlocked shouldBe true
        result.diagnostics.single { it.code == "VIEW_SIGNATURE_INCOMPATIBLE" }
            .message shouldContainStr "v_x"
    }

    // Plan-2 §8 D.3b Sub-Slice A: materialized-view render/block paths
    // live in [PostgresDiffMaterializedViewTest] to keep
    // [PostgresDiffDdlGeneratorTest] under Detekt's `LargeClass`
    // threshold.

    test("Planner blockers (CONSTRAINT_NOT_DIFFABLE) cascade into DIALECT_UNSUPPORTED_OPERATION") {
        val tableWithCheck = TableDefinition(
            columns = mapOf("age" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(
                ConstraintDefinition(name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0"),
            ),
        )
        val current = emptySchema().copy(tables = mapOf("users" to tableWithCheck))
        val desired = emptySchema()
        val diff = SchemaDiff(tablesRemoved = listOf(NamedTable("users", tableWithCheck)))
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("Both planner blockers AND renderer blockers coexist in result.blockers") {
        // CONSTRAINT_NOT_DIFFABLE on `users` (planner) + AlterCustomType (renderer-side
        // DIALECT_UNSUPPORTED). Both must surface so a CLI sees the full picture.
        val tableWithCheck = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(
                ConstraintDefinition(name = "chk_x", type = ConstraintType.CHECK, expression = "id > 0"),
            ),
        )
        val customType = dev.dmigrate.core.model.CustomTypeDefinition(
            kind = dev.dmigrate.core.model.CustomTypeKind.ENUM,
            values = listOf("a"),
        )
        val current = emptySchema().copy(
            tables = mapOf("users" to tableWithCheck),
            customTypes = mapOf("status_t" to customType),
        )
        val desired = emptySchema().copy(
            customTypes = mapOf("status_t" to customType.copy(values = listOf("a", "b"))),
        )
        val diff = SchemaDiff(
            tablesRemoved = listOf(NamedTable("users", tableWithCheck)),
            customTypesChanged = listOf(
                dev.dmigrate.core.diff.CustomTypeDiff(
                    name = "status_t",
                    values = ValueChange(listOf("a"), listOf("a", "b")),
                ),
            ),
        )
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        // Planner-side cascade blocker present
        r.blockers.any { it.reason == MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION &&
            it.diagnostics.any { d -> d.code == "CONSTRAINT_NOT_DIFFABLE" } } shouldBe true
        // Renderer-side blocker (AlterCustomType is out-of-scope) also present
        val rendererBlocker = r.blockers.any { it.reason == MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION &&
            it.diagnostics.isEmpty() } || r.diagnostics.any { it.code == "POSTGRES_RENDER_SKIP" }
        rendererBlocker shouldBe true
    }

    // ── Plan-2 §A.1: dialect execution hints ──

    test("§A.1: PostgreSQL CreateTable statements carry FULLY_TRANSACTIONAL + TABLE_EXCLUSIVE hints") {
        val t = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", t))))
        val hints = r.statements.single().hints
        hints.transactionBehavior shouldBe TransactionBehavior.FULLY_TRANSACTIONAL
        hints.lockBehavior shouldBe LockBehavior.TABLE_EXCLUSIVE
        hints.implicitCommitPossible shouldBe false
        hints.sideEffectsPossible shouldBe false
        hints.requiresExclusiveAccess shouldBe true
    }

    test("§A.1: PostgreSQL CREATE INDEX uses TABLE_SHARED + no exclusive access") {
        // PG `CREATE INDEX` (non-CONCURRENTLY) takes SHARE lock —
        // writes block, reads proceed. Honest LockBehavior is shared.
        val before = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val after = before.copy(
            indices = listOf(
                IndexDefinition(name = "ix_t_id", columns = listOf(IndexColumn("id")), type = IndexType.BTREE),
            ),
        )
        val current = SchemaDefinition(name = "App", version = "1", tables = mapOf("t" to before))
        val desired = SchemaDefinition(name = "App", version = "1", tables = mapOf("t" to after))
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "t", indicesAdded = after.indices)),
        )
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        val createIndex = r.statements.single { it.sql.startsWith("CREATE INDEX") }
        createIndex.hints.transactionBehavior shouldBe TransactionBehavior.FULLY_TRANSACTIONAL
        createIndex.hints.lockBehavior shouldBe LockBehavior.TABLE_SHARED
        createIndex.hints.requiresExclusiveAccess shouldBe false
    }

    test("§A.1: PostgreSQL CREATE VIEW uses METADATA + no exclusive access") {
        val view = ViewDefinition(query = "SELECT 1 AS x", sourceDialect = "postgresql")
        val desired = SchemaDefinition(name = "App", version = "1", views = mapOf("v" to view))
        val diff = SchemaDiff(viewsAdded = listOf(dev.dmigrate.core.diff.NamedView("v", view)))
        val r = gen.generateUp(planner.plan(emptySchema(), desired, diff), DdlGenerationOptions())
        val createView = r.statements.single { it.sql.startsWith("CREATE VIEW") }
        createView.hints.transactionBehavior shouldBe TransactionBehavior.FULLY_TRANSACTIONAL
        createView.hints.lockBehavior shouldBe LockBehavior.METADATA
        createView.hints.requiresExclusiveAccess shouldBe false
    }

    test("§A.1: PostgreSQL CREATE TYPE uses METADATA + no exclusive access") {
        val enumType = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "b"))
        val desired = SchemaDefinition(
            name = "App",
            version = "1",
            customTypes = mapOf("status_t" to enumType),
        )
        val diff = SchemaDiff(
            customTypesAdded = listOf(dev.dmigrate.core.diff.NamedCustomType("status_t", enumType)),
        )
        val r = gen.generateUp(planner.plan(emptySchema(), desired, diff), DdlGenerationOptions())
        val createType = r.statements.single { it.sql.startsWith("CREATE TYPE") }
        createType.hints.transactionBehavior shouldBe TransactionBehavior.FULLY_TRANSACTIONAL
        createType.hints.lockBehavior shouldBe LockBehavior.METADATA
        createType.hints.requiresExclusiveAccess shouldBe false
    }
})

private fun integerToTextDiff(): SchemaDiff =
    SchemaDiff(
        tablesChanged = listOf(
            TableDiff(
                name = "users",
                columnsChanged = listOf(
                    dev.dmigrate.core.diff.ColumnDiff(
                        name = "age",
                        type = ValueChange(NeutralType.Integer, NeutralType.Text()),
                    ),
                ),
            ),
        ),
    )

private fun usingOverlay(
    planned: DiffResult,
    upExpression: String,
    downExpression: String? = null,
    upSecret: Boolean = false,
    duplicateEntry: Boolean = false,
    dataRisk: MigrationOverlayDataRisk = MigrationOverlayDataRisk.USER_ASSERTED_SAFE,
    reversibility: MigrationOverlayConversionReversibility =
        if (downExpression == null) {
            MigrationOverlayConversionReversibility.NOT_REVERSIBLE
        } else {
            MigrationOverlayConversionReversibility.AUTOMATIC
        },
): MigrationOverlay =
    MigrationOverlay(
        overlayKind = MigrationOverlayKinds.USING_EXPRESSION,
        sourceFingerprint = planned.current.fingerprint!!,
        targetFingerprint = planned.desired.fingerprint!!,
        dialect = "postgresql",
        entries = buildList {
            add(
                usingEntry(
                    id = "age-int-to-text",
                    upExpression = upExpression,
                    upSecret = upSecret,
                    downExpression = downExpression,
                    dataRisk = dataRisk,
                    reversibility = reversibility,
                ),
            )
            if (duplicateEntry) {
                add(
                    usingEntry(
                        id = "age-int-to-text-duplicate",
                        upExpression = upExpression,
                        upSecret = upSecret,
                        downExpression = downExpression,
                        dataRisk = dataRisk,
                        reversibility = reversibility,
                    ),
                )
            }
        },
        createdAt = "2026-05-12T10:15:30Z",
        createdByVersion = "d-migrate-test",
    ).withComputedHash()

private fun usingEntry(
    id: String,
    upExpression: String,
    upSecret: Boolean,
    downExpression: String?,
    dataRisk: MigrationOverlayDataRisk,
    reversibility: MigrationOverlayConversionReversibility,
): UsingExpressionOverlayEntry =
    UsingExpressionOverlayEntry(
        id = id,
        table = "users",
        column = "age",
        sourceType = "INTEGER",
        targetType = "TEXT",
        upUsingExpression = OverlayText(upExpression, secret = upSecret),
        downUsingExpression = downExpression?.let(::OverlayText),
        dataRisk = dataRisk,
        conversionReversibility = reversibility,
        expressionSource = "user",
        reviewedByUser = true,
    )
