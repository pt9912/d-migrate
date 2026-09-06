package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.DependencyProjectionStatus
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Oracle laesst abhaengige Sichten beim SPALTEN-Rename invalid zurueck
 * (live gemessen: die Sicht, die die Spalte nennt, geht auf `INVALID`,
 * ihr Rumpf bleibt unveraendert, `SELECT` scheitert mit `ORA-04063` und
 * heilt auch beim zweiten Versuch nicht). `OracleRenameDependencyPolicy`
 * projiziert sie deshalb als einzige der fuenf Policies auch beim
 * Spalten-Rename neu.
 *
 * Dieser Test faehrt bewusst durch [DiffPlanner.plan] statt die Policy
 * direkt aufzurufen: die Absorption der Sicht ist eine Eigenschaft des
 * gesamten Wegs (Policy → Projector → `RenameColumnProjection` →
 * `foldRenameColumns` → `mapTables` → `mapViews`), und genau dort ging
 * sie zuerst verloren.
 */
class OracleColumnRenameViewAbsorptionTest : FunSpec({

    val planner = DiffPlanner()
    val oracle = RenameProjectionCapabilities.fileOnly(RenameProjectionDialect.ORACLE)

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    val beforeCol = ColumnDefinition(type = NeutralType.Text(maxLength = 100))
    val afterCol = ColumnDefinition(type = NeutralType.Text(maxLength = 100))

    fun table(columnName: String, column: ColumnDefinition) = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            columnName to column,
        ),
        primaryKey = listOf("id"),
    )

    fun columnRenameOverlay(): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "oracle",
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "rename-column-email",
                    objectType = "column",
                    fromName = "users.email_addr",
                    toName = "users.email",
                ),
            ),
            createdAt = "2026-09-06T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()
        return MigrationOverlayDocument(source = "ovl/rename.json", overlay = overlay)
    }

    fun diffWithChangedView() = SchemaDiff(
        tablesChanged = listOf(
            TableDiff(
                name = "users",
                columnsAdded = mapOf("email" to afterCol),
                columnsRemoved = mapOf("email_addr" to beforeCol),
            ),
        ),
        viewsChanged = listOf(
            ViewDiff(
                name = "v_users",
                query = ValueChange(
                    "SELECT email_addr FROM users",
                    "SELECT email FROM users",
                ),
            ),
        ),
    )

    test("the column rename absorbs the view -- no third ReplaceView beside the Drop+Create") {
        val current = emptySchema().copy(
            tables = mapOf("users" to table("email_addr", beforeCol)),
            views = mapOf(
                "v_users" to ViewDefinition(
                    query = "SELECT email_addr FROM users",
                    dependencies = DependencyInfo(tables = listOf("users")),
                ),
            ),
        )
        val desired = emptySchema().copy(
            tables = mapOf("users" to table("email", afterCol)),
            views = mapOf(
                "v_users" to ViewDefinition(
                    query = "SELECT email FROM users",
                    dependencies = DependencyInfo(tables = listOf("users")),
                ),
            ),
        )

        val plan = planner.plan(
            current = current, desired = desired, schemaDiff = diffWithChangedView(),
            migrationOverlays = listOf(columnRenameOverlay()), capabilities = oracle,
        )

        val rename = plan.operations.filterIsInstance<DiffOperation.RenameColumn>().single()
        val drop = plan.operations.filterIsInstance<DiffOperation.DropView>().single()
        val create = plan.operations.filterIsInstance<DiffOperation.CreateView>().single()

        create.view.query shouldBe "SELECT email FROM users"
        drop.dependencies shouldContain rename.id
        create.dependencies shouldContain rename.id
        create.dependencies shouldContain drop.id

        // Der eigentliche Punkt: `mapViews` darf fuer die absorbierte
        // Sicht kein zusaetzliches, an nichts gekettetes ReplaceView
        // emittieren.
        plan.operations.filterIsInstance<DiffOperation.ReplaceView>()
            .none { it.objectRef.path == listOf("v_users") } shouldBe true

        // Und der Report muss die Folge-Operationen ausweisen -- er ist
        // der Audit-Traeger fuer das, was im Plan steht.
        val report = plan.renameProjections.single { it.objectType == "column" }
        report.explicit.map { it.kind } shouldBe listOf("VIEW_DROP", "VIEW_CREATE")
    }

    test("a view the desired schema drops does not force the rename onto the destructive path") {
        val current = emptySchema().copy(
            tables = mapOf("users" to table("email_addr", beforeCol)),
            views = mapOf(
                // Nennt die umbenannte Spalte gar nicht und wird im selben
                // Lauf entfernt -- sie darf den Rename nicht blockieren.
                "v_legacy" to ViewDefinition(
                    query = "SELECT id FROM users",
                    dependencies = DependencyInfo(tables = listOf("users")),
                ),
            ),
        )
        val desired = emptySchema().copy(tables = mapOf("users" to table("email", afterCol)))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsAdded = mapOf("email" to afterCol),
                    columnsRemoved = mapOf("email_addr" to beforeCol),
                ),
            ),
        )

        val plan = planner.plan(
            current = current, desired = desired, schemaDiff = diff,
            migrationOverlays = listOf(columnRenameOverlay()), capabilities = oracle,
        )

        plan.operations.filterIsInstance<DiffOperation.RenameColumn>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropColumn>().shouldBeEmpty()
        plan.diagnostics.none { it.code == RENAME_DEPENDENCY_UNPROJECTABLE } shouldBe true
    }

    test("a view with an unusable dependency projection blocks instead of being skipped") {
        val hidden = ViewDefinition(
            query = "SELECT email_addr FROM users",
            // Wie der Oracle-Reader eine Sicht meldet, deren
            // ALL_DEPENDENCIES-Zeilen der lesende Nutzer nicht sieht.
            dependencies = DependencyInfo(
                projectionComplete = false,
                tableProjectionStatus = DependencyProjectionStatus.INCOMPLETE_PRIVILEGE,
            ),
        )
        val current = emptySchema().copy(
            tables = mapOf("users" to table("email_addr", beforeCol)),
            views = mapOf("v_users" to hidden),
        )
        val desired = emptySchema().copy(
            tables = mapOf("users" to table("email", afterCol)),
            views = mapOf("v_users" to hidden),
        )

        val plan = planner.plan(
            current = current, desired = desired, schemaDiff = diffWithChangedView(),
            migrationOverlays = listOf(columnRenameOverlay()), capabilities = oracle,
        )

        // Leere `tables` heisst hier nicht "haengt an nichts" -- still zu
        // ueberspringen liesse die Sicht nach dem Rename gebrochen zurueck.
        plan.diagnostics.map { it.code } shouldContain RENAME_DEPENDENCY_UNPROJECTABLE
    }
})
