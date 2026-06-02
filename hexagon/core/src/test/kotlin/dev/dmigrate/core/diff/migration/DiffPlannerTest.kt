package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

class DiffPlannerTest : FunSpec({

    val planner = DiffPlanner()

    fun emptyDiff() = SchemaDiff()

    fun schemaWith(tables: Map<String, TableDefinition> = emptyMap()) =
        SchemaDefinition(name = "App", version = "1", tables = tables)

    test("empty diff yields empty operations and no diagnostics; endpoints have fingerprints") {
        val current = schemaWith()
        val desired = schemaWith()
        val result = planner.plan(current, desired, emptyDiff())
        result.operations.shouldBeEmpty()
        result.diagnostics.shouldBeEmpty()
        result.current.fingerprint shouldNotBe null
        result.desired.fingerprint shouldNotBe null
    }

    test("tablesAdded yields CreateTable; tablesRemoved yields DropTable") {
        val orders = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("orders", orders)),
            tablesRemoved = listOf(NamedTable("legacy", TableDefinition())),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)
        result.operations.filterIsInstance<DiffOperation.CreateTable>().single().objectRef.rootName shouldBe "orders"
        result.operations.filterIsInstance<DiffOperation.DropTable>().single().objectRef.rootName shouldBe "legacy"
    }

    test("FK column on a new table depends on the referenced table's CreateTable") {
        val users = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
        )
        val orders = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "user_id" to ColumnDefinition(
                    NeutralType.Integer,
                    references = ReferenceDefinition(table = "users", column = "id"),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", users), NamedTable("orders", orders)),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)

        val createUsers = result.operations.filterIsInstance<DiffOperation.CreateTable>()
            .single { it.objectRef.rootName == "users" }
        val createOrders = result.operations.filterIsInstance<DiffOperation.CreateTable>()
            .single { it.objectRef.rootName == "orders" }
        createOrders.dependencies shouldContain createUsers.id
        // Topological sort places users before orders.
        result.operations.indexOf(createUsers) shouldBe 0
        result.operations.indexOf(createOrders) shouldBe 1
    }

    test("AddColumn with FK depends on the referenced table's CreateTable when both are new") {
        val users = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
        )
        val ordersChange = TableDiff(
            name = "orders",
            columnsAdded = mapOf(
                "user_id" to ColumnDefinition(
                    NeutralType.Integer,
                    references = ReferenceDefinition(table = "users", column = "id"),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", users)),
            tablesChanged = listOf(ordersChange),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)

        val createUsers = result.operations.filterIsInstance<DiffOperation.CreateTable>().single()
        val addColumn = result.operations.filterIsInstance<DiffOperation.AddColumn>().single()
        addColumn.dependencies shouldContain createUsers.id
    }

    test("AddConstraint FOREIGN_KEY depends on referenced table's CreateTable when added") {
        val users = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        val ordersChange = TableDiff(
            name = "orders",
            constraintsAdded = listOf(
                ConstraintDefinition(
                    name = "fk_orders_users",
                    type = ConstraintType.FOREIGN_KEY,
                    columns = listOf("user_id"),
                    references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", users)),
            tablesChanged = listOf(ordersChange),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)

        val createUsers = result.operations.filterIsInstance<DiffOperation.CreateTable>().single()
        val addConstraint = result.operations.filterIsInstance<DiffOperation.AddConstraint>().single()
        addConstraint.dependencies shouldContain createUsers.id
    }

    test("§F.5 Sub-Slice A: CHECK constraint diffs flow through the mapper without planner-level block") {
        // Sub-Slice A removes the planner-level CONSTRAINT_NOT_DIFFABLE
        // blanket for non-cross-table CHECK / EXCLUDE diffs. The
        // DropTable for the table holding the CHECK is emitted; the
        // CHECK itself rides as a DropConstraint op (renderer
        // decides per dialect whether to render).
        val tableWithCheck = TableDefinition(
            columns = mapOf("age" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(
                ConstraintDefinition(name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0"),
            ),
        )
        val current = schemaWith(tables = mapOf("users" to tableWithCheck))
        val desired = schemaWith()
        val diff = SchemaDiff(
            tablesRemoved = listOf(NamedTable("users", tableWithCheck)),
        )
        val result = planner.plan(current, desired, diff)
        result.hasBlockers shouldBe false
        result.diagnostics.map { it.code } shouldNotContain "CONSTRAINT_NOT_DIFFABLE"
        result.operations.filterIsInstance<DiffOperation.DropTable>()
            .any { it.objectRef.rootName == "users" } shouldBe true
    }

    test("DropTable depends on DropConstraint of FK pointing at the dropped table") {
        val orders = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "user_id" to ColumnDefinition(NeutralType.Integer),
            ),
            constraints = listOf(
                ConstraintDefinition(
                    name = "fk_orders_users",
                    type = ConstraintType.FOREIGN_KEY,
                    columns = listOf("user_id"),
                    references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
                ),
            ),
        )
        val users = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        val current = schemaWith(tables = mapOf("orders" to orders, "users" to users))
        val desired = schemaWith(tables = mapOf("orders" to orders.copy(constraints = emptyList())))
        // The diff: remove users entirely + drop the FK constraint on orders.
        val diff = SchemaDiff(
            tablesRemoved = listOf(NamedTable("users", users)),
            tablesChanged = listOf(
                TableDiff(
                    name = "orders",
                    constraintsRemoved = orders.constraints,
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)

        val dropUsers = result.operations.filterIsInstance<DiffOperation.DropTable>()
            .single { it.objectRef.rootName == "users" }
        val dropFk = result.operations.filterIsInstance<DiffOperation.DropConstraint>()
            .single { it.constraint.name == "fk_orders_users" }
        dropUsers.dependencies shouldContain dropFk.id
        // Order: drop fk before drop users.
        result.operations.indexOf(dropFk) shouldBe (result.operations.indexOf(dropUsers) - 1)
    }

    test("CreateView depends on CreateTable of declared dependencies.tables") {
        val users = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        val view = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", users)),
            viewsAdded = listOf(NamedView("v_users", view)),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)

        val createUsers = result.operations.filterIsInstance<DiffOperation.CreateTable>().single()
        val createView = result.operations.filterIsInstance<DiffOperation.CreateView>().single()
        createView.dependencies shouldContain createUsers.id
        result.operations.indexOf(createUsers) shouldBe (result.operations.indexOf(createView) - 1)
    }

    test("Phase order is the tie-breaker when no dependencies exist") {
        // Two unrelated added tables and an added custom type.
        val customType = dev.dmigrate.core.model.CustomTypeDefinition(
            kind = dev.dmigrate.core.model.CustomTypeKind.ENUM,
            values = listOf("a"),
        )
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("t1", TableDefinition()), NamedTable("t2", TableDefinition())),
            customTypesAdded = listOf(dev.dmigrate.core.diff.NamedCustomType("status_t", customType)),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)

        // CUSTOM_TYPE phase (TYPES) precedes TABLES phase.
        val customTypeOp = result.operations.filterIsInstance<DiffOperation.CreateCustomType>().single()
        val firstTable = result.operations.filterIsInstance<DiffOperation.CreateTable>().first()
        result.operations.indexOf(customTypeOp) shouldBe 0
        result.operations.indexOf(firstTable) shouldBe 1
    }

    test("primaryKey change yields DropPrimaryKey + AddPrimaryKey, both in CONSTRAINTS phase") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "orders",
                    primaryKey = ValueChange(before = listOf("id"), after = listOf("id", "tenant_id")),
                ),
            ),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)
        result.operations.filterIsInstance<DiffOperation.DropPrimaryKey>().single().columns shouldBe listOf("id")
        result.operations.filterIsInstance<DiffOperation.AddPrimaryKey>().single().columns shouldBe
            listOf("id", "tenant_id")
        result.operations.all { it.phase == DiffPhase.CONSTRAINTS } shouldBe true
    }

    test("constraintsChanged is modelled as Drop + Add of the new definition") {
        val before = ConstraintDefinition(name = "uq_email", type = ConstraintType.UNIQUE, columns = listOf("email"))
        val after = ConstraintDefinition(
            name = "uq_email_tenant",
            type = ConstraintType.UNIQUE,
            columns = listOf("email", "tenant_id"),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    constraintsChanged = listOf(ValueChange(before, after)),
                ),
            ),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)
        result.operations.filterIsInstance<DiffOperation.DropConstraint>()
            .single().constraint.name shouldBe "uq_email"
        result.operations.filterIsInstance<DiffOperation.AddConstraint>()
            .single().constraint.name shouldBe "uq_email_tenant"
    }

    test("column change with type+required+default yields three Alter operations") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "email",
                            type = ValueChange(NeutralType.Text(), NeutralType.Text(maxLength = 254)),
                            required = ValueChange(false, true),
                            default = ValueChange(
                                null,
                                dev.dmigrate.core.model.DefaultValue.StringLiteral(""),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)
        result.operations.filterIsInstance<DiffOperation.AlterColumnType>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.AlterColumnNullability>().size shouldBe 1
        result.operations.filterIsInstance<DiffOperation.AlterColumnDefault>().size shouldBe 1
    }

    test("operation IDs are deterministic across two planning runs") {
        val orders = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("orders", orders)))
        val r1 = planner.plan(schemaWith(), schemaWith(), diff)
        val r2 = planner.plan(schemaWith(), schemaWith(), diff)
        r1.operations.map { it.id } shouldBe r2.operations.map { it.id }
    }

    test("isFullyReversible is true when only AUTOMATIC operations are produced") {
        // AddColumn nullable → AUTOMATIC_WITH_DATA_RISK still counts.
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("nick" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)
        result.isFullyReversible shouldBe true
    }

    test("isFullyReversible is false when DropColumn is present (NOT_REVERSIBLE)") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)
        result.isFullyReversible shouldBe false
    }

    test("isFullyReversible is false when MANUAL_REQUIRED operation is present (AlterCustomType)") {
        val customType = dev.dmigrate.core.model.CustomTypeDefinition(
            kind = dev.dmigrate.core.model.CustomTypeKind.ENUM,
            values = listOf("a"),
        )
        val current = schemaWith().copy(customTypes = mapOf("status_t" to customType))
        val desired = schemaWith().copy(customTypes = mapOf("status_t" to customType.copy(values = listOf("a", "b"))))
        val diff = SchemaDiff(
            customTypesChanged = listOf(
                dev.dmigrate.core.diff.CustomTypeDiff(
                    name = "status_t",
                    values = ValueChange(listOf("a"), listOf("a", "b")),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        result.isFullyReversible shouldBe false
    }

    test("FK on a non-blocked table referencing a blocked table emits FK_TO_BLOCKED_TABLE") {
        // §F.5 Sub-Slice A: the planner-level block now triggers only
        // for cross-table CHECK heuristic hits. To still exercise the
        // FK_TO_BLOCKED_TABLE diagnostic, the blocked side carries a
        // CHECK with a SELECT-style subquery.
        val tableWithCrossTableCheck = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
            constraints = listOf(
                ConstraintDefinition(
                    name = "chk_x",
                    type = ConstraintType.CHECK,
                    expression = "id IN (SELECT user_id FROM allowed_users)",
                ),
            ),
        )
        val orders = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "user_id" to ColumnDefinition(
                    NeutralType.Integer,
                    references = ReferenceDefinition(table = "users", column = "id"),
                ),
            ),
        )
        val current = schemaWith()
        val desired = schemaWith(tables = mapOf("users" to tableWithCrossTableCheck, "orders" to orders))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", tableWithCrossTableCheck), NamedTable("orders", orders)),
        )
        val result = planner.plan(current, desired, diff)
        val codes = result.diagnostics.map { it.code }.toSet()
        codes shouldContain "CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED"
        codes shouldContain "FK_TO_BLOCKED_TABLE"
        val createOrders = result.operations.filterIsInstance<DiffOperation.CreateTable>()
            .single { it.objectRef.rootName == "orders" }
        val fkDiag = result.diagnostics.single { it.code == "FK_TO_BLOCKED_TABLE" }
        fkDiag.operationId shouldBe createOrders.id
        fkDiag.severity shouldBe dev.dmigrate.core.diff.migration.DiffDiagnostic.Severity.BLOCKER
    }

    test("anonymous indices on the same columns but different where get distinct IDs") {
        val idxA = dev.dmigrate.core.model.IndexDefinition(
            name = null,
            columns = listOf(dev.dmigrate.core.model.IndexColumn("c1")),
            type = dev.dmigrate.core.model.IndexType.BTREE,
            where = "active = true",
        )
        val idxB = dev.dmigrate.core.model.IndexDefinition(
            name = null,
            columns = listOf(dev.dmigrate.core.model.IndexColumn("c1")),
            type = dev.dmigrate.core.model.IndexType.BTREE,
            where = "active = false",
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idxA, idxB))),
        )
        val result = planner.plan(schemaWith(), schemaWith(), diff)
        val ids = result.operations.filterIsInstance<DiffOperation.AddIndex>().map { it.id }
        ids.size shouldBe 2
        ids.distinct().size shouldBe 2
    }

    // ── §F.6.b View-Dependency-Block ────────────────────────────────────
    //
    // Per Plan §6.3 column-altering operations on a table referenced by
    // a view that lacks column-level dependency info MUST block. The
    // check is dialect-agnostic at the planner layer; PostgreSQL
    // adapters supplying column-level deps via `pg_depend` will simply
    // never trigger it, MySQL adapters (no `VIEW_COLUMN_USAGE`) trip it
    // unless the user supplied an explicit schema file with column
    // deps.

    test("F.6.b — DropColumn under a view with table-level-only deps blocks with VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS") {
        val users = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "legacy" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val view = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(tables = listOf("users")), // table-level only
        )
        val current = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to view),
        )
        val desired = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("users" to users.copy(columns = users.columns - "legacy")),
            views = mapOf("v_users" to view),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        val diag = result.diagnostics.single { it.code == "VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS" }
        diag.severity shouldBe DiffDiagnostic.Severity.BLOCKER
        diag.operationId shouldBe result.operations.filterIsInstance<DiffOperation.DropColumn>().single().id
        diag.message shouldContainStr "v_users"
        diag.message shouldContainStr "users.legacy"
        result.hasBlockers shouldBe true
    }

    test("F.6.b — AlterColumnType under a view with table-level-only deps blocks") {
        val users = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "name" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val view = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val schema = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to view),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "name",
                            type = ValueChange(NeutralType.Text(), NeutralType.Text(maxLength = 100)),
                        ),
                    ),
                ),
            ),
        )
        val result = planner.plan(schema, schema, diff)
        val diag = result.diagnostics.single { it.code == "VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS" }
        diag.operationId shouldBe result.operations.filterIsInstance<DiffOperation.AlterColumnType>().single().id
    }

    test("F.6.b — AlterColumnNullability under a view with table-level-only deps blocks") {
        val users = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "email" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val view = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val schema = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to view),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "email",
                            required = ValueChange(false, true),
                        ),
                    ),
                ),
            ),
        )
        val result = planner.plan(schema, schema, diff)
        val diag = result.diagnostics.single { it.code == "VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS" }
        diag.operationId shouldBe result.operations.filterIsInstance<DiffOperation.AlterColumnNullability>().single().id
    }

    test("F.6.b — DropColumn under a view with column-level deps does NOT block (PostgreSQL pg_depend path)") {
        val users = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "legacy" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val view = ViewDefinition(
            query = "SELECT id FROM users",
            // Column-level deps present — adapter (e.g. PostgreSQL via
            // pg_depend) supplied them. Even though `legacy` is dropped,
            // the planner trusts the column-level signal and does NOT
            // emit the §F.6.b block. (Other layers may still block via
            // explicit dep on `legacy` — out of scope here.)
            dependencies = DependencyInfo(
                tables = listOf("users"),
                columns = mapOf("users" to listOf("id")),
            ),
        )
        val current = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to view),
        )
        val desired = current.copy(
            tables = mapOf("users" to users.copy(columns = users.columns - "legacy")),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        result.diagnostics.any { it.code == "VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS" } shouldBe false
    }

    test("F.6.b — DropColumn on a table with NO views does not block") {
        val users = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "legacy" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val current = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("users" to users),
        )
        val desired = current.copy(
            tables = mapOf("users" to users.copy(columns = users.columns - "legacy")),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        result.diagnostics.any { it.code == "VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS" } shouldBe false
    }

    test("F.6.b — AddColumn (non-altering) under a table-level-only view does NOT block") {
        // Adding a column cannot break a view that doesn't reference
        // it — only DropColumn/AlterColumnType/AlterColumnNullability
        // do. Pin so a future planner refactor doesn't accidentally
        // widen the block to AddColumn.
        val users = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
        )
        val view = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val current = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to view),
        )
        val desired = current.copy(
            tables = mapOf(
                "users" to users.copy(
                    columns = users.columns + ("nick" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("nick" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
        )
        val result = planner.plan(current, desired, diff)
        result.diagnostics.any { it.code == "VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS" } shouldBe false
    }

    test("F.6.b — view in CURRENT only (slated for DropView) still triggers the block") {
        // Pre-execute the view exists in the live DB; the column-
        // altering op runs before any DropView could remove it.
        val users = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "legacy" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val view = ViewDefinition(
            query = "SELECT id FROM users",
            dependencies = DependencyInfo(tables = listOf("users")),
        )
        val current = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("users" to users),
            views = mapOf("v_users" to view),
        )
        // desired drops both the legacy column AND the view
        val desired = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("users" to users.copy(columns = users.columns - "legacy")),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text())),
                ),
            ),
            viewsRemoved = listOf(NamedView("v_users", view)),
        )
        val result = planner.plan(current, desired, diff)
        result.diagnostics.any { it.code == "VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS" } shouldBe true
    }

    test("operation IDs are stable across construction-order variants") {
        // Two semantically equal but differently-constructed inputs.
        val colsA = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Identifier()),
            "name" to ColumnDefinition(NeutralType.Text()),
        )
        val colsB = linkedMapOf<String, ColumnDefinition>().apply {
            put("name", ColumnDefinition(NeutralType.Text()))
            put("id", ColumnDefinition(NeutralType.Identifier()))
        }
        val tableA = TableDefinition(columns = colsA)
        val tableB = TableDefinition(columns = colsB)
        val diffA = SchemaDiff(tablesAdded = listOf(NamedTable("users", tableA)))
        val diffB = SchemaDiff(tablesAdded = listOf(NamedTable("users", tableB)))
        val rA = planner.plan(schemaWith(), schemaWith(), diffA)
        val rB = planner.plan(schemaWith(), schemaWith(), diffB)
        rA.operations.map { it.id } shouldBe rB.operations.map { it.id }
    }
})
