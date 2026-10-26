package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TableMetadata
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SqliteRebuildPlanWithoutRowidTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    fun schemaWith(tables: Map<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables)

    fun table(withoutRowid: Boolean?) = TableDefinition(
        columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
        primaryKey = listOf("id"),
        metadata = withoutRowid?.let { TableMetadata(withoutRowid = it) },
    )

    fun plan(old: TableDefinition, new: TableDefinition, direction: SqliteRenderDirection = SqliteRenderDirection.UP) =
        SqliteRebuildPlan(
            originalTableName = "t",
            oldTable = old,
            newTable = new,
            newTableTempName = "t_new",
            bucketOperations = emptyList(),
            sourceOperationIds = emptySet(),
            risk = OperationRisk.SAFE,
            mapping = SqliteColumnMappingModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            indexesToRecreate = emptyList(),
            direction = direction,
        )

    val down = SqliteRenderDirection.DOWN

    test("Merkmal der Datenbank-Seite zaehlt, auch wenn die Datei es nicht kennt (Up: oldTable)") {
        plan(table(true), table(null)).withoutRowid shouldBe true
    }

    test("Merkmal der Datenbank-Seite zaehlt in der Gegenrichtung (Down: newTable)") {
        plan(table(null), table(true), down).withoutRowid shouldBe true
    }

    test("Merkmal der Datei zaehlt nicht, wenn die Datenbank es nicht fuehrt (Up und Down)") {
        plan(table(false), table(true)).withoutRowid shouldBe false
        plan(table(null), table(true)).withoutRowid shouldBe false
        plan(table(true), table(false), down).withoutRowid shouldBe false
        plan(table(true), table(null), down).withoutRowid shouldBe false
    }

    test("die Datei weicht ab, wenn sie true fuehrt und die Datenbank nicht (Fall A)") {
        plan(table(false), table(true)).fileDeclaresWithoutRowid shouldBe true
        plan(table(true), table(false), down).fileDeclaresWithoutRowid shouldBe true
        plan(table(true), table(true)).fileDeclaresWithoutRowid shouldBe false
        plan(table(true), table(null)).fileDeclaresWithoutRowid shouldBe false
        plan(table(false), table(false)).fileDeclaresWithoutRowid shouldBe false
    }

    test("keine Seite traegt das Merkmal") {
        plan(table(null), table(null)).withoutRowid shouldBe false
        plan(table(false), table(false)).withoutRowid shouldBe false
    }

    // Neubau durch eine Typaenderung; die Datenbank ist `current`, die Datei `desired`.
    fun rebuildTable(withoutRowid: Boolean?, type: NeutralType) = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "views" to ColumnDefinition(type),
        ),
        primaryKey = listOf("id"),
        metadata = withoutRowid?.let { TableMetadata(withoutRowid = it) },
    )

    fun typeDiff() = SchemaDiff(
        tablesChanged = listOf(
            TableDiff(
                name = "t",
                columnsChanged = listOf(
                    dev.dmigrate.core.diff.ColumnDiff("views", type = ValueChange(NeutralType.SmallInt, NeutralType.Integer)),
                ),
            ),
        ),
    )

    fun diffOf(dbFlag: Boolean?, fileFlag: Boolean?) = planner.plan(
        schemaWith(mapOf("t" to rebuildTable(dbFlag, NeutralType.SmallInt))),
        schemaWith(mapOf("t" to rebuildTable(fileFlag, NeutralType.Integer))),
        typeDiff(),
    )

    fun upWarnings(dbFlag: Boolean?, fileFlag: Boolean?) = gen.generateUp(diffOf(dbFlag, fileFlag), DdlGenerationOptions())

    fun MigrationDdlResult.withoutRowidRebuild() =
        statements.any { it.sql.startsWith("CREATE TABLE") && it.sql.endsWith(") WITHOUT ROWID;") }

    fun MigrationDdlResult.w164() = diagnostics.filter { it.code == "W164" }

    test("Fall A: Datenbank Rowid, Datei true: der Neubau bleibt Rowid und warnt mit W164 an der Neubau-Operation") {
        val diff = diffOf(dbFlag = null, fileFlag = true)
        val r = gen.generateUp(diff, DdlGenerationOptions())
        r.isBlocked shouldBe false
        r.withoutRowidRebuild() shouldBe false
        val warn = r.w164().single()
        warn.severity shouldBe DiffDiagnostic.Severity.WARNING
        warn.operationId shouldBe diff.operations.first().id
    }

    test("Fall B: Datenbank Rowid, Datei ohne Angabe oder false: Rowid, keine Warnung") {
        for (file in listOf(null, false)) {
            val r = upWarnings(dbFlag = null, fileFlag = file)
            r.withoutRowidRebuild() shouldBe false
            r.w164().size shouldBe 0
        }
    }

    test("Fall C1 und C2: Datenbank WITHOUT ROWID: bleibt WITHOUT ROWID, keine Warnung (Datei true, ohne Angabe, false)") {
        for (file in listOf(true, null, false)) {
            val r = upWarnings(dbFlag = true, fileFlag = file)
            r.withoutRowidRebuild() shouldBe true
            r.w164().size shouldBe 0
        }
    }

    test("Down warnt nie: der Rollback von Fall A kehrt zur Rowid-Tabelle zurueck, ohne W164") {
        val r = gen.generateDown(diffOf(dbFlag = null, fileFlag = true), DdlGenerationOptions())
        r.withoutRowidRebuild() shouldBe false
        r.statements.any { it.sql.startsWith("CREATE TABLE") } shouldBe true
        r.w164().size shouldBe 0
    }

    test("FULLTEXT recreate degrades when only the database side is WITHOUT ROWID (file silent)") {
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
            metadata = null,
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
        val r = gen.generateUp(
            planner.plan(schemaWith(mapOf("docs" to before)), schemaWith(mapOf("docs" to after)), diff),
            DdlGenerationOptions(),
        )

        r.isBlocked shouldBe false
        r.statements.any { it.sql.startsWith("CREATE VIRTUAL TABLE") } shouldBe false
        r.statements.any { it.sql.startsWith("-- FULLTEXT index \"docs_fts\" skipped") } shouldBe true
        r.statements.any { it.sql.startsWith("CREATE TABLE") && it.sql.endsWith(") WITHOUT ROWID;") } shouldBe true
        r.diagnostics.any { it.code == "W132" } shouldBe true
    }

    // FULLTEXT-Fall A-FT: Datenbank Rowid, Datei true. Die Datenbank-Seite entscheidet: Rowid ist FTS5-faehig.
    val ftIndex = IndexDefinition(name = "docs_fts", columns = listOf(IndexColumn("title")), type = IndexType.FULLTEXT)

    fun docs(withoutRowid: Boolean?, fulltext: Boolean, views: NeutralType = NeutralType.SmallInt) = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "title" to ColumnDefinition(NeutralType.Text()),
            "views" to ColumnDefinition(views),
        ),
        primaryKey = listOf("id"),
        indices = if (fulltext) listOf(ftIndex) else emptyList(),
        metadata = withoutRowid?.let { TableMetadata(withoutRowid = it) },
    )

    test("A-FT mit Neubau: Datei true gegen Rowid-Datenbank baut FTS5 auf (kein W132) und warnt mit W164") {
        val diff = planner.plan(
            schemaWith(mapOf("docs" to docs(null, fulltext = true))),
            schemaWith(mapOf("docs" to docs(true, fulltext = true, views = NeutralType.Integer))),
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "docs",
                        columnsChanged = listOf(
                            dev.dmigrate.core.diff.ColumnDiff("views", type = ValueChange(NeutralType.SmallInt, NeutralType.Integer)),
                        ),
                    ),
                ),
            ),
        )
        val r = gen.generateUp(diff, DdlGenerationOptions())
        val sql = r.statements.joinToString("\n") { it.sql }
        r.isBlocked shouldBe false
        r.withoutRowidRebuild() shouldBe false
        sql shouldContain "CREATE VIRTUAL TABLE \"docs_fts\" USING fts5("
        r.diagnostics.none { it.code == "W132" } shouldBe true
        r.w164().size shouldBe 1
    }

    test("FULLTEXT-Index ohne Neubau (Fall E): die Datei true aendert nichts, FTS5 wird aufgebaut, keine Warnung") {
        val r = gen.generateUp(
            planner.plan(
                schemaWith(mapOf("docs" to docs(null, fulltext = false))),
                schemaWith(mapOf("docs" to docs(true, fulltext = true))),
                SchemaDiff(tablesChanged = listOf(TableDiff(name = "docs", indicesAdded = listOf(ftIndex)))),
            ),
            DdlGenerationOptions(),
        )
        r.statements.joinToString("\n") { it.sql } shouldContain "CREATE VIRTUAL TABLE \"docs_fts\""
        r.diagnostics.none { it.code == "W132" || it.code == "W164" } shouldBe true
    }
})
