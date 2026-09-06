package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Sub-Slice 5b: Constraints und Indizes. Die Oracle-Eigenheiten, auf denen
 * diese Erwartungen beruhen, sind in [OracleDiffObjectOps] dokumentiert und
 * live gemessen (kein `WITH CHECK`-Äquivalent, `DROP INDEX` ohne
 * Tabellenname, UNIQUE-Constraint trägt seinen Index selbst).
 */
class OracleDiffObjectOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = OracleDiffDdlGenerator()

    val usersTable = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "email" to ColumnDefinition(NeutralType.Text(maxLength = 200)),
            "body" to ColumnDefinition(NeutralType.Text()),
        ),
        primaryKey = listOf("id"),
    )

    fun schemaWithUsers() = SchemaDefinition(name = "App", version = "1", tables = mapOf("users" to usersTable))

    fun plan(
        diff: SchemaDiff,
        current: SchemaDefinition = schemaWithUsers(),
        desired: SchemaDefinition = schemaWithUsers(),
    ): DiffResult = planner.plan(current, desired, diff)

    fun up(
        diff: SchemaDiff,
        current: SchemaDefinition = schemaWithUsers(),
        desired: SchemaDefinition = schemaWithUsers(),
    ) = gen.generateUp(plan(diff, current, desired), DdlGenerationOptions())

    fun down(
        diff: SchemaDiff,
        current: SchemaDefinition = schemaWithUsers(),
        desired: SchemaDefinition = schemaWithUsers(),
    ) = gen.generateDown(plan(diff, current, desired), DdlGenerationOptions())

    /** Schema OHNE die Tabelle -- fuer die Faelle, in denen der Renderer sie braucht. */
    fun schemaWithoutUsers() = SchemaDefinition(name = "App", version = "1")

    fun constraintAdded(c: ConstraintDefinition) =
        SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", constraintsAdded = listOf(c))))

    fun constraintRemoved(c: ConstraintDefinition) =
        SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", constraintsRemoved = listOf(c))))

    // ── Constraints ──────────────────────────────

    test("AddConstraint UNIQUE: up adds, down drops — no WITH CHECK equivalent needed") {
        val c = ConstraintDefinition(name = "uq_users_email", type = ConstraintType.UNIQUE, columns = listOf("email"))
        up(constraintAdded(c)).statements.single().sql shouldBe
            "ALTER TABLE \"users\" ADD CONSTRAINT \"uq_users_email\" UNIQUE (\"email\");"
        down(constraintAdded(c)).statements.single().sql shouldBe
            "ALTER TABLE \"users\" DROP CONSTRAINT \"uq_users_email\";"
    }

    test("AddConstraint CHECK renders the expression verbatim") {
        val c = ConstraintDefinition(name = "ck_users_email", type = ConstraintType.CHECK, expression = "email IS NOT NULL")
        up(constraintAdded(c)).statements.single().sql shouldBe
            "ALTER TABLE \"users\" ADD CONSTRAINT \"ck_users_email\" CHECK (email IS NOT NULL);"
    }

    test("AddConstraint FOREIGN KEY emits REFERENCES") {
        val c = ConstraintDefinition(
            name = "fk_users_org",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("id"),
            references = ConstraintReferenceDefinition(table = "orgs", columns = listOf("id")),
        )
        up(constraintAdded(c)).statements.single().sql shouldBe
            "ALTER TABLE \"users\" ADD CONSTRAINT \"fk_users_org\" FOREIGN KEY (\"id\") REFERENCES \"orgs\" (\"id\");"
    }

    test("DropConstraint: up drops, down re-adds the full clause") {
        val c = ConstraintDefinition(name = "uq_users_email", type = ConstraintType.UNIQUE, columns = listOf("email"))
        up(constraintRemoved(c)).statements.single().sql shouldBe
            "ALTER TABLE \"users\" DROP CONSTRAINT \"uq_users_email\";"
        down(constraintRemoved(c)).statements.single().sql shouldBe
            "ALTER TABLE \"users\" ADD CONSTRAINT \"uq_users_email\" UNIQUE (\"email\");"
    }

    test("EXCLUDE has no Oracle equivalent and blocks instead of emitting a bare note") {
        val c = ConstraintDefinition(name = "ex_users", type = ConstraintType.EXCLUDE, expression = "a WITH =")
        val r = up(constraintAdded(c))
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.diagnostics.any { it.code == "ORACLE_EXCLUDE_CONSTRAINT_UNSUPPORTED" } shouldBe true
    }

    test("CHECK without an expression blocks — the clause is not reconstructible") {
        val c = ConstraintDefinition(name = "ck_users_x", type = ConstraintType.CHECK, expression = null)
        val r = up(constraintAdded(c))
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_CHECK_EXPRESSION_MISSING" } shouldBe true
    }

    test("FOREIGN KEY without a reference target blocks instead of throwing") {
        val c = ConstraintDefinition(name = "fk_users_x", type = ConstraintType.FOREIGN_KEY, columns = listOf("id"))
        val r = up(constraintAdded(c))
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_FOREIGN_KEY_REFERENCE_MISSING" } shouldBe true
    }

    test("UNIQUE on a CLOB column blocks — Oracle rejects LOB key columns (ORA-02329)") {
        val c = ConstraintDefinition(name = "uq_users_body", type = ConstraintType.UNIQUE, columns = listOf("body"))
        val r = up(constraintAdded(c))
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.any { it.code == "ORACLE_CONSTRAINT_NOT_RENDERABLE" } shouldBe true
    }

    // ── Indices ──────────────────────────────────

    test("AddIndex: up creates, down drops without naming the table") {
        val idx = IndexDefinition(name = "idx_users_email", columns = listOf(IndexColumn("email")), type = IndexType.BTREE)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx))))
        up(diff).statements.single().sql shouldBe "CREATE INDEX \"idx_users_email\" ON \"users\" (\"email\");"
        down(diff).statements.single().sql shouldBe "DROP INDEX \"idx_users_email\";"
    }

    test("AddIndex: a bitmap index keeps its access method in the diff path too") {
        // Generate und Diff teilen sich OracleIndexDdlBuilder -- diese
        // Zusicherung haelt fest, dass der Diff-Pfad die Naht wirklich nutzt
        // und nicht still auf einen gewoehnlichen Index zurueckfaellt.
        val idx = IndexDefinition(name = "bm_email", columns = listOf(IndexColumn("email")), type = IndexType.BITMAP)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx))))
        up(diff).statements.single().sql shouldBe "CREATE BITMAP INDEX \"bm_email\" ON \"users\" (\"email\");"
        down(diff).statements.single().sql shouldBe "DROP INDEX \"bm_email\";"
    }

    test("AddIndex: a unique bitmap index falls back to a unique B-tree (there is no UNIQUE BITMAP)") {
        val idx = IndexDefinition(
            name = "uq_bm", columns = listOf(IndexColumn("email")), type = IndexType.BITMAP, unique = true,
        )
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx))))
        val r = up(diff)
        r.statements.single().sql shouldBe "CREATE UNIQUE INDEX \"uq_bm\" ON \"users\" (\"email\");"
        r.diagnostics.any { it.code == "W102" } shouldBe true
    }

    test("DropIndex: up drops, down recreates") {
        val idx = IndexDefinition(name = "idx_users_email", columns = listOf(IndexColumn("email")), type = IndexType.BTREE)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesRemoved = listOf(idx))))
        up(diff).statements.single().sql shouldBe "DROP INDEX \"idx_users_email\";"
        down(diff).statements.single().sql shouldBe "CREATE INDEX \"idx_users_email\" ON \"users\" (\"email\");"
    }

    test("An anonymous index uses one shared name for create and drop") {
        // Sonst legte CREATE einen anderen Namen an, als DROP spaeter sucht.
        val idx = IndexDefinition(columns = listOf(IndexColumn("email")), type = IndexType.BTREE)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx))))
        val created = up(diff).statements.single().sql
        val dropped = down(diff).statements.single().sql
        created shouldBe "CREATE INDEX \"idx_users_email\" ON \"users\" (\"email\");"
        dropped shouldBe "DROP INDEX \"idx_users_email\";"
    }

    test("An index on a CLOB column blocks — Oracle rejects LOB index keys") {
        val idx = IndexDefinition(name = "idx_users_body", columns = listOf(IndexColumn("body")), type = IndexType.BTREE)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx))))
        val r = up(diff)
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_INDEX_NOT_RENDERABLE" } shouldBe true
    }

    test("A non-BTREE index still renders as B-tree, carrying the W102 note through") {
        val idx = IndexDefinition(name = "idx_users_hash", columns = listOf(IndexColumn("email")), type = IndexType.HASH)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx))))
        val r = up(diff)
        r.statements.single().sql shouldBe "CREATE INDEX \"idx_users_hash\" ON \"users\" (\"email\");"
        r.diagnostics.any { it.code == "W102" } shouldBe true
    }

    test("AddIndex blocks when the table is absent from the schema of this direction") {
        val idx = IndexDefinition(name = "idx_users_email", columns = listOf(IndexColumn("email")), type = IndexType.BTREE)
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(name = "users", indicesAdded = listOf(idx))))
        val r = up(diff, desired = schemaWithoutUsers())
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.any { it.code == "ORACLE_TABLE_NOT_IN_SCHEMA" } shouldBe true
    }

    test("UNIQUE blocks when the table is absent — the LOB guard must not disarm itself silently") {
        // Ohne die Tabelle waere die LOB-Spaltenmenge leer, der ORA-02329-
        // Waechter liefe ins Leere und wir emittierten DDL, die Oracle ablehnt.
        val c = ConstraintDefinition(name = "uq_users_body", type = ConstraintType.UNIQUE, columns = listOf("body"))
        val r = up(constraintAdded(c), desired = schemaWithoutUsers())
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_TABLE_NOT_IN_SCHEMA" } shouldBe true
    }

    test("CHECK still renders without the table — it does not need the LOB information") {
        val c = ConstraintDefinition(name = "ck_users_x", type = ConstraintType.CHECK, expression = "id > 0")
        up(constraintAdded(c), desired = schemaWithoutUsers()).statements.single().sql shouldBe
            "ALTER TABLE \"users\" ADD CONSTRAINT \"ck_users_x\" CHECK (id > 0);"
    }

    test("UNIQUE/FOREIGN KEY without columns blocks instead of rendering broken SQL") {
        val uq = ConstraintDefinition(name = "uq_null", type = ConstraintType.UNIQUE, columns = null)
        up(constraintAdded(uq)).diagnostics.any { it.code == "ORACLE_CONSTRAINT_COLUMNS_MISSING" } shouldBe true
        val fk = ConstraintDefinition(
            name = "fk_null",
            type = ConstraintType.FOREIGN_KEY,
            columns = null,
            references = ConstraintReferenceDefinition(table = "orgs", columns = listOf("id")),
        )
        up(constraintAdded(fk)).diagnostics.any { it.code == "ORACLE_CONSTRAINT_COLUMNS_MISSING" } shouldBe true
    }

    test("notes from the reused Generate helper reach the diagnostics (W153)") {
        val c = ConstraintDefinition(
            name = "fk_users_org",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("id"),
            references = ConstraintReferenceDefinition(
                table = "orgs",
                columns = listOf("id"),
                onDelete = ReferentialAction.SET_DEFAULT,
            ),
        )
        up(constraintAdded(c)).diagnostics.any { it.code == "W153" } shouldBe true
    }
})
