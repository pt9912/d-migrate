package dev.dmigrate.driver.oracle

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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Sub-Slice 5c, Custom-Type-Haelfte. Oracle fuehrt fuer ENUM und DOMAIN kein
 * Datenbankobjekt — beide leben an der Spalte. Create/Drop erzeugen deshalb
 * keine Anweisung, buchen die Operation aber als erledigt und legen die
 * Begruendung als INFO-Diagnose ab; eine geaenderte ENUM faechert auf die
 * nutzenden Spalten auf.
 */
class OracleDiffCustomTypeOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = OracleDiffDdlGenerator()

    val moodEnum = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("happy", "sad"))
    val moodEnumWider = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("happy", "sad", "confused"))

    /** Schema mit einer Spalte, die den Typ nutzt — auf beiden Seiten bekannt. */
    fun schemaUsing(type: CustomTypeDefinition) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            "users" to TableDefinition(
                columns = mapOf("mood" to ColumnDefinition(NeutralType.Enum(refType = "mood"))),
            ),
        ),
        customTypes = mapOf("mood" to type),
    )

    /** Der Wertevorrat-Wechsel, den alle Fan-out-Tests fahren. */
    val widened = SchemaDiff(
        customTypesChanged = listOf(
            CustomTypeDiff(name = "mood", values = ValueChange(moodEnum.values!!, moodEnumWider.values!!)),
        ),
    )

    fun up(diff: SchemaDiff, current: SchemaDefinition, desired: SchemaDefinition) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun down(diff: SchemaDiff, current: SchemaDefinition, desired: SchemaDefinition) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    test("CreateCustomType renders no statement — Oracle has no type object — but counts as done") {
        // Der Vertrag laesst "rendered ohne Anweisung" ausdruecklich zu; die
        // Begruendung gehoert in die Diagnosen, nicht als Kommentar ins Skript.
        val diff = SchemaDiff(customTypesAdded = listOf(NamedCustomType("mood", moodEnum)))
        val r = up(diff, schemaUsing(moodEnum), schemaUsing(moodEnum))
        r.statements.shouldBeEmpty()
        r.operationsRendered.size shouldBe 1
        r.operationsSkipped.shouldBeEmpty()
        r.isBlocked shouldBe false
        val note = r.diagnostics.single { it.code == "ORACLE_CUSTOM_TYPE_AT_COLUMN" }
        note.message shouldContain "is created at its columns"
        note.message shouldContain "VARCHAR2 + CHECK"
    }

    test("the verb flips with the direction so a rollback script does not claim the opposite") {
        val diff = SchemaDiff(customTypesAdded = listOf(NamedCustomType("mood", moodEnum)))
        down(diff, schemaUsing(moodEnum), schemaUsing(moodEnum))
            .diagnostics.single { it.code == "ORACLE_CUSTOM_TYPE_AT_COLUMN" }
            .message shouldContain "is dropped at its columns"
    }

    test("a DOMAIN records its CLOB shape") {
        val domain = CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "text")
        val diff = SchemaDiff(customTypesAdded = listOf(NamedCustomType("d", domain)))
        up(diff, schemaUsing(moodEnum), schemaUsing(moodEnum))
            .diagnostics.single { it.code == "ORACLE_CUSTOM_TYPE_AT_COLUMN" }
            .message shouldContain "CLOB"
    }

    test("a COMPOSITE type blocks with E054 — Oracle cannot express it") {
        val composite = CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE, fields = emptyMap())
        val diff = SchemaDiff(customTypesAdded = listOf(NamedCustomType("addr", composite)))
        val r = up(diff, schemaUsing(moodEnum), schemaUsing(moodEnum))
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.diagnostics.any { it.code == "E054" } shouldBe true
    }

    test("AlterCustomType fans out to the using columns: drop CHECK, widen, re-add CHECK — in that order") {
        val r = up(widened, schemaUsing(moodEnum), schemaUsing(moodEnumWider))
        r.statements.map { it.sql } shouldBe listOf(
            "ALTER TABLE \"users\" DROP CONSTRAINT \"ck_users_mood\";",
            // "confused" ist der laengste Wert -> Breite 8
            "ALTER TABLE \"users\" MODIFY \"mood\" VARCHAR2(8);",
            "ALTER TABLE \"users\" ADD CONSTRAINT \"ck_users_mood\" CHECK (\"mood\" IN ('happy', 'sad', 'confused'));",
        )
        r.diagnostics.any { it.code == "ORACLE_CUSTOM_TYPE_FANNED_OUT" } shouldBe true
    }

    test("AlterCustomType with no using column emits a note, not a blocker") {
        val bare = SchemaDefinition(name = "App", version = "1", customTypes = mapOf("mood" to moodEnum))
        val r = up(widened, bare, bare)
        r.statements.shouldBeEmpty()
        r.operationsRendered.size shouldBe 1
        r.diagnostics.any { it.code == "ORACLE_CUSTOM_TYPE_NO_USERS" } shouldBe true
        r.isBlocked shouldBe false
    }

    test("AlterCustomType blocks without a schema — the using columns are not discoverable") {
        val plan = planner.plan(schemaUsing(moodEnum), schemaUsing(moodEnumWider), widened)
        val r = gen.generateUp(plan.copy(currentSchema = null, desiredSchema = null), DdlGenerationOptions())
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_TABLE_NOT_IN_SCHEMA" } shouldBe true
    }

    test("a column the opposite side does not know is left alone — CreateTable already renders the new width") {
        // `mood` existiert nur im Ziel: die Tabelle entsteht erst in Phase
        // TABLES, ein ALTER darauf liefe ins Leere.
        val bare = SchemaDefinition(name = "App", version = "1", customTypes = mapOf("mood" to moodEnum))
        val r = up(widened, bare, schemaUsing(moodEnumWider))
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_CUSTOM_TYPE_NO_USERS" } shouldBe true
    }

    test("DropCustomType renders no statement either, with the dropped verb") {
        val diff = SchemaDiff(customTypesRemoved = listOf(NamedCustomType("mood", moodEnum)))
        val r = up(diff, schemaUsing(moodEnum), schemaUsing(moodEnum))
        r.statements.shouldBeEmpty()
        r.operationsRendered.size shouldBe 1
        r.diagnostics.single { it.code == "ORACLE_CUSTOM_TYPE_AT_COLUMN" }
            .message shouldContain "is dropped at its columns"
    }

    test("AlterCustomType blocks in the down direction instead of throwing") {
        // `AlterCustomType` ist MANUAL_REQUIRED mit risks.down == null. Ohne
        // den Dispatcher-Waechter liefe `riskFor` in ein error(...) — eine
        // Exception statt des Blockers, den der Port verlangt.
        val r = down(widened, schemaUsing(moodEnum), schemaUsing(moodEnumWider))
        r.statements.shouldBeEmpty()
        r.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("a DOMAIN change renders nothing — its Oracle column shape carries no values") {
        val before = CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "text")
        val after = CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "varchar")
        val diff = SchemaDiff(
            customTypesChanged = listOf(CustomTypeDiff(name = "mood", baseType = ValueChange("text", "varchar"))),
        )
        val r = up(diff, schemaUsing(before), schemaUsing(after))
        r.statements.shouldBeEmpty()
        r.diagnostics.single { it.code == "ORACLE_CUSTOM_TYPE_AT_COLUMN" }.message shouldContain "CLOB"
    }

    test("a column that is not yet an enum on the other side is left to its own AlterColumnType") {
        // Typwechsel im selben Diff: zur TYPES-Phase traegt die Spalte weder
        // Breite noch CHECK — der Fan-out darf sie nicht anfassen.
        val plainBefore = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("users" to TableDefinition(columns = mapOf("mood" to ColumnDefinition(NeutralType.Text())))),
            customTypes = mapOf("mood" to moodEnum),
        )
        val r = up(widened, plainBefore, schemaUsing(moodEnumWider))
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_CUSTOM_TYPE_NO_USERS" } shouldBe true
    }

    test("without a prior CHECK the fan-out only widens and adds — no DROP CONSTRAINT into the void") {
        val valueless = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = null)
        val diff = SchemaDiff(
            customTypesChanged = listOf(CustomTypeDiff(name = "mood", values = ValueChange(emptyList(), moodEnumWider.values!!))),
        )
        val r = up(diff, schemaUsing(valueless), schemaUsing(moodEnumWider))
        r.statements.map { it.sql } shouldBe listOf(
            "ALTER TABLE \"users\" MODIFY \"mood\" VARCHAR2(8);",
            "ALTER TABLE \"users\" ADD CONSTRAINT \"ck_users_mood\" CHECK (\"mood\" IN ('happy', 'sad', 'confused'));",
        )
    }
})
