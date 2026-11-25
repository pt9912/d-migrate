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
import io.kotest.matchers.string.shouldContain as shouldContainText

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

    // ── `values_constraint` (ADR 0069): der Name steht im Schema, nichts wird geraten ──

    fun named(name: String?, vararg v: String) = NeutralType.Enum(values = v.toList(), valuesConstraintName = name)

    test("CREATE TABLE: die Inline-Enum mit Namen rendert CONSTRAINT <name> CHECK, ohne Namen unbenannt") {
        val generator = PostgresDdlGenerator()
        fun ddl(type: NeutralType) = generator.generate(schema(type)).render()

        ddl(named("ck_a_state", "ACTIVE", "DISABLED")) shouldContainText
            "CONSTRAINT \"ck_a_state\" CHECK (\"state\" IN ('ACTIVE', 'DISABLED'))"
        ddl(named(null, "ACTIVE", "DISABLED")) shouldContainText "\"state\" TEXT CHECK (\"state\" IN ('ACTIVE', 'DISABLED'))"
    }

    test("CreateTable ueber den Diff-Pfad: die benannte Inline-Enum traegt ihren Namen") {
        val type = named("ck_a_state", "ACTIVE", "DISABLED")
        val table = schema(type).tables.getValue("t_a")
        val diff = SchemaDiff(tablesAdded = listOf(dev.dmigrate.core.diff.NamedTable("t_a", table)))

        val r = gen.generateUp(planner.plan(schema(type), schema(type), diff), fileOptions)

        r.statements.joinToString("\n") { it.sql } shouldContainText
            "CONSTRAINT \"ck_a_state\" CHECK (\"state\" IN ('ACTIVE', 'DISABLED'))"
    }

    test("Datei gegen Datei: benannte Enum, erweiterte Liste: der Up loest den alten CHECK unter seinem Namen") {
        val before = named("ck_a_state", "ACTIVE", "DISABLED")
        val after = named("ck_a_state", "ACTIVE", "DISABLED", "LOCKED")

        val r = up(schema(before), schema(after), before, after, fileOptions)

        withClue(r.diagnostics.toString()) { r.isBlocked shouldBe false }
        r.statements.map { it.sql } shouldBe listOf(
            "ALTER TABLE \"t_a\" DROP CONSTRAINT IF EXISTS \"ck_a_state\";",
            "ALTER TABLE \"t_a\" ADD CONSTRAINT \"ck_a_state\" CHECK (\"state\" IN ('ACTIVE', 'DISABLED', 'LOCKED'));",
        )
    }

    test("nur der Name aendert sich: der alte CHECK geht, der neue kommt unter dem neuen Namen") {
        val before = named("ck_old", "ACTIVE", "DISABLED")
        val after = named("ck_new", "ACTIVE", "DISABLED")

        val r = up(schema(before), schema(after), before, after, fileOptions)

        withClue(r.diagnostics.toString()) { r.isBlocked shouldBe false }
        r.statements.map { it.sql } shouldBe listOf(
            "ALTER TABLE \"t_a\" DROP CONSTRAINT IF EXISTS \"ck_old\";",
            "ALTER TABLE \"t_a\" ADD CONSTRAINT \"ck_new\" CHECK (\"state\" IN ('ACTIVE', 'DISABLED'));",
        )
    }

    test("gegen die Datenbank: die Soll-Enum benennt den CHECK, der Katalogname der Ist-Seite wird geloest") {
        val live = schema(
            NeutralType.Text(),
            listOf(
                ConstraintDefinition(
                    name = "t_a_state_check", type = ConstraintType.CHECK,
                    expression = "(state = ANY (ARRAY['ACTIVE'::text, 'DISABLED'::text]))",
                ),
            ),
        )
        val after = named("ck_a_state", "ACTIVE", "DISABLED")

        val r = up(live, schema(after), NeutralType.Text(), after, liveOptions)

        withClue(r.diagnostics.toString()) { r.isBlocked shouldBe false }
        r.statements.map { it.sql } shouldBe listOf(
            "ALTER TABLE \"t_a\" DROP CONSTRAINT IF EXISTS \"t_a_state_check\";",
            "ALTER TABLE \"t_a\" ADD CONSTRAINT \"ck_a_state\" CHECK (\"state\" IN ('ACTIVE', 'DISABLED'));",
        )
    }

    test("Down, benannt: der vom Up angelegte CHECK wird unter seinem Namen geloest, der alte unter seinem hergestellt") {
        val before = named("ck_a_state", "ACTIVE", "DISABLED")
        val after = named("ck_a_state", "ACTIVE", "DISABLED", "LOCKED")

        val r = gen.generateDown(planner.plan(schema(before), schema(after), change(before, after)), fileOptions)

        withClue(r.diagnostics.toString()) { r.isBlocked shouldBe false }
        r.statements.map { it.sql } shouldBe listOf(
            "ALTER TABLE \"t_a\" DROP CONSTRAINT \"ck_a_state\";",
            "ALTER TABLE \"t_a\" ADD CONSTRAINT \"ck_a_state\" CHECK (\"state\" IN ('ACTIVE', 'DISABLED'));",
        )
    }

    test("Down, unbenannt, Datei gegen Datei: weiter blockiert, wie vor ADR 0069") {
        val before = enumOf("ACTIVE", "DISABLED")
        val after = enumOf("ACTIVE", "DISABLED", "LOCKED")

        val r = gen.generateDown(planner.plan(schema(before), schema(after), change(before, after)), fileOptions)

        r.isBlocked shouldBe true
        r.diagnostics.map { it.code } shouldContain "POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN"
    }
})
