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
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.TransactionBehavior
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

    test("Down direction on a fully reversible rebuild bucket emits inverse rebuild (D.5)") {
        // AlterColumnType is AUTOMATIC_WITH_DATA_RISK → reversible.
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
        r.isBlocked shouldBe false
        // Down rebuild casts BigInteger back to Integer (inverse direction).
        val insertSelect = r.statements.single { it.sql.startsWith("INSERT INTO") }.sql
        insertSelect shouldContain "CAST(\"id\" AS INTEGER)"
        // Sequence is the canonical 9-step (no indices in this test).
        r.statements.size shouldBe 9
        r.statements.first().sql shouldBe "PRAGMA foreign_keys = OFF;"
        r.statements.last().sql shouldBe "PRAGMA foreign_keys = ON;"
    }

    test("Down direction on a bucket containing DropColumn (NOT_REVERSIBLE) yields ROLLBACK_NOT_POSSIBLE") {
        // DropColumn is absorbed into the rebuild bucket and is NOT_REVERSIBLE.
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "legacy" to ColumnDefinition(NeutralType.Text()),
            ),
            primaryKey = listOf("id"),
        )
        val after = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        // The DropColumn alone wouldn't trigger a rebuild — pair it with an
        // AlterColumnType so the bucket actually forms.
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsRemoved = mapOf("legacy" to ColumnDefinition(NeutralType.Text())),
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
        val r = gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        r.diagnostics.any { it.code == "SQLITE_REBUILD_NOT_REVERSIBLE" } shouldBe true
    }

    test("rebuild of a FULLTEXT-carrying table drops and recreates the FTS5 objects") {
        // ADR 0025: the FTS5 virtual table would survive the base-table
        // drop orphaned (sync triggers die with the table) — the rebuild
        // must drop it before `DROP TABLE` and recreate the full expansion
        // (virtual table + 'rebuild' + 3 sync triggers) after the RENAME.
        val fulltext = IndexDefinition(
            name = "docs_fts",
            columns = listOf(IndexColumn("title"), IndexColumn("body")),
            type = IndexType.FULLTEXT,
        )
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "title" to ColumnDefinition(NeutralType.Text()),
                "body" to ColumnDefinition(NeutralType.Text()),
                "views" to ColumnDefinition(NeutralType.SmallInt),
            ),
            primaryKey = listOf("id"),
            indices = listOf(fulltext),
        )
        val after = before.copy(
            columns = before.columns + ("views" to ColumnDefinition(NeutralType.Integer)),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "docs",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "views",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("docs" to before))
        val desired = schemaWith(mapOf("docs" to after))

        val up = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        up.isBlocked shouldBe false
        val sqls = up.statements.map { it.sql }

        // Old FTS5 objects torn down BEFORE the base-table drop...
        val ftsDrop = sqls.indexOfFirst { it == "DROP TABLE IF EXISTS \"docs_fts\";" }
        val baseDrop = sqls.indexOfFirst { it.startsWith("DROP TABLE \"docs\"") }
        val rename = sqls.indexOfFirst { it.contains("RENAME TO \"docs\"") }
        (ftsDrop >= 0) shouldBe true
        (ftsDrop < baseDrop) shouldBe true
        sqls.count { it.startsWith("DROP TRIGGER IF EXISTS \"docs_fts_") } shouldBe 3

        // ...and recreated (incl. 'rebuild' repopulation) AFTER the RENAME.
        val createVt = sqls.indexOfFirst { it.startsWith("CREATE VIRTUAL TABLE \"docs_fts\" USING fts5") }
        val rebuild = sqls.indexOfFirst { it == "INSERT INTO \"docs_fts\"(\"docs_fts\") VALUES('rebuild');" }
        (rename in (baseDrop + 1) until createVt) shouldBe true
        (rebuild > createVt) shouldBe true
        sqls.single { it.startsWith("CREATE VIRTUAL TABLE") } shouldContain "content='docs'"
        sqls.count { it.startsWith("CREATE TRIGGER \"docs_fts_") } shouldBe 3

        // DOWN direction (reversible bucket) recreates the FTS5 objects too.
        val down = gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())
        down.isBlocked shouldBe false
        val downSqls = down.statements.map { it.sql }
        downSqls.any { it.startsWith("CREATE VIRTUAL TABLE \"docs_fts\" USING fts5") } shouldBe true
        downSqls.count { it.startsWith("CREATE TRIGGER \"docs_fts_") } shouldBe 3
    }

    test("FULLTEXT recreate degrades with W132 when the new table is WITHOUT ROWID") {
        // unsupportedReason: external-content FTS5 needs the implicit rowid —
        // degrade with the skip marker + W132 diagnostic, never broken DDL.
        val fulltext = IndexDefinition(
            name = "docs_fts",
            columns = listOf(IndexColumn("title")),
            type = IndexType.FULLTEXT,
        )
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "title" to ColumnDefinition(NeutralType.Text()),
                "views" to ColumnDefinition(NeutralType.SmallInt),
            ),
            primaryKey = listOf("id"),
            indices = listOf(fulltext),
            metadata = dev.dmigrate.core.model.TableMetadata(withoutRowid = true),
        )
        val after = before.copy(
            columns = before.columns + ("views" to ColumnDefinition(NeutralType.Integer)),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "docs",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "views",
                            type = ValueChange(NeutralType.SmallInt, NeutralType.Integer),
                        ),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("docs" to before))
        val desired = schemaWith(mapOf("docs" to after))
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false
        r.statements.any { it.sql.startsWith("CREATE VIRTUAL TABLE") } shouldBe false
        r.statements.any { it.sql.startsWith("-- FULLTEXT index \"docs_fts\" skipped") } shouldBe true
        r.diagnostics.any { it.code == "W132" } shouldBe true
    }

    // ---- Phase G.1: SQLite Cast-Matrix ----

    /**
     * Builds a single-column AlterColumnType rebuild and runs it through
     * the generator. Returns the result so individual tests can assert
     * blocked-state, diagnostics and emitted SQL.
     */
    fun runCastMatrixCase(from: NeutralType, to: NeutralType): MigrationDdlResult {
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "col" to ColumnDefinition(from),
            ),
            primaryKey = listOf("id"),
        )
        val after = before.copy(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "col" to ColumnDefinition(to),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        dev.dmigrate.core.diff.ColumnDiff(
                            name = "col",
                            type = ValueChange(from, to),
                        ),
                    ),
                ),
            ),
        )
        val current = schemaWith(mapOf("u" to before))
        val desired = schemaWith(mapOf("u" to after))
        return gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
    }

    test("G.1 whitelist: Integer-family casts emit CAST (all 6 ordered pairs)") {
        val pairs = listOf(
            NeutralType.SmallInt to NeutralType.Integer,
            NeutralType.Integer to NeutralType.SmallInt,
            NeutralType.SmallInt to NeutralType.BigInteger,
            NeutralType.BigInteger to NeutralType.SmallInt,
            NeutralType.Integer to NeutralType.BigInteger,
            NeutralType.BigInteger to NeutralType.Integer,
        )
        for ((from, to) in pairs) {
            val r = runCastMatrixCase(from, to)
            r.isBlocked shouldBe false
            val insert = r.statements.single { it.sql.startsWith("INSERT INTO") }.sql
            insert shouldContain "CAST(\"col\" AS INTEGER)"
        }
    }

    test("G.1 whitelist: Text-family widening (length non-narrowing)") {
        val widening = listOf(
            NeutralType.Text(maxLength = 20) to NeutralType.Text(maxLength = 50),
            NeutralType.Text(maxLength = 20) to NeutralType.Text(maxLength = null),
            NeutralType.Char(length = 10) to NeutralType.Char(length = 20),
            NeutralType.Char(length = 10) to NeutralType.Text(maxLength = null),
            NeutralType.Char(length = 10) to NeutralType.Text(maxLength = 20),
            NeutralType.Text(maxLength = 10) to NeutralType.Char(length = 20),
        )
        for ((from, to) in widening) {
            val r = runCastMatrixCase(from, to)
            r.isBlocked shouldBe false
        }
    }

    test("G.1 whitelist: Date -> DateTime(tz=false)") {
        val r = runCastMatrixCase(NeutralType.Date, NeutralType.DateTime(timezone = false))
        r.isBlocked shouldBe false
    }

    test("G.1 block: Text-family narrowing yields SQLITE_CAST_NOT_WHITELISTED") {
        val narrowing = listOf(
            NeutralType.Text(maxLength = 20) to NeutralType.Text(maxLength = 10),
            NeutralType.Char(length = 20) to NeutralType.Char(length = 10),
            NeutralType.Text(maxLength = null) to NeutralType.Text(maxLength = 10),
            NeutralType.Text(maxLength = 20) to NeutralType.Char(length = 10),
        )
        for ((from, to) in narrowing) {
            val r = runCastMatrixCase(from, to)
            r.isBlocked shouldBe true
            r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
            val diag = r.diagnostics.single { it.code == "SQLITE_CAST_NOT_WHITELISTED" }
            diag.message shouldContain "would truncate"
        }
    }

    test("G.1 block: structural carve-outs are blocked with SQLITE_CAST_NOT_WHITELISTED") {
        val blocked = listOf(
            // Float <-> Decimal
            NeutralType.Float() to NeutralType.Decimal(10, 2),
            NeutralType.Decimal(10, 2) to NeutralType.Float(),
            // Boolean <-> Integer family
            NeutralType.BooleanType to NeutralType.Integer,
            NeutralType.Integer to NeutralType.BooleanType,
            // DateTime tz changes / component loss
            NeutralType.DateTime(timezone = true) to NeutralType.DateTime(timezone = false),
            NeutralType.DateTime(timezone = false) to NeutralType.Date,
            // Text -> Integer / Float / Uuid
            NeutralType.Text() to NeutralType.Integer,
            NeutralType.Text() to NeutralType.Float(),
            NeutralType.Text() to NeutralType.Uuid,
            // Integer / Float -> Text (carve-out per plan wording)
            NeutralType.Integer to NeutralType.Text(),
            NeutralType.Float() to NeutralType.Text(),
            // Binary <-> Text (open question per plan)
            NeutralType.Binary to NeutralType.Text(),
            NeutralType.Text() to NeutralType.Binary,
        )
        for ((from, to) in blocked) {
            val r = runCastMatrixCase(from, to)
            r.isBlocked shouldBe true
            r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
            r.diagnostics.any { it.code == "SQLITE_CAST_NOT_WHITELISTED" } shouldBe true
        }
    }

    test("G.1 block: diagnostic carries source and target types in the message") {
        val r = runCastMatrixCase(NeutralType.Integer, NeutralType.Text())
        val diag = r.diagnostics.single { it.code == "SQLITE_CAST_NOT_WHITELISTED" }
        diag.message shouldContain "Integer"
        diag.message shouldContain "Text"
        diag.message shouldContain "`col`"
    }

    test("G.1 block: rebuild emits no CAST SQL for blocked columns") {
        val r = runCastMatrixCase(NeutralType.Text(), NeutralType.Integer)
        r.statements.any { it.sql.contains("CAST(\"col\"") } shouldBe false
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

    test("PK reshape (column change to PRIMARY KEY) renders the new key in CREATE temp + SELECT in column order") {
        // Phase H.1b coverage: a real PK change (id → (id, tenant))
        // should hit the rebuild path. Pin the CREATE-temp PRIMARY KEY
        // line and the INSERT-SELECT preserving both columns.
        val before = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "tenant" to ColumnDefinition(NeutralType.Integer, required = true),
            ),
            primaryKey = listOf("id"),
        )
        val after = before.copy(primaryKey = listOf("id", "tenant"))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    primaryKey = ValueChange(listOf("id"), listOf("id", "tenant")),
                ),
            ),
        )
        val current = schemaWith(mapOf("u" to before))
        val desired = schemaWith(mapOf("u" to after))
        val r = gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())
        r.isBlocked shouldBe false

        val createTemp = r.statements.single { it.sql.startsWith("CREATE TABLE \"u__dmg_rebuild_") }.sql
        // The new composite primary key must be on a separate line in the CREATE.
        createTemp shouldContain "PRIMARY KEY (\"id\", \"tenant\")"

        val insertSelect = r.statements.single { it.sql.startsWith("INSERT INTO") }.sql
        // Both source columns should be SELECTed (deterministic sort by name: id, tenant).
        insertSelect shouldContain "(\"id\", \"tenant\")"
        insertSelect shouldContain "SELECT \"id\", \"tenant\""
    }

    // ── Plan-2 §A.1: dialect execution hints for the rebuild bracket ──

    test("§A.1: rebuild hints differentiate outside-tx PRAGMAs from inside-tx statements") {
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

        // Canonical 9-statement order from the test above:
        //   0: PRAGMA foreign_keys = OFF;        → OUTSIDE tx
        //   1: BEGIN IMMEDIATE;                  → INSIDE tx (marker)
        //   2..5: CREATE/INSERT/DROP/RENAME      → INSIDE tx
        //   6: PRAGMA foreign_key_check;         → INSIDE tx
        //   7: COMMIT;                           → INSIDE tx (marker)
        //   8: PRAGMA foreign_keys = ON;         → OUTSIDE tx
        val pragmaOff = r.statements[0]
        pragmaOff.sql shouldBe "PRAGMA foreign_keys = OFF;"
        pragmaOff.hints.transactionBehavior shouldBe TransactionBehavior.NOT_TRANSACTIONAL
        pragmaOff.hints.lockBehavior shouldBe LockBehavior.NONE
        pragmaOff.hints.sideEffectsPossible shouldBe true

        // BEGIN/COMMIT and PRAGMA foreign_key_check are TX-markers /
        // read-only checks inside the bracket — FULLY_TRANSACTIONAL
        // but NOT lock-heavy DDL.
        val beginMarker = r.statements[1]
        beginMarker.sql shouldBe "BEGIN IMMEDIATE;"
        beginMarker.hints.transactionBehavior shouldBe TransactionBehavior.FULLY_TRANSACTIONAL
        beginMarker.hints.lockBehavior shouldBe LockBehavior.NONE
        beginMarker.hints.requiresExclusiveAccess shouldBe false

        val createTemp = r.statements[2]
        createTemp.sql shouldContain "CREATE TABLE \"u__dmg_rebuild_"
        createTemp.hints.transactionBehavior shouldBe TransactionBehavior.FULLY_TRANSACTIONAL
        createTemp.hints.lockBehavior shouldBe LockBehavior.TABLE_EXCLUSIVE
        createTemp.hints.requiresExclusiveAccess shouldBe true

        val fkCheck = r.statements[6]
        fkCheck.sql shouldBe "PRAGMA foreign_key_check;"
        fkCheck.hints.transactionBehavior shouldBe TransactionBehavior.FULLY_TRANSACTIONAL
        fkCheck.hints.lockBehavior shouldBe LockBehavior.NONE
        fkCheck.hints.requiresExclusiveAccess shouldBe false

        val commitMarker = r.statements[7]
        commitMarker.sql shouldBe "COMMIT;"
        commitMarker.hints.transactionBehavior shouldBe TransactionBehavior.FULLY_TRANSACTIONAL
        commitMarker.hints.lockBehavior shouldBe LockBehavior.NONE
        commitMarker.hints.requiresExclusiveAccess shouldBe false

        val pragmaOn = r.statements[8]
        pragmaOn.sql shouldBe "PRAGMA foreign_keys = ON;"
        pragmaOn.hints.transactionBehavior shouldBe TransactionBehavior.NOT_TRANSACTIONAL
    }
})
