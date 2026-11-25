package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * ADR 0069: der Name des Wertevorrat-CHECKs einer Inline-Enum (`values_constraint`).
 *
 * Setzt die **Soll**-Seite einen Namen und die Ist-Seite fuehrt den CHECK unter einem anderen, ist das eine
 * Aenderung an der Spalte: der Up loest den alten CHECK und legt den neuen unter dem Namen an. Ohne gesetzten Namen
 * zaehlt der Name nicht, wie bisher. Wo das Ziel keinen CHECK fuehrt (MySQL), bleibt der Name ohne Wirkung.
 */
class SchemaComparatorValuesConstraintNameTest : FunSpec({

    fun schema(table: TableDefinition) = SchemaDefinition(name = "app", version = "1", tables = mapOf("t" to table))

    /** Wie PostgreSQL die Spalte zurueckliefert: Text plus CHECK unter dem vom Server vergebenen Namen. */
    fun reversed(checkName: String) = schema(TableDefinition(
        columns = mapOf("mood" to ColumnDefinition(NeutralType.Text())),
        constraints = listOf(
            ConstraintDefinition(
                name = checkName, type = ConstraintType.CHECK,
                expression = "(mood = ANY (ARRAY['red'::text, 'green'::text]))",
            ),
        ),
    ))

    fun authored(name: String?, vararg values: String) = schema(TableDefinition(
        columns = mapOf(
            "mood" to ColumnDefinition(NeutralType.Enum(values = values.toList(), valuesConstraintName = name)),
        ),
    ))

    fun target(compareName: Boolean) = TargetProjection(
        type = { if (it is NeutralType.Enum) NeutralType.Text() else it },
        comparesValuesConstraintName = compareName,
    )

    test("ein anderer Name an der Ist-Seite ist eine Aenderung an der Spalte") {
        val diff = SchemaComparator(target(true)).compare(reversed("t_mood_check"), authored("ck_mood", "red", "green"))

        val column = diff.tablesChanged.single().columnsChanged.single()
        column.name shouldBe "mood"
        column.type?.after shouldBe NeutralType.Enum(listOf("red", "green"), valuesConstraintName = "ck_mood")
        // Der CHECK gehoert zur Spalte, nicht in den Constraint-Block.
        diff.tablesChanged.single().constraintsRemoved.shouldBeEmpty()
        diff.tablesChanged.single().constraintsAdded.shouldBeEmpty()
    }

    test("derselbe Name an der Ist-Seite ist keine Aenderung") {
        SchemaComparator(target(true)).compare(reversed("ck_mood"), authored("ck_mood", "red", "green"))
            .tablesChanged.shouldBeEmpty()
    }

    test("ohne gesetzten Namen zaehlt der Name nicht: wie bisher") {
        SchemaComparator(target(true)).compare(reversed("t_mood_check"), authored(null, "red", "green"))
            .tablesChanged.shouldBeEmpty()
    }

    test("wo das Ziel keinen CHECK fuehrt (MySQL), bleibt der Name ohne Wirkung") {
        SchemaComparator(target(false)).compare(reversed("t_mood_check"), authored("ck_mood", "red", "green"))
            .tablesChanged.shouldBeEmpty()
    }

    test("Datei gegen Datei: die Ist-Seite ohne Namen, die Soll-Seite mit Namen, ist eine Aenderung") {
        SchemaComparator(target(true)).compare(authored(null, "red", "green"), authored("ck_mood", "red", "green"))
            .tablesChanged.single().columnsChanged.single().type?.after shouldBe
            NeutralType.Enum(listOf("red", "green"), valuesConstraintName = "ck_mood")
    }

    test("Datei gegen Datei: zwei gleiche Namen sind keine Aenderung, zwei verschiedene sind eine") {
        SchemaComparator(target(true)).compare(authored("ck_a", "red", "green"), authored("ck_a", "red", "green"))
            .tablesChanged.shouldBeEmpty()
        SchemaComparator(target(true)).compare(authored("ck_a", "red", "green"), authored("ck_b", "red", "green"))
            .tablesChanged.single().columnsChanged.single().name shouldBe "mood"
    }

    test("ein geaenderter Wertevorrat bleibt eine Aenderung, auch mit gleichem Namen") {
        SchemaComparator(target(true)).compare(authored("ck_mood", "red", "green"), authored("ck_mood", "red", "blue"))
            .tablesChanged.single().columnsChanged.single().name shouldBe "mood"
    }

    test("strikt (schema compare): zwei Enums mit verschiedenem Namen unterscheiden sich") {
        SchemaComparator().compare(authored("ck_a", "red", "green"), authored("ck_b", "red", "green"))
            .tablesChanged.single().columnsChanged.single().name shouldBe "mood"
    }

    test("der Name steht im Fingerabdruck nicht: zwei Enums, die sich nur im Namen unterscheiden, hashen gleich") {
        // Die gelesene Datenbankseite traegt den vom Server vergebenen Namen; stuende der Name im Hash,
        // unterschieden sich beide nach jedem `--execute`.
        val a = dev.dmigrate.core.diff.migration.MigrationFingerprint.compute(authored("ck_a", "red", "green"))
        val b = dev.dmigrate.core.diff.migration.MigrationFingerprint.compute(authored("ck_b", "red", "green"))
        val none = dev.dmigrate.core.diff.migration.MigrationFingerprint.compute(authored(null, "red", "green"))
        (a == b && a == none) shouldBe true
    }
})
