package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
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

    test("an IDENTITY change rebuilds the table instead of rendering an ALTER COLUMN") {
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
        val sqls = r.statements.map { it.sql }
        sqls.none { it.contains("ALTER COLUMN") } shouldBe true
        sqls.any { it.startsWith("CREATE TABLE [users__dmg_rebuild_") } shouldBe true
        r.blockers.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "MSSQL_TABLE_REBUILT_FOR_IDENTITY"
    }

    test("an operation of a later sub-slice is blocked with a message naming its owner") {
        val view = ViewDefinition(query = "SELECT 1 AS one")
        val r = up(SchemaDiff(viewsAdded = listOf(NamedView("v", view))))
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.diagnostics.single { it.code == "DIALECT_UNSUPPORTED_OPERATION" }
            .message shouldContainStr "sub-slice 5c"
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

    test("CreateTable renders its indices; partitioning still blocks") {
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
        r.statements.map { it.sql }.any { it.startsWith("CREATE TABLE") } shouldBe true
        r.statements.map { it.sql }.any { it.contains("CREATE INDEX [ix_nick]") } shouldBe true
        r.blockers.shouldBeEmpty()
    }

    test("dependent index and constraint are dropped around a column change and recreated") {
        // SQL Server weist ALTER COLUMN mit Msg 5074 ab, solange ein Index oder
        // Constraint auf der Spalte haengt — beides muss darum herum weichen.
        val tableDef = TableDefinition(
            columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(50))),
            indices = listOf(
                IndexDefinition(name = "ix_nick", columns = listOf(IndexColumn("nick")), type = IndexType.BTREE),
            ),
        )
        val withIndex = schema("users" to tableDef)
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
        val sqls = up(diff, current = withIndex, desired = withIndex).statements.map { it.sql }
        sqls[0] shouldBe "DROP INDEX IF EXISTS [ix_nick] ON [users];"
        sqls.any { it.contains("ALTER COLUMN [nick] NVARCHAR(80)") } shouldBe true
        sqls.last() shouldContainStr "CREATE INDEX [ix_nick]"
    }

    test("a nullability change on an IDENTITY column is blocked: SQL Server has no nullable IDENTITY") {
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
        r.diagnostics.map { it.code } shouldContain "MSSQL_IDENTITY_COLUMN_NOT_NULLABLE"
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

    test("AddIndex renders CREATE INDEX; down drops it, and DropIndex is the mirror") {
        val idx = IndexDefinition(name = "ix_nick", columns = listOf(IndexColumn("nick")), type = IndexType.BTREE)
        val tableDef = TableDefinition(
            columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(20))),
            indices = listOf(idx),
        )
        val withIndex = schema("users" to tableDef)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx))))
        up(diff, desired = withIndex).statements.single().sql shouldContainStr "CREATE INDEX [ix_nick]"
        down(diff, current = withIndex, desired = withIndex).statements.single().sql shouldBe
            "DROP INDEX IF EXISTS [ix_nick] ON [users];"

        val dropDiff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesRemoved = listOf(idx))))
        up(dropDiff, current = withIndex, desired = withIndex).statements.single().sql shouldBe
            "DROP INDEX IF EXISTS [ix_nick] ON [users];"
    }

    test("a filtered index carries the SET options into its own statement") {
        // Im Skript setzt Slice 2a sie als eigenen Batch voran; der Migrate-Pfad
        // fuehrt einzeln aus und saehe die Praeambel nie — ohne die Optionen
        // scheitert der Index mit Msg 1934.
        val idx = IndexDefinition(
            name = "ix_active", columns = listOf(IndexColumn("nick")),
            type = IndexType.BTREE, where = "[nick] IS NOT NULL",
        )
        val tableDef = TableDefinition(
            columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(20))),
            indices = listOf(idx),
        )
        val sqlText = up(
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx)))),
            desired = schema("users" to tableDef),
        ).statements.single().sql
        sqlText shouldContainStr "SET QUOTED_IDENTIFIER ON;"
        sqlText shouldContainStr "CREATE INDEX [ix_active]"
        sqlText shouldContainStr "WHERE"
    }

    test("AddConstraint uses WITH CHECK so the constraint is trusted") {
        // Ohne WITH CHECK prueft SQL Server einen nachtraeglichen FK/CHECK NICHT
        // gegen Bestandsdaten — der Constraint gilt dann als not trusted.
        val c = ConstraintDefinition(
            name = "ck_age", type = ConstraintType.CHECK, expression = "age >= 0",
        )
        val tableDef = TableDefinition(
            columns = mapOf("age" to ColumnDefinition(NeutralType.Integer)),
            constraints = listOf(c),
        )
        val withCheck = schema("users" to tableDef)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", constraintsAdded = listOf(c))))
        up(diff, desired = withCheck).statements.single().sql shouldBe
            "ALTER TABLE [users] WITH CHECK ADD CONSTRAINT [ck_age] CHECK (age >= 0);"
        down(diff, current = withCheck, desired = withCheck).statements.single().sql shouldBe
            "ALTER TABLE [users] DROP CONSTRAINT IF EXISTS [ck_age];"
    }

    test("an EXCLUDE constraint is blocked — T-SQL has no equivalent") {
        val c = ConstraintDefinition(name = "ex_x", type = ConstraintType.EXCLUDE, expression = "x WITH =")
        val r = up(
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "t", constraintsAdded = listOf(c)))),
            desired = schema("t" to TableDefinition(constraints = listOf(c))),
        )
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("a partitioned CreateTable still blocks — that is slice 7") {
        val t = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
            partitioning = PartitionConfig(type = PartitionType.RANGE, key = listOf("id")),
        )
        val r = up(SchemaDiff(tablesAdded = listOf(NamedTable("t", t))), desired = schema("t" to t))
        r.statements.shouldBeEmpty()
        r.diagnostics.single { it.code == "DIALECT_UNSUPPORTED_OPERATION" }.message shouldContainStr "slice 7"
    }

    test("a full-text index blocks instead of vanishing") {
        // Der Generate-Helfer meldet E057 (SQL Server braucht Katalog und
        // Schluesselindex); im Diff darf daraus kein leeres Statement werden.
        val idx = IndexDefinition(name = "ft", columns = listOf(IndexColumn("bio")), type = IndexType.FULLTEXT)
        val t = TableDefinition(columns = mapOf("bio" to ColumnDefinition(NeutralType.Text())), indices = listOf(idx))
        val r = up(
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "t", indicesAdded = listOf(idx)))),
            desired = schema("t" to t),
        )
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("an index without a name falls back to the generate path's naming") {
        val idx = IndexDefinition(columns = listOf(IndexColumn("nick")), type = IndexType.BTREE)
        val t = TableDefinition(columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(20))), indices = listOf(idx))
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesRemoved = listOf(idx))))
        up(diff, current = schema("users" to t), desired = schema("users" to t)).statements.single().sql shouldBe
            "DROP INDEX IF EXISTS [idx_users_nick] ON [users];"
    }

    test("an index operation without its table in the schema blocks instead of guessing") {
        val idx = IndexDefinition(name = "ix", columns = listOf(IndexColumn("nick")), type = IndexType.BTREE)
        val r = up(SchemaDiff(tablesChanged = listOf(TableDiff(name = "gone", indicesAdded = listOf(idx)))))
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "MSSQL_COLUMN_NOT_IN_SCHEMA"
    }

    test("a table whose index cannot be rendered blocks before anything is emitted") {
        // Regression: der Volltext-Index blockte NACH dem CREATE TABLE — damit
        // lag die Operation in `rendered` UND `skipped`, und MigrationDdlResult
        // erzwingt per require(), dass die Mengen disjunkt sind.
        val idx = IndexDefinition(name = "ft", columns = listOf(IndexColumn("bio")), type = IndexType.FULLTEXT)
        val t = TableDefinition(
            columns = mapOf("bio" to ColumnDefinition(NeutralType.Text())),
            indices = listOf(idx),
        )
        val r = up(SchemaDiff(tablesAdded = listOf(NamedTable("t", t))), desired = schema("t" to t))
        r.statements.shouldBeEmpty()
        r.operationsRendered.intersect(r.operationsSkipped).shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("a column-level unique is dropped around a column change too") {
        // `unique: true` steht in keiner der beiden Modell-Listen — der
        // Generate-Pfad rendert es als uq_-Constraint an der Spalte. Ohne das
        // nachzubilden bliebe es beim ALTER COLUMN haengen (Msg 5074).
        val tableDef = TableDefinition(
            columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(50), unique = true)),
        )
        val withUnique = schema("users" to tableDef)
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
        val sqls = up(diff, current = withUnique, desired = withUnique).statements.map { it.sql }
        // Der Name des UNIQUE steht NICHT im Modell (der Reverse hebt es auf
        // column.unique), also wird er im Katalog nachgeschlagen statt geraten.
        sqls[0] shouldContainStr "FROM sys.key_constraints"
        sqls[0] shouldContainStr "kc.type = 'UQ'"
        sqls.any { it.contains("ALTER COLUMN [nick] NVARCHAR(80)") } shouldBe true
        // Neu angelegt wird unter d-migrates Namen — das ist ein neues Objekt.
        sqls.any { it.contains("ADD CONSTRAINT [uq_users_nick] UNIQUE") } shouldBe true
    }

    test("dropping a column finds its dependents in the schema that still describes it") {
        // Regression: die Abhaengigkeiten wurden im Schema der Renderrichtung
        // gesucht — bei einem DROP also im Soll-Schema, in dem die Spalte samt
        // ihrer Indizes gerade nicht mehr steht. Der Abraeum-Code lief damit
        // immer leer und DROP COLUMN scheiterte an Msg 5074.
        val before = schema(
            "users" to TableDefinition(
                columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(50))),
                indices = listOf(
                    IndexDefinition(name = "ix_nick", columns = listOf(IndexColumn("nick")), type = IndexType.BTREE),
                ),
            ),
        )
        val after = schema("users" to TableDefinition(columns = emptyMap()))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", columnsRemoved = mapOf("nick" to ColumnDefinition(NeutralType.Text(50)))),
            ),
        )
        val sqls = up(diff, current = before, desired = after).statements.map { it.sql }
        sqls[0] shouldBe "DROP INDEX IF EXISTS [ix_nick] ON [users];"
        sqls.last() shouldBe "ALTER TABLE [users] DROP COLUMN [nick];"
    }

    test("an index the same plan adds is not recreated by the column dance") {
        // COLUMNS rendert vor INDEXES. Wuerde der Tanz den Index aus dem
        // Soll-Schema wiederherstellen, legte ihn die spaetere AddIndex-
        // Operation ein zweites Mal an (Msg 1913).
        val idx = IndexDefinition(name = "ix_nick", columns = listOf(IndexColumn("nick")), type = IndexType.BTREE)
        val before = schema("users" to TableDefinition(columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(50)))))
        val after = schema(
            "users" to TableDefinition(
                columns = mapOf("nick" to ColumnDefinition(NeutralType.Text(80))),
                indices = listOf(idx),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(name = "nick", type = ValueChange(NeutralType.Text(50), NeutralType.Text(80))),
                    ),
                    indicesAdded = listOf(idx),
                ),
            ),
        )
        val sqls = up(diff, current = before, desired = after).statements.map { it.sql }
        sqls.count { it.contains("CREATE INDEX [ix_nick]") } shouldBe 1
    }

    test("an inbound foreign key is dropped around a change to the referenced column") {
        // SQL Server lehnt ALTER COLUMN auch ab, wenn die Abhaengigkeit von
        // AUSSEN kommt — die eigene Tabelle allein zu betrachten reicht nicht.
        val fk = ConstraintDefinition(
            name = "fk_orders_user", type = ConstraintType.FOREIGN_KEY, columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val both = schema(
            "users" to TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true))),
            "orders" to TableDefinition(
                columns = mapOf("user_id" to ColumnDefinition(NeutralType.Integer)),
                constraints = listOf(fk),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(name = "id", type = ValueChange(NeutralType.Integer, NeutralType.BigInteger)),
                    ),
                ),
            ),
        )
        val sqls = up(diff, current = both, desired = both).statements.map { it.sql }
        sqls[0] shouldBe "ALTER TABLE [orders] DROP CONSTRAINT IF EXISTS [fk_orders_user];"
        sqls.last() shouldContainStr "ALTER TABLE [orders] WITH CHECK ADD CONSTRAINT [fk_orders_user]"
    }

    test("a UNIQUE is not restored on a column that became a large object") {
        // NVARCHAR(MAX) ist keine zulaessige Schluesselspalte (Msg 1919); der
        // Generate-Pfad laesst das UNIQUE weg und meldet E057.
        val before = schema(
            "users" to TableDefinition(
                columns = mapOf("bio" to ColumnDefinition(NeutralType.Text(50), unique = true)),
            ),
        )
        val after = schema(
            "users" to TableDefinition(
                columns = mapOf("bio" to ColumnDefinition(NeutralType.Text(), unique = true)),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(name = "bio", type = ValueChange(NeutralType.Text(50), NeutralType.Text())),
                    ),
                ),
            ),
        )
        val r = up(diff, current = before, desired = after)
        r.statements.map { it.sql }.any { it.contains("ADD CONSTRAINT [uq_users_bio]") } shouldBe false
        r.diagnostics.map { it.code } shouldContain "E057"
    }

    test("CreateTable renders its foreign keys through the cascade guard") {
        // Ein FK, der einen zweiten Kaskadenpfad schliesst, muss auch beim
        // CREATE TABLE zu NO ACTION werden — sonst scheitert es an 1785, waehrend
        // `schema generate` fuer dasselbe Schema NO ACTION schreibt.
        val fk = ConstraintDefinition(
            name = "fk_child_parent", type = ConstraintType.FOREIGN_KEY, columns = listOf("pid"),
            references = ConstraintReferenceDefinition(
                table = "child", columns = listOf("id"), onDelete = ReferentialAction.CASCADE,
            ),
        )
        val child = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "pid" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
            constraints = listOf(fk),
        )
        val sqlText = up(
            SchemaDiff(tablesAdded = listOf(NamedTable("child", child))),
            desired = schema("child" to child),
        ).statements.single().sql
        sqlText shouldContainStr "FOREIGN KEY ([pid])"
    }
})
