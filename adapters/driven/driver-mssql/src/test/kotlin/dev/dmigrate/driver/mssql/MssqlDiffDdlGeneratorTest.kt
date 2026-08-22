package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.TransactionBehavior
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Sub-Slice 5a: Tabellen-, Spalten- und Primaerschluessel-Operationen.
 *
 * Der Schwerpunkt liegt auf den drei T-SQL-Eigenheiten, an denen ein aus
 * PostgreSQL abgeschriebener Renderer falsch waere: der Default-Constraint-
 * Dreischritt, die Voll-Neudeklaration bei `ALTER COLUMN` und `sp_rename`.
 */
class MssqlDiffDdlGeneratorTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    fun schema(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables.toMap())

    fun plan(
        diff: SchemaDiff,
        current: SchemaDefinition = schema(),
        desired: SchemaDefinition = schema(),
    ): DiffResult = planner.plan(current, desired, diff)

    fun up(
        diff: SchemaDiff,
        current: SchemaDefinition = schema(),
        desired: SchemaDefinition = schema(),
    ) = gen.generateUp(plan(diff, current, desired), DdlGenerationOptions())

    fun down(
        diff: SchemaDiff,
        current: SchemaDefinition = schema(),
        desired: SchemaDefinition = schema(),
    ) = gen.generateDown(plan(diff, current, desired), DdlGenerationOptions())

    test("dialect is MSSQL and an empty diff renders nothing") {
        gen.dialect.name shouldBe "MSSQL"
        val r = up(SchemaDiff())
        r.statements.shouldBeEmpty()
        r.blockers.shouldBeEmpty()
    }

    test("CreateTable renders bracket quoting, a named PK and a named default constraint") {
        val users = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true), required = true),
                "nick" to ColumnDefinition(NeutralType.Text(50), default = DefaultValue.StringLiteral("anon")),
            ),
            primaryKey = listOf("id"),
        )
        val sqlText = up(
            SchemaDiff(tablesAdded = listOf(NamedTable("users", users))),
            desired = schema("users" to users),
        ).statements.single().sql
        sqlText shouldContainStr "CREATE TABLE [users] ("
        sqlText shouldContainStr "[id] INT IDENTITY(1,1) NOT NULL"
        // Der Default MUSS benannt sein — anonym koennte ihn kein spaeterer
        // ALTER COLUMN mehr loesen.
        sqlText shouldContainStr "CONSTRAINT [df_users_nick] DEFAULT N'anon'"
        sqlText shouldContainStr "CONSTRAINT [pk_users] PRIMARY KEY ([id])"
    }

    test("DropTable renders DROP TABLE and blocks the down direction") {
        val diff = SchemaDiff(tablesRemoved = listOf(NamedTable("legacy", TableDefinition())))
        up(diff).statements.single().sql shouldBe "DROP TABLE [legacy];"
        val rDown = down(diff)
        rDown.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("AddColumn uses T-SQL ADD (not ADD COLUMN); down drops the default first") {
        val nick = ColumnDefinition(NeutralType.Text(20), default = DefaultValue.StringLiteral("x"))
        val withNick = schema("users" to TableDefinition(columns = mapOf("nick" to nick)))
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", columnsAdded = mapOf("nick" to nick))),
        )
        val upSql = up(diff, desired = withNick).statements.single().sql
        upSql shouldContainStr "ALTER TABLE [users] ADD [nick] NVARCHAR(20)"
        upSql.contains("ADD COLUMN") shouldBe false

        // Rueckwaerts: erst der Default-Constraint, dann die Spalte — sonst
        // scheitert DROP COLUMN an der Abhaengigkeit.
        val downSqls = down(diff, current = withNick, desired = withNick).statements.map { it.sql }
        downSqls[0] shouldContainStr "FROM sys.default_constraints"
        downSqls[0] shouldContainStr "QUOTENAME(@df)"
        downSqls[1] shouldBe "ALTER TABLE [users] DROP COLUMN [nick];"
    }

    test("DropColumn drops the default constraint before the column") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", columnsRemoved = mapOf("nick" to ColumnDefinition(NeutralType.Text()))),
            ),
        )
        val sqls = up(diff).statements.map { it.sql }
        sqls[0] shouldContainStr "FROM sys.default_constraints"
        sqls[1] shouldBe "ALTER TABLE [users] DROP COLUMN [nick];"
    }

    test("AlterColumnType renders the three-step and keeps NOT NULL") {
        // Der eigentliche Regressionsschutz: ALTER COLUMN ist in T-SQL eine
        // Voll-Neudeklaration. Ohne das NOT NULL aus dem Soll-Schema waere die
        // Spalte nach der Migration still nullable.
        val desired = schema(
            "users" to TableDefinition(
                columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(100), required = true)),
            ),
        )
        val current = schema(
            "users" to TableDefinition(
                columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(50), required = true)),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(name = "nick", type = ValueChange(NeutralType.Text(50), NeutralType.Text(100))),
                    ),
                ),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        sqls[0] shouldContainStr "FROM sys.default_constraints"
        sqls[1] shouldBe "ALTER TABLE [users] ALTER COLUMN [nick] NVARCHAR(100) NOT NULL;"
    }

    test("AlterColumnType blocks instead of guessing when the column is not in the schema") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(name = "nick", type = ValueChange(NeutralType.Text(50), NeutralType.Text(100))),
                    ),
                ),
            ),
        )
        val r = up(diff)
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.map { it.code } shouldContain "MSSQL_COLUMN_NOT_IN_SCHEMA"
    }

    test("AlterColumnNullability carries the column type from the schema") {
        val desired = schema(
            "users" to TableDefinition(
                columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(50), required = true)),
            ),
        )
        val current = schema(
            "users" to TableDefinition(
                columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(50), required = false)),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(ColumnDiff(name = "nick", required = ValueChange(false, true))),
                ),
            ),
        )
        up(diff, current, desired).statements.map { it.sql } shouldContain
            "ALTER TABLE [users] ALTER COLUMN [nick] NVARCHAR(50) NOT NULL;"
        // Abwaerts gilt das Ist-Schema — dort ist die Spalte nullable.
        down(diff, current, desired).statements.map { it.sql } shouldContain
            "ALTER TABLE [users] ALTER COLUMN [nick] NVARCHAR(50) NULL;"
    }

    test("AlterColumnDefault drops the old constraint and adds the new one FOR the column") {
        val desired = schema(
            "users" to TableDefinition(
                columns = mapOf(
                    "nick" to ColumnDefinition(NeutralType.Text(50), default = DefaultValue.StringLiteral("neu")),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "nick",
                            default = ValueChange(DefaultValue.StringLiteral("alt"), DefaultValue.StringLiteral("neu")),
                        ),
                    ),
                ),
            ),
        )
        val sqls = up(diff, desired = desired).statements.map { it.sql }
        sqls[0] shouldContainStr "FROM sys.default_constraints"
        sqls[1] shouldBe "ALTER TABLE [users] ADD CONSTRAINT [df_users_nick] DEFAULT N'neu' FOR [nick];"
    }

    test("AddPrimaryKey names the constraint; DropPrimaryKey looks the real name up") {
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", primaryKey = ValueChange(emptyList(), listOf("id")))),
        )
        up(diff).statements.single().sql shouldBe
            "ALTER TABLE [users] ADD CONSTRAINT [pk_users] PRIMARY KEY ([id]);"

        val dropDiff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", primaryKey = ValueChange(listOf("id"), emptyList()))),
        )
        // Auch der PK wird nachgeschlagen: ein fremdes Schema nennt ihn nicht pk_users.
        val r = up(dropDiff)
        r.statements.single().sql shouldContainStr "FROM sys.key_constraints"
        r.statements.single().sql shouldContainStr "QUOTENAME(@pk)"
    }

    test("an IDENTITY change is blocked rather than silently dropped") {
        // ALTER COLUMN kann IDENTITY weder setzen noch entfernen; ein blankes
        // ALTER COLUMN wuerde die Identity kommentarlos verlieren.
        val current = schema(
            "users" to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true))),
            ),
        )
        val desired = schema(
            "users" to TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Integer))),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "id",
                            type = ValueChange(NeutralType.Identifier(autoIncrement = true), NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val r = up(diff, current, desired)
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "MSSQL_IDENTITY_CHANGE_NEEDS_REBUILD"
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("an operation of a later sub-slice is blocked with a message naming its owner") {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    indicesAdded = listOf(
                        IndexDefinition(name = "ix_nick", columns = listOf(IndexColumn("nick")), type = IndexType.BTREE),
                    ),
                ),
            ),
        )
        val r = up(diff)
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.diagnostics.single { it.code == "DIALECT_UNSUPPORTED_OPERATION" }
            .message shouldContainStr "sub-slice 5b"
    }

    test("statements declare SQL Server's fully transactional DDL") {
        val r = up(SchemaDiff(tablesRemoved = listOf(NamedTable("legacy", TableDefinition()))))
        r.statements.single().hints.transactionBehavior shouldBe TransactionBehavior.FULLY_TRANSACTIONAL
        r.statements.single().hints.implicitCommitPossible shouldBe false
    }

    test("AlterColumnDefault without a resolvable type blocks cleanly instead of crashing") {
        // Regression: das DROP wurde frueher VOR der Typaufloesung emittiert.
        // Damit lag die Operation in `rendered` UND `skipped`, und
        // MigrationDdlResult erzwingt per require(), dass die Mengen disjunkt
        // sind — der Renderer flog mit IllegalArgumentException statt einen
        // Blocker zu liefern.
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(name = "nick", default = ValueChange(null, DefaultValue.StringLiteral("neu"))),
                    ),
                ),
            ),
        )
        val r = up(diff)
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.operationsRendered.intersect(r.operationsSkipped).shouldBeEmpty()
    }

    test("CreateTable with indices or partitioning blocks instead of losing them silently") {
        val indexed = TableDefinition(
            columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(20))),
            indices = listOf(
                IndexDefinition(name = "ix_nick", columns = listOf(IndexColumn("nick")), type = IndexType.BTREE),
            ),
        )
        val r = up(
            SchemaDiff(tablesAdded = listOf(NamedTable("t", indexed))),
            desired = schema("t" to indexed),
        )
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.diagnostics.single { it.code == "DIALECT_UNSUPPORTED_OPERATION" }.message shouldContainStr "5b"
    }

    test("a column with a dependent UNIQUE or index cannot be altered or dropped yet") {
        // SQL Server weist ALTER/DROP COLUMN mit Msg 5074 auch wegen eines
        // abhaengigen UNIQUE oder Index ab, nicht nur wegen des Defaults.
        val withUnique = schema(
            "users" to TableDefinition(
                columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(50), unique = true)),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(name = "nick", type = ValueChange(NeutralType.Text(50), NeutralType.Text(80))),
                    ),
                ),
            ),
        )
        val r = up(diff, current = withUnique, desired = withUnique)
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "MSSQL_COLUMN_HAS_DEPENDENT_OBJECTS"
    }

    test("a nullability change on an IDENTITY column is blocked (ALTER COLUMN would be Msg 156)") {
        val identity = schema(
            "users" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true), required = true),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(ColumnDiff(name = "id", required = ValueChange(false, true))),
                ),
            ),
        )
        val r = up(diff, current = identity, desired = identity)
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "MSSQL_IDENTITY_CHANGE_NEEDS_REBUILD"
    }

    test("the diff path renders columns exactly like the generate path") {
        // Identity aus `generation`, Enum als begrenztes NVARCHAR + CHECK: beides
        // kann der Generate-Helfer, eine frisch geschriebene Kopie vergisst es.
        val t = TableDefinition(
            columns = linkedMapOf(
                "mood" to ColumnDefinition(NeutralType.Enum(values = listOf("red", "green"))),
                "big" to ColumnDefinition(NeutralType.BigInteger, generation = ColumnGeneration.Identity()),
            ),
        )
        val sqlText = up(
            SchemaDiff(tablesAdded = listOf(NamedTable("t", t))),
            desired = schema("t" to t),
        ).statements.single().sql
        sqlText shouldContainStr "[mood] NVARCHAR(5)"
        sqlText shouldContainStr "CHECK ([mood] IN ("
        sqlText shouldContainStr "IDENTITY"
    }

    test("notes of the column renderer surface as diagnostics") {
        // Ueber 4000 Zeichen weitet der Renderer auf NVARCHAR(MAX) und meldet
        // W136 — im Migrate-Pfad ist das genauso eine Warnung wert.
        val t = TableDefinition(columns = mapOf("bio" to ColumnDefinition(NeutralType.Text(5000))))
        val r = up(SchemaDiff(tablesAdded = listOf(NamedTable("t", t))), desired = schema("t" to t))
        r.diagnostics.map { it.code } shouldContain "W136"
    }
})
