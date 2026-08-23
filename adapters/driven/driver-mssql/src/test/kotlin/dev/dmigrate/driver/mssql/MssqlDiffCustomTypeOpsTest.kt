package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.CustomTypeDiff
import dev.dmigrate.core.diff.NamedCustomType
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Sub-Slice 5c: Custom Types.
 *
 * T-SQL hat fuer Enum und Domain kein eigenes Objekt — beide loest der
 * Generate-Pfad an der Spalte auf. Anlegen und Loeschen sind hier deshalb
 * gegenstandslos; bezahlt wird beim Aendern, weil jede nutzende Spalte ihre
 * eigene Kopie der Werte traegt.
 */
class MssqlDiffCustomTypeOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    val mood = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("red", "green"))
    val moodWide = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("red", "green", "yellow"))

    fun schema(types: Map<String, CustomTypeDefinition>, tables: Map<String, TableDefinition> = emptyMap()) =
        SchemaDefinition(name = "App", version = "1", tables = tables, customTypes = types)

    fun up(diff: SchemaDiff, current: SchemaDefinition, desired: SchemaDefinition) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun down(diff: SchemaDiff, current: SchemaDefinition, desired: SchemaDefinition) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    /** Eine Tabelle, deren Spalte den Typ ueber `refType` nutzt. */
    fun users(refType: String) = TableDefinition(
        columns = linkedMapOf("mood" to ColumnDefinition(NeutralType.Enum(refType = refType))),
    )

    test("creating an enum type renders no object — it lives at its columns") {
        val r = up(
            SchemaDiff(customTypesAdded = listOf(NamedCustomType("mood", mood))),
            schema(emptyMap()),
            schema(mapOf("mood" to mood)),
        )
        r.blockers.shouldBeEmpty()
        r.statements.single().sql shouldContainStr "is created at its columns, not as an object"
    }

    test("dropping an enum type likewise leaves nothing to drop") {
        val r = up(
            SchemaDiff(customTypesRemoved = listOf(NamedCustomType("mood", mood))),
            schema(mapOf("mood" to mood)),
            schema(emptyMap()),
        )
        r.blockers.shouldBeEmpty()
        r.statements.single().sql shouldContainStr "is dropped at its columns"
    }

    test("a composite type is blocked — SQL Server has no equivalent, as in the generate path") {
        val composite = CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE)
        val r = up(
            SchemaDiff(customTypesAdded = listOf(NamedCustomType("addr", composite))),
            schema(emptyMap()),
            schema(mapOf("addr" to composite)),
        )
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "E054"
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("changing the values fans out to every column that uses the type") {
        // Wo PostgreSQL `ALTER TYPE … ADD VALUE` kennt, traegt in SQL Server
        // jede Spalte ihre eigene Breite und ihren eigenen CHECK.
        val current = schema(mapOf("mood" to mood), mapOf("users" to users("mood")))
        val desired = schema(mapOf("mood" to moodWide), mapOf("users" to users("mood")))
        val diff = SchemaDiff(
            customTypesChanged = listOf(
                CustomTypeDiff(name = "mood", values = ValueChange(mood.values!!, moodWide.values!!)),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        val drop = sqls.indexOfFirst { it.contains("DROP CONSTRAINT IF EXISTS [ck_users_mood]") }
        val alter = sqls.indexOfFirst { it.contains("ALTER COLUMN [mood] NVARCHAR(6)") }
        val readd = sqls.indexOfFirst { it.contains("ADD CONSTRAINT [ck_users_mood]") }
        (drop in 0 until alter) shouldBe true
        (readd > alter) shouldBe true
        sqls[readd] shouldContainStr "N'yellow'"
    }

    test("the fan-out says which columns it touched") {
        val current = schema(mapOf("mood" to mood), mapOf("users" to users("mood")))
        val desired = schema(mapOf("mood" to moodWide), mapOf("users" to users("mood")))
        val diff = SchemaDiff(
            customTypesChanged = listOf(
                CustomTypeDiff(name = "mood", values = ValueChange(mood.values!!, moodWide.values!!)),
            ),
        )
        val r = up(diff, current, desired)
        r.diagnostics.single { it.code == "MSSQL_CUSTOM_TYPE_FANNED_OUT" }
            .message shouldContainStr "'users.mood'"
    }

    test("a type nobody uses changes nothing but says so") {
        val current = schema(mapOf("mood" to mood))
        val desired = schema(mapOf("mood" to moodWide))
        val diff = SchemaDiff(
            customTypesChanged = listOf(
                CustomTypeDiff(name = "mood", values = ValueChange(mood.values!!, moodWide.values!!)),
            ),
        )
        up(diff, current, desired).statements.single().sql shouldContainStr "no column uses it"
    }

    test("down of a type change is blocked: the planner defines no inverse for it") {
        val current = schema(mapOf("mood" to mood), mapOf("users" to users("mood")))
        val desired = schema(mapOf("mood" to moodWide), mapOf("users" to users("mood")))
        val diff = SchemaDiff(
            customTypesChanged = listOf(
                CustomTypeDiff(name = "mood", values = ValueChange(mood.values!!, moodWide.values!!)),
            ),
        )
        val r = down(diff, current, desired)
        r.statements.shouldBeEmpty()
        r.diagnostics.map { it.code } shouldContain "ROLLBACK_NOT_POSSIBLE"
    }
})
