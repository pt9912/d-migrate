package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.JdbcMigrationStatementExecutor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.sql.DriverManager

/**
 * Der Neubau wegen eines engeren Wertevorrats kopiert die Zeilen mit
 * `INSERT ... SELECT`. Traegt eine Zeile einen entfernten Wert, bricht das ab —
 * und die Tabelle muss mit allen Zeilen bleiben, der Fehler der Operation
 * zugeordnet. Der Preflight faengt den Fall vorher ab; hier wird er umgangen
 * (keine Deklaration), damit der Apply-Pfad selbst geprueft ist.
 */
class SqliteRebuildAtomicityTest : FunSpec({

    val wide = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "b"))

    fun schema(type: NeutralType, types: Map<String, CustomTypeDefinition>) = SchemaDefinition(
        name = "App", version = "1", customTypes = types,
        tables = mapOf(
            "u" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "e" to ColumnDefinition(type),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    test("ein Neubau mit verletzender Zeile bricht ab, rollt zurueck und laesst die Tabelle unveraendert") {
        val before = schema(NeutralType.Text(), emptyMap())
        val after = schema(NeutralType.Enum(refType = "ee"), mapOf("ee" to wide))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        ColumnDiff(name = "e", type = ValueChange(NeutralType.Text(), NeutralType.Enum(refType = "ee"))),
                    ),
                ),
            ),
        )
        val plan = DiffPlanner().plan(before, after, diff)
        val opId = plan.operations.filterIsInstance<DiffOperation.AlterColumnType>().single().id
        val statements = SqliteDiffDdlGenerator().generateUp(plan, DdlGenerationOptions()).statements

        val db = Files.createTempFile("rebuild-atomicity", ".sqlite")
        DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE u (id INTEGER NOT NULL, e TEXT CHECK (\"e\" IN ('a', 'b', 'c')), PRIMARY KEY (id))")
                it.execute("INSERT INTO u (id, e) VALUES (1, 'a'), (2, 'c')")
            }

            val trace = JdbcMigrationStatementExecutor.runAll(conn, statements)

            trace.transactionRolledBack shouldBe true
            trace.executionError!! shouldContain "CHECK constraint failed"
            trace.lastStatementOperationIds shouldContain opId
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM u").use { rs -> rs.next(); rs.getInt(1) shouldBe 2 }
                st.executeQuery("SELECT sql FROM sqlite_master WHERE name = 'u'").use { rs ->
                    rs.next()
                    rs.getString(1) shouldContain "'c'"
                }
                st.executeQuery("SELECT name FROM sqlite_master WHERE name LIKE '%dmg_rebuild%'").use { rs ->
                    rs.next() shouldBe false
                }
            }
        }
    }
})
