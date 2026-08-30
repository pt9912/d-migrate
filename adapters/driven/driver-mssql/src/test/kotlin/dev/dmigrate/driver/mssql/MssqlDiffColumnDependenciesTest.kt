package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Der Abhaengigkeits-Tanz um eine Spaltenaenderung: abraeumen davor,
 * wiederherstellen danach.
 *
 * Beide Richtungen muessen sich decken. Was abgeraeumt und nicht wieder
 * angelegt wird, verschwindet still aus der Datenbank; was uebersehen und
 * deshalb nicht abgeraeumt wird, laesst `ALTER COLUMN` mit Msg 5074
 * scheitern. Die Faelle hier sind die, an denen die beiden Seiten schon
 * einmal auseinandergelaufen sind.
 */
class MssqlDiffColumnDependenciesTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    fun schema(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables.toMap())

    fun up(diff: SchemaDiff, current: SchemaDefinition, desired: SchemaDefinition) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun down(diff: SchemaDiff, current: SchemaDefinition, desired: SchemaDefinition) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    test("a column-level foreign key is restored after the column change, not just dropped") {
        // Es wird abgeraeumt, weil ALTER COLUMN sonst mit Msg 5074 scheitert —
        // und wieder angelegt, weil die Beziehung sonst still verschwaende.
        val orders = TableDefinition(
            columns = linkedMapOf(
                "user_id" to ColumnDefinition(
                    NeutralType.Integer,
                    references = ReferenceDefinition(table = "users", column = "id"),
                ),
            ),
        )
        val users = TableDefinition(
            columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val current = schema("users" to users, "orders" to orders)
        val desired = schema(
            "users" to users,
            "orders" to orders.copy(
                columns = linkedMapOf(
                    "user_id" to ColumnDefinition(
                        NeutralType.BigInteger,
                        references = ReferenceDefinition(table = "users", column = "id"),
                    ),
                ),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "orders",
                    columnsChanged = listOf(
                        ColumnDiff(
                            name = "user_id",
                            type = ValueChange(NeutralType.Integer, NeutralType.BigInteger),
                        ),
                    ),
                ),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        sqls.any { it.contains("sys.foreign_keys") } shouldBe true
        sqls.last() shouldContainStr "ADD CONSTRAINT [fk_orders_user_id] FOREIGN KEY ([user_id]) REFERENCES [users]([id])"
    }

    test("an inbound column-level reference is dropped before ALTER COLUMN — Msg 5074 otherwise") {
        // Abwaerts ist das massgebliche Schema das SOLL, und das kommt aus YAML;
        // dort darf ein Fremdschluessel als `references` an der Spalte stehen.
        val orders = TableDefinition(
            columns = linkedMapOf(
                "user_id" to ColumnDefinition(
                    NeutralType.Integer,
                    references = ReferenceDefinition(table = "users", column = "id"),
                ),
            ),
        )
        val usersBefore = TableDefinition(
            columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
        )
        val ordersBefore = orders.copy(columns = linkedMapOf("user_id" to ColumnDefinition(NeutralType.Integer)))
        val current = schema("users" to usersBefore, "orders" to ordersBefore)
        val desired = schema(
            "users" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(NeutralType.BigInteger, required = true)),
            ),
            "orders" to orders,
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
        val sqls = down(diff, current, desired).statements.map { it.sql }
        sqls.any { it.contains("DROP CONSTRAINT IF EXISTS [fk_orders_user_id]") } shouldBe true
    }

    test("down drops a foreign key its own inverse re-created earlier in the same run") {
        // Abwaerts laeuft CONSTRAINTS vor COLUMNS: die Umkehr des
        // `DropConstraint` legt den Fremdschluessel wieder an, BEVOR der
        // Spaltentanz kommt. Das Soll-Schema kennt ihn nicht — wer nur dort
        // nachsieht, laesst ihn stehen und faehrt in Msg 5074.
        val fk = ConstraintDefinition(
            name = "fk_orders_user",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val orders = TableDefinition(columns = linkedMapOf("user_id" to ColumnDefinition(NeutralType.Integer)))
        val usersBefore = TableDefinition(
            columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
        )
        val current = schema("users" to usersBefore, "orders" to orders.copy(constraints = listOf(fk)))
        val desired = schema(
            "users" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(NeutralType.BigInteger, required = true)),
            ),
            "orders" to orders,
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    columnsChanged = listOf(
                        ColumnDiff(name = "id", type = ValueChange(NeutralType.Integer, NeutralType.BigInteger)),
                    ),
                ),
                TableDiff(name = "orders", constraintsRemoved = listOf(fk)),
            ),
        )
        val sqls = down(diff, current, desired).statements.map { it.sql }
        val readd = sqls.indexOfFirst { it.contains("ADD CONSTRAINT [fk_orders_user]") }
        val alter = sqls.indexOfFirst { it.contains("ALTER COLUMN [id] INT") }
        val drop = sqls.indexOfFirst { it.contains("DROP CONSTRAINT IF EXISTS [fk_orders_user]") }
        (readd in 0 until alter) shouldBe true
        (drop in readd until alter) shouldBe true
    }

    test("AddColumn creates the foreign key of a column-level reference") {
        // Der Generate-Pfad rendert die spaltenstaendige Form; ohne sie hier
        // verloere eine per `migrate` angelegte Spalte ihre Beziehung still.
        val newCol = ColumnDefinition(
            NeutralType.Integer,
            references = ReferenceDefinition(table = "users", column = "id"),
        )
        val users = TableDefinition(
            columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val orders = TableDefinition(columns = linkedMapOf("label" to ColumnDefinition(NeutralType.Text(10))))
        val current = schema("users" to users, "orders" to orders)
        val desired = schema(
            "users" to users,
            "orders" to orders.copy(
                columns = linkedMapOf("label" to ColumnDefinition(NeutralType.Text(10)), "user_id" to newCol),
            ),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "orders", columnsAdded = mapOf("user_id" to newCol))),
        )
        val r = up(diff, current, desired)
        // Derselbe Name wie im Generate-Pfad, aus derselben Funktion.
        r.statements.map { it.sql }.any {
            it.contains("ADD CONSTRAINT [fk_orders_user_id] FOREIGN KEY")
        } shouldBe true
        r.diagnostics.map { it.code } shouldNotContain "MSSQL_COLUMN_REFERENCE_NOT_RENDERED"
    }

    test("CreateTable creates it inline, like the generate path") {
        // Die haeufigere Form der Luecke: eine ganz neue Tabelle, deren Spalte
        // ein `references` traegt.
        val newCol = ColumnDefinition(
            NeutralType.Integer,
            references = ReferenceDefinition(table = "users", column = "id"),
        )
        val users = TableDefinition(
            columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val orders = TableDefinition(columns = linkedMapOf("user_id" to newCol))
        val current = schema("users" to users)
        val desired = schema("users" to users, "orders" to orders)
        val r = up(SchemaDiff(tablesAdded = listOf(NamedTable("orders", orders))), current, desired)
        r.statements.map { it.sql }.any {
            it.contains("CONSTRAINT [fk_orders_user_id] FOREIGN KEY")
        } shouldBe true
        r.diagnostics.map { it.code } shouldNotContain "MSSQL_COLUMN_REFERENCE_NOT_RENDERED"
    }

    test("the warning stays quiet when the constraint list declares the same relationship") {
        // Dann entsteht der Fremdschluessel ueber die eigene Operation — es
        // fehlt nichts, und eine Warnung waere ein Fehlalarm.
        val ref = ReferenceDefinition(table = "users", column = "id")
        val declared = ConstraintDefinition(
            name = "fk_orders_user_id",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
        )
        val newCol = ColumnDefinition(NeutralType.Integer, references = ref)
        val users = TableDefinition(
            columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
        )
        val orders = TableDefinition(columns = linkedMapOf("user_id" to newCol), constraints = listOf(declared))
        val current = schema("users" to users)
        val desired = schema("users" to users, "orders" to orders)
        val r = up(SchemaDiff(tablesAdded = listOf(NamedTable("orders", orders))), current, desired)
        r.statements.map { it.sql }.any { it.contains("[fk_orders_user_id]") } shouldBe true
        r.diagnostics.map { it.code }.none { it == "MSSQL_COLUMN_REFERENCE_NOT_RENDERED" } shouldBe true
    }
})
