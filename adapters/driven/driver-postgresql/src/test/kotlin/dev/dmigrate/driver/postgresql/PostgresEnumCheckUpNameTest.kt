package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.PostgresServerVersion
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Issue #81: Datei gegen Datei kennt der Up den Namen des alten Wertevorrat-CHECKs einer
 * Inline-Enum nicht. Frueher legte er dann nur den neuen CHECK an und liess den alten stehen
 * (Exit 0); der alte wies neue Werte weiter ab. Jetzt blockiert er wie der Down in derselben Lage.
 */
class PostgresEnumCheckUpNameTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun schema(column: NeutralType, constraints: List<ConstraintDefinition> = emptyList()) = SchemaDefinition(
        name = "s", version = "1",
        tables = mapOf(
            "t_a" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "state" to ColumnDefinition(column),
                ),
                primaryKey = listOf("id"),
                constraints = constraints,
            ),
        ),
    )

    fun enumOf(vararg v: String) = NeutralType.Enum(values = v.toList())

    fun change(from: NeutralType, to: NeutralType) = SchemaDiff(
        tablesChanged = listOf(
            TableDiff(name = "t_a", columnsChanged = listOf(ColumnDiff(name = "state", type = ValueChange(from, to)))),
        ),
    )

    val fileOptions = DdlGenerationOptions()
    val fileWithVersion = DdlGenerationOptions(
        dialectContext = DdlDialectContext.Postgres(serverVersion = PostgresServerVersion(18, 0)),
    )
    val liveOptions = DdlGenerationOptions(
        dialectContext = DdlDialectContext.Postgres(serverVersion = PostgresServerVersion(18, 0), catalogRead = true),
    )

    fun up(current: SchemaDefinition, desired: SchemaDefinition, from: NeutralType, to: NeutralType, options: DdlGenerationOptions) =
        gen.generateUp(planner.plan(current, desired, change(from, to)), options)

    for ((label, values) in listOf(
        "erweiterte Liste (Issue #81)" to arrayOf("ACTIVE", "DISABLED", "LOCKED"),
        "verkuerzte Liste" to arrayOf("ACTIVE"),
    )) {
        test("Datei gegen Datei, $label: der Up blockiert statt nur einen zweiten CHECK anzulegen") {
            val before = enumOf("ACTIVE", "DISABLED")
            val after = enumOf(*values)

            val r = up(schema(before), schema(after), before, after, fileOptions)

            withClue(r.statements.map { it.sql }.toString()) {
                r.isBlocked shouldBe true
                r.statements.shouldBeEmpty()
                r.diagnostics.map { it.code } shouldContain PostgresEnumCheckUpName.CODE
                r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
            }
        }
    }

    test("--target-version allein ist kein Katalog: weiterhin blockiert") {
        val before = enumOf("ACTIVE", "DISABLED")
        val after = enumOf("ACTIVE", "DISABLED", "LOCKED")

        up(schema(before), schema(after), before, after, fileWithVersion).isBlocked shouldBe true
    }

    test("gegen die Datenbank kennt der Up den Katalognamen: DROP des alten, ADD des neuen") {
        val live = schema(
            NeutralType.Text(),
            listOf(
                ConstraintDefinition(
                    name = "t_a_state_check", type = ConstraintType.CHECK,
                    expression = "(state = ANY (ARRAY['ACTIVE'::text, 'DISABLED'::text]))",
                ),
            ),
        )
        val after = enumOf("ACTIVE", "DISABLED", "LOCKED")

        val r = up(live, schema(after), NeutralType.Text(), after, liveOptions)

        withClue(r.diagnostics.toString()) { r.isBlocked shouldBe false }
        val sql = r.statements.map { it.sql }
        sql shouldBe listOf(
            "ALTER TABLE \"t_a\" DROP CONSTRAINT IF EXISTS \"t_a_state_check\";",
            "ALTER TABLE \"t_a\" ADD CHECK (\"state\" IN ('ACTIVE', 'DISABLED', 'LOCKED'));",
        )
    }

    test("eine Textspalte, die erst einen Wertevorrat bekommt, braucht keinen alten Namen") {
        val after = enumOf("ACTIVE", "DISABLED")

        val r = up(schema(NeutralType.Text()), schema(after), NeutralType.Text(), after, fileOptions)

        withClue(r.diagnostics.toString()) { r.isBlocked shouldBe false }
        r.statements.map { it.sql } shouldBe listOf("ALTER TABLE \"t_a\" ADD CHECK (\"state\" IN ('ACTIVE', 'DISABLED'));")
    }
})
