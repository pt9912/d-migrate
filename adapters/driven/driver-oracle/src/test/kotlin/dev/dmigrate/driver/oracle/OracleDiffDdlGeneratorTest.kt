package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
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
 * and indices (5b) live in [OracleDiffObjectOpsTest], views and custom types
 * (5c) in [OracleDiffViewOpsTest] / [OracleDiffCustomTypeOpsTest]; the
 * families still ahead (materialized views Slice 10, partitioning Slice 7,
 * routines and triggers Slice 9) are asserted UNSUPPORTED here; sequences
 * (5d) live in [OracleDiffSequenceOpsTest].
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

    // ── Identity hinzufuegen: Oracle kann das nur ueber einen Neubau ──
    //
    // `MODIFY <col> GENERATED ALWAYS AS IDENTITY` antwortet auf jeder Spalte,
    // die nicht bereits Identity traegt, mit ORA-30673 — an leerer Tabelle,
    // gefuellter Tabelle und Spalte mit NULL-Wert gleichermassen gemessen.

    val identityDiff = SchemaDiff(
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

    fun usersSchema(idType: NeutralType) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf(
            "users" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(idType, ordinal = 1),
                    "email" to ColumnDefinition(NeutralType.Text(maxLength = 100), ordinal = 2),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    test("AlterColumnType: adding identity rebuilds the table") {
        val up = planAndUp(
            identityDiff,
            current = usersSchema(NeutralType.BigInteger),
            desired = usersSchema(NeutralType.Identifier(autoIncrement = true)),
        )

        val sqls = up.statements.map { it.sql }
        // Anlegen, kopieren, alte loeschen, umbenennen — und erst danach die
        // Namen, die schema-global sind.
        sqls[0] shouldContain "CREATE TABLE"
        sqls[0] shouldContain "GENERATED BY DEFAULT AS IDENTITY"
        sqls[1] shouldContain "INSERT INTO"
        sqls[1] shouldContain "SELECT \"id\", \"email\" FROM \"users\""
        sqls[2] shouldBe "DROP TABLE \"users\" CASCADE CONSTRAINTS PURGE;"
        sqls[3] shouldContain "RENAME TO \"users\""
        sqls[4] shouldBe "ALTER TABLE \"users\" MODIFY \"id\" GENERATED ALWAYS AS IDENTITY " +
            "(START WITH LIMIT VALUE);"
        sqls[5] shouldBe "ALTER TABLE \"users\" ADD CONSTRAINT \"pk_users\" PRIMARY KEY (\"id\");"
        up.diagnostics.any { it.code == "ORACLE_TABLE_REBUILT_FOR_IDENTITY" } shouldBe true
        up.primaryBlockedReason shouldBe null
    }

    test("the rebuild needs both definitions and says so when it has only one") {
        // Ohne Schemata kann der Neubau die Zieltabelle nicht bauen — er raet
        // nicht, sondern blockt benannt.
        val up = planAndUp(identityDiff)

        up.statements.shouldBeEmpty()
        up.diagnostics.any { it.code == "ORACLE_TABLE_NOT_IN_SCHEMA" } shouldBe true
    }

    test("the reverse direction still drops identity in place — no rebuild needed") {
        // Die Gegenrichtung ist der Entfernen-Fall; Oracle kann ihn per ALTER.
        val down = planAndDown(
            identityDiff,
            current = usersSchema(NeutralType.BigInteger),
            desired = usersSchema(NeutralType.Identifier(autoIncrement = true)),
        )
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

    // Der Cross-Dialekt-Matrix-Sweep traegt fuer Oracle einen Carve-out
    // auf der E.2-Zelle und verweist als Deckung hierher -- die Zusage
    // muss belegt sein, nicht nur behauptet.
    test("a trigger renders as PL/SQL, without a trailing semicolon after END;") {
        val op = DiffOperation.CreateTrigger(
            id = "create-trigger",
            objectRef = DiffObjectRef(DiffObjectType.TRIGGER, listOf("orders::trg_audit")),
            trigger = dev.dmigrate.core.model.TriggerDefinition(
                table = "orders",
                timing = dev.dmigrate.core.model.TriggerTiming.BEFORE,
                events = setOf(dev.dmigrate.core.model.TriggerEvent.INSERT),
                body = "BEGIN NULL; END;",
            ),
        )
        val plan = DiffResult(
            current = DiffEndpoint(schemaName = "App"),
            desired = DiffEndpoint(schemaName = "App"),
            schemaDiff = SchemaDiff(),
            operations = listOf(op),
        )
        val r = gen.generateUp(plan, DdlGenerationOptions())
        r.isBlocked shouldBe false
        val sql = r.statements.single().sql
        sql shouldContain "CREATE OR REPLACE TRIGGER \"trg_audit\""
        sql shouldContain "BEFORE INSERT ON \"orders\""
        // Ein weiteres `;` hinter END; laesst execute() gelingen und die
        // Routine INVALID zurueck -- live gegen JDBC gemessen.
        sql.endsWith("END;") shouldBe true
    }

    test("a materialized view renders natively in the diff path") {
        val op = DiffOperation.CreateMaterializedView(
            id = "create-mv",
            objectRef = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf("mv_x")),
            view = ViewDefinition(query = "SELECT 1 FROM dual", materialized = true),
        )
        val plan = DiffResult(
            current = DiffEndpoint(schemaName = "App"),
            desired = DiffEndpoint(schemaName = "App"),
            schemaDiff = SchemaDiff(),
            operations = listOf(op),
        )
        val r = gen.generateUp(plan, DdlGenerationOptions())
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContain "CREATE MATERIALIZED VIEW \"mv_x\""
    }

    test("Oracle DDL carries IMPLICIT_COMMIT (no cross-statement rollback), unlike PostgreSQL/MSSQL") {
        val t = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)))
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", t))))
        r.statements.single().hints.transactionBehavior shouldBe TransactionBehavior.IMPLICIT_COMMIT
    }

    // ── Wo Generate weich rendert und Migrate blocken muss ─────────
    //
    // Der Generate-Pfad darf eine Tabelle notfalls anders anlegen, als das
    // Modell sie beschreibt, und das melden. Auf dem Migrate-Pfad waere
    // dieselbe Nachgiebigkeit eine stille Aenderung an einer bestehenden
    // Datenbank -- dort wird stattdessen benannt geblockt.

    test("CreateTable renders the partitioning Oracle can express") {
        // Seit Slice 7 ist Partitionierung kein Pauschal-Blocker mehr. Der
        // Diff-Pfad nutzt denselben Builder wie der Generate-Pfad -- ohne
        // diese Zusicherung koennte er still auf eine flache Tabelle
        // zurueckfallen, waehrend `schema generate` partitioniert.
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier()),
                "st" to ColumnDefinition(type = NeutralType.Text(maxLength = 10)),
            ),
            partitioning = dev.dmigrate.core.model.PartitionConfig(
                type = dev.dmigrate.core.model.PartitionType.LIST,
                key = listOf("st"),
                partitions = listOf(
                    dev.dmigrate.core.model.PartitionDefinition(name = "l_a", values = listOf("'A'")),
                    dev.dmigrate.core.model.PartitionDefinition(name = "l_rest", isDefault = true),
                ),
            ),
        )
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("events", table))))
        r.blockers.shouldBeEmpty()
        val sql = r.statements.single().sql
        sql shouldContain "PARTITION BY LIST (\"st\")"
        sql shouldContain "PARTITION \"l_rest\" VALUES (DEFAULT)"
    }

    test("CreateTable with an unrenderable index blocks instead of dropping it silently") {
        // Ohne den Blocker stufte `carryOverNotes` das ACTION_REQUIRED zu
        // einer Warnung herab, die Tabelle entstuende ohne den Index -- und
        // Spec wie Handbuch sagen Abbruch zu.
        val table = TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
                "b" to ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
            ),
            indices = listOf(
                IndexDefinition(
                    name = "ft_multi",
                    columns = listOf(IndexColumn("a"), IndexColumn("b")),
                    type = IndexType.FULLTEXT,
                ),
            ),
        )
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("docs", table))))
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_INDEX_NOT_RENDERABLE" } shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
    }

    test("CreateTable with partitioning Oracle cannot express blocks instead of creating a flat table") {
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier())),
            primaryKey = listOf("id"),
            partitioning = dev.dmigrate.core.model.PartitionConfig(
                type = dev.dmigrate.core.model.PartitionType.RANGE,
                key = listOf("id"),
            ),
        )
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("events", table))))
        r.statements.shouldBeEmpty()
        r.blockers.single().reason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.diagnostics.single().code shouldBe "ORACLE_PARTITIONING_UNSUPPORTED"
    }

    test("a geometry column renders as SDO_GEOMETRY on CreateTable and AddColumn") {
        val geo = ColumnDefinition(type = NeutralType.Geometry())
        val created = TableDefinition(columns = mapOf("shape" to geo))
        val onCreate = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("places", created))))
        onCreate.diagnostics.shouldBeEmpty()
        onCreate.statements.single().sql shouldContain "\"shape\" SDO_GEOMETRY"

        val onAdd = planAndUp(
            SchemaDiff(
                tablesChanged = listOf(TableDiff(name = "places", columnsAdded = mapOf("shape" to geo))),
            ),
        )
        onAdd.diagnostics.shouldBeEmpty()
        onAdd.statements.single().sql shouldBe "ALTER TABLE \"places\" ADD (\"shape\" SDO_GEOMETRY);"
    }

    /**
     * Der Generate-Pfad legt den raeumlichen Index in die POST_DATA-Phase,
     * weil Oracle sein Koordinatensystem aus den Zeilen ableitet. Der
     * Migrate-Pfad hat keine Datenphase; eine Tabelle, die dieselbe Migration
     * gerade anlegt, ist garantiert leer, und der Index scheiterte mit
     * ORA-13199 -- bei jedem Folgelauf erneut.
     */
    test("CreateTable with a spatial index blocks instead of emitting DDL that always fails") {
        val places = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "geom" to ColumnDefinition(NeutralType.Geometry()),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(name = "sx_places_geom", columns = listOf(IndexColumn("geom")), type = IndexType.SPATIAL),
            ),
        )
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("places", places))))
        r.statements.shouldBeEmpty()
        r.diagnostics.single().code shouldBe "ORACLE_SPATIAL_INDEX_NEEDS_ROWS"
        r.isBlocked shouldBe true
    }

    /**
     * Oracle verweigert den Typwechsel einer Spalte in einen Objekttyp und
     * aus ihm heraus (ORA-22858/ORA-22859), auch auf leerer Tabelle -- ein
     * `MODIFY` waere DDL, die in jedem Fall scheitert.
     */
    test("changing a column's type into or out of geometry stays blocked") {
        fun alter(before: NeutralType, after: NeutralType) = planAndUp(
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "places",
                        columnsChanged = listOf(ColumnDiff(name = "shape", type = ValueChange(before, after))),
                    ),
                ),
            ),
        )

        val intoGeometry = alter(NeutralType.Text(maxLength = 40), NeutralType.Geometry())
        intoGeometry.statements.shouldBeEmpty()
        intoGeometry.diagnostics.single().code shouldBe "ORACLE_GEOMETRY_TYPE_CHANGE_UNSUPPORTED"

        val outOfGeometry = alter(NeutralType.Geometry(), NeutralType.Text(maxLength = 40))
        outOfGeometry.statements.shouldBeEmpty()
        outOfGeometry.diagnostics.single().code shouldBe "ORACLE_GEOMETRY_TYPE_CHANGE_UNSUPPORTED"
    }
})
