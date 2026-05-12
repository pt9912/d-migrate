package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.ExtensionAvailabilityDeclaration
import dev.dmigrate.driver.ExtensionAvailabilityStatus
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

class SqliteDiffDdlGeneratorTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planAndUp(
        diff: SchemaDiff,
        current: SchemaDefinition = emptySchema(),
        desired: SchemaDefinition = emptySchema(),
        options: DdlGenerationOptions = DdlGenerationOptions(),
    ) = gen.generateUp(planner.plan(current, desired, diff), options)

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

    test("§C.1: SQLite geometry CreateTable blocks when SpatiaLite availability is unknown") {
        val t = TableDefinition(
            columns = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
        )
        val r = planAndUp(
            SchemaDiff(tablesAdded = listOf(NamedTable("places", t))),
            options = DdlGenerationOptions(spatialProfile = SpatialProfile.SPATIALITE),
        )

        r.statements.shouldBeEmpty()
        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.single { it.code == "EXTENSION_DEPENDENCY_UNKNOWN" }
            .message shouldContainStr "spatialite"
        r.extensionDependencies.single().extension shouldBe "spatialite"
        r.extensionDependencies.single().status shouldBe ExtensionAvailabilityStatus.UNKNOWN
    }

    test("§C.1: SQLite geometry AddColumn renders only when SpatiaLite is verified") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "places",
                    columnsAdded = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
                ),
            ),
        )
        val r = planAndUp(
            diff,
            options = DdlGenerationOptions(
                spatialProfile = SpatialProfile.SPATIALITE,
                extensionAvailability = listOf(
                    ExtensionAvailabilityDeclaration(
                        dialect = "sqlite",
                        extension = "spatialite",
                        status = ExtensionAvailabilityStatus.VERIFIED_PRESENT,
                    ),
                ),
            ),
        )

        r.isBlocked shouldBe false
        r.statements.single().sql shouldContainStr
            "AddGeometryColumn('places', 'shape', 0, 'GEOMETRY', 'XY')"
        r.diagnostics.single { it.code == "EXTENSION_DEPENDENCY_VERIFIED" }
            .message shouldContainStr "spatialite"
        r.extensionDependencies.single().status shouldBe ExtensionAvailabilityStatus.VERIFIED_PRESENT
    }

    test("§C.2: SQLite geometry AddColumn blocks without SpatiaLite profile") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "places",
                    columnsAdded = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
                ),
            ),
        )
        val r = planAndUp(
            diff,
            options = DdlGenerationOptions(
                extensionAvailability = listOf(
                    ExtensionAvailabilityDeclaration(
                        dialect = "sqlite",
                        extension = "spatialite",
                        status = ExtensionAvailabilityStatus.VERIFIED_PRESENT,
                    ),
                ),
            ),
        )

        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.single { it.code == "SPATIAL_PROFILE_REQUIRED" }
            .message shouldContainStr "SPATIALITE"
    }

    test("§C.2: SQLite geometry CreateTable uses SpatiaLite metadata functions") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer),
                "shape" to ColumnDefinition(NeutralType.Geometry()),
            ),
        )
        val r = planAndUp(
            SchemaDiff(tablesAdded = listOf(NamedTable("places", table))),
            options = DdlGenerationOptions(
                spatialProfile = SpatialProfile.SPATIALITE,
                extensionAvailability = listOf(
                    ExtensionAvailabilityDeclaration(
                        dialect = "sqlite",
                        extension = "spatialite",
                        status = ExtensionAvailabilityStatus.VERIFIED_PRESENT,
                    ),
                ),
            ),
        )

        r.isBlocked shouldBe false
        r.statements.size shouldBe 2
        r.statements[0].sql shouldContainStr "CREATE TABLE \"places\""
        r.statements[0].sql.contains("\"shape\"") shouldBe false
        r.statements[1].sql shouldContainStr "AddGeometryColumn('places', 'shape', 0, 'GEOMETRY', 'XY')"
    }

    test("§C.2: SQLite index on geometry column is blocked") {
        val before = TableDefinition(
            columns = mapOf("shape" to ColumnDefinition(NeutralType.Geometry())),
        )
        val index = IndexDefinition(name = "idx_places_shape", columns = listOf(IndexColumn("shape")))
        val after = before.copy(indices = listOf(index))
        val current = emptySchema().copy(tables = mapOf("places" to before))
        val desired = emptySchema().copy(tables = mapOf("places" to after))
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "places", indicesAdded = listOf(index))))
        val r = planAndUp(diff, current = current, desired = desired)

        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.single { it.code == "SPATIAL_INDEX_UNSUPPORTED" }
            .message shouldContainStr "spatial-index metadata"
    }

    test("DropColumn renders DROP COLUMN") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text()))),
            ),
        )
        planAndUp(diff).statements.single().sql shouldBe "ALTER TABLE \"u\" DROP COLUMN \"legacy\";"
    }

    test("Rebuild-required ops on a table not present in current/desired schemas yield SQLITE_REBUILD_MISSING_SOURCES") {
        // Rebuild ops require both current+desired schemas in DiffResult.
        // When the planner is fed empty schemas and a synthetic diff
        // (which is degenerate since tables don't exist), the rebuild
        // renderer cannot reconstruct the target table → blocker.
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
        r.diagnostics.any { it.code == "SQLITE_REBUILD_MISSING_SOURCES" } shouldBe true
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

    test("materialized view operations are blocked before SQL render") {
        val view = ViewDefinition(query = "SELECT 1", materialized = true)
        val create = planAndUp(SchemaDiff(viewsAdded = listOf(dev.dmigrate.core.diff.NamedView("mv", view))))

        create.statements.shouldBeEmpty()
        create.isBlocked shouldBe true
        create.blockers.single().reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        val diagnostic = create.diagnostics.single { it.code == "MATERIALIZED_VIEW_DIFF_UNSUPPORTED" }
        diagnostic.message shouldContainStr "mv"
        diagnostic.message shouldContainStr "sqlite"
        diagnostic.message shouldContainStr "materialized=true"

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
        replace.diagnostics.any { it.code == "MATERIALIZED_VIEW_DIFF_UNSUPPORTED" } shouldBe true

        val drop = planAndUp(SchemaDiff(viewsRemoved = listOf(dev.dmigrate.core.diff.NamedView("mv", view))))
        drop.statements.shouldBeEmpty()
        drop.diagnostics.any { it.code == "MATERIALIZED_VIEW_DIFF_UNSUPPORTED" } shouldBe true
    }

    test("rebuild does not recreate dependent materialized view as a regular view") {
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "name" to ColumnDefinition(NeutralType.Text()),
            ),
        )
        val after = before.copy(
            columns = before.columns + ("name" to ColumnDefinition(NeutralType.Text(maxLength = 100))),
        )
        val view = ViewDefinition(
            query = "SELECT id FROM users",
            materialized = true,
            dependencies = DependencyInfo(
                tables = listOf("users"),
                columns = mapOf("users" to listOf("id")),
            ),
        )
        val current = emptySchema().copy(tables = mapOf("users" to before), views = mapOf("mv_users" to view))
        val desired = emptySchema().copy(tables = mapOf("users" to after), views = mapOf("mv_users" to view))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "name",
                            type = ValueChange(NeutralType.Text(), NeutralType.Text(maxLength = 100)),
                        ),
                    ),
                ),
            ),
        )

        val result = planAndUp(diff, current, desired)

        result.statements.shouldBeEmpty()
        result.isBlocked shouldBe true
        val diagnostic = result.diagnostics.single { it.code == "MATERIALIZED_VIEW_DIFF_UNSUPPORTED" }
        diagnostic.message shouldContainStr "mv_users"
        diagnostic.message shouldContainStr "sqlite"
        diagnostic.message shouldContainStr "materialized=true"
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

    test("Rebuild bucket with currentSchema=null yields SQLITE_REBUILD_MISSING_SOURCES") {
        // Build a real DiffResult, then strip the source schemas to simulate a
        // deserialised-from-artefact path.
        val before = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
        )
        val after = before.copy(columns = mapOf("id" to ColumnDefinition(NeutralType.BigInteger)))
        val current = SchemaDefinition(name = "App", version = "1", tables = mapOf("u" to before))
        val desired = SchemaDefinition(name = "App", version = "1", tables = mapOf("u" to after))
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
        val plan = DiffPlanner().plan(current, desired, diff)
            .copy(currentSchema = null, desiredSchema = null)
        val r = gen.generateUp(plan, DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "SQLITE_REBUILD_MISSING_SOURCES" } shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }
})
