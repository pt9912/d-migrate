package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Der Wertevorrat einer Spalte zaehlt gleich, in welcher Darstellung er auch
 * vorliegt — am Spaltentyp oder als eigener `IN`-CHECK daneben.
 *
 * Der Fingerabdruck bringt beide Darstellungen schon auf dieselbe Form. Tut der
 * Vergleich es nicht, plant der Lauf gegen ein frisch migriertes Ziel das
 * Loesen genau des CHECKs, den er eben angelegt hat: die Werte-Durchsetzung
 * verschwindet, und der Post-Compare meldet danach Drift.
 */
class SchemaComparatorEnumCheckTest : FunSpec({

    fun schema(table: TableDefinition) =
        SchemaDefinition(name = "app", version = "1", tables = mapOf("t" to table))

    /** Wie ein Dialekt ohne Enum-Typ die Spalte zurueckliefert. */
    fun reversed(expression: String, width: Int = 5) = schema(TableDefinition(
        columns = mapOf("mood" to ColumnDefinition(NeutralType.Text(width))),
        constraints = listOf(
            ConstraintDefinition(name = "ck_t_mood", type = ConstraintType.CHECK, expression = expression),
        ),
    ))

    /** Wie das Soll sie fuehrt. */
    fun authored(values: List<String>) = schema(TableDefinition(
        columns = mapOf("mood" to ColumnDefinition(NeutralType.Enum(values = values))),
    ))

    /** Ein Ziel, das `enum` als Textspalte ablegt. */
    val targetAware = TargetProjection(
        type = { if (it is NeutralType.Enum) NeutralType.Text(it.values?.maxOf { v -> v.length } ?: 0) else it },
    )

    test("ein zurueckgelesener Wertevorrat gleicht dem authored enum") {
        val diff = SchemaComparator(targetAware)
            .compare(reversed("mood='green' OR mood='red'"), authored(listOf("red", "green")))

        diff.tablesChanged.shouldBeEmpty()
    }

    test("dieselbe Aussage in der IN-Schreibweise ebenso") {
        val diff = SchemaComparator(targetAware)
            .compare(reversed("mood IN ('red', 'green')"), authored(listOf("red", "green")))

        diff.tablesChanged.shouldBeEmpty()
    }

    test("ein geaenderter Wertevorrat bleibt eine Aenderung an der Spalte") {
        // Der Zieldialekt faltet `enum` und Textspalte auf denselben Typ. Ohne
        // eigene Dimension fuer die WERTE faende der Vergleich hier nichts —
        // und die Migration liesse den alten Wertevorrat stehen.
        val diff = SchemaComparator(targetAware)
            .compare(reversed("mood IN ('red', 'green')"), authored(listOf("red", "brown")))

        val table = diff.tablesChanged.single()
        table.columnsChanged.single().name shouldBe "mood"
        table.columnsChanged.single().type.shouldNotBeNull()
        // Nicht als Constraint-Kante: der CHECK gehoert zur Spalte, und der
        // Neubau der Spalte schreibt ihn mit.
        table.constraintsRemoved.shouldBeEmpty()
        table.constraintsAdded.shouldBeEmpty()
    }

    test("schema compare bleibt strikt — dort ist die Darstellung selbst der Unterschied") {
        val diff = SchemaComparator()
            .compare(reversed("mood IN ('red', 'green')"), authored(listOf("red", "green")))

        diff.tablesChanged.single().constraintsRemoved.single().name shouldBe "ck_t_mood"
    }

    test("zwei passende CHECKs auf derselben Spalte bleiben Constraints") {
        // Welcher von beiden den Wertevorrat beschreibt, ist nicht
        // entscheidbar — einen davon in die Spalte zu falten liesse den
        // anderen samt seinem Unterschied verschwinden.
        val twoChecks = schema(TableDefinition(
            columns = mapOf("mood" to ColumnDefinition(NeutralType.Text(5))),
            constraints = listOf(
                ConstraintDefinition(name = "ck_a", type = ConstraintType.CHECK, expression = "mood IN ('red', 'green')"),
                ConstraintDefinition(name = "ck_b", type = ConstraintType.CHECK, expression = "mood IN ('red')"),
            ),
        ))

        val diff = SchemaComparator(targetAware).compare(twoChecks, authored(listOf("red", "green")))

        diff.tablesChanged.single().constraintsRemoved.map { it.name } shouldBe listOf("ck_a", "ck_b")
    }

    test("ein handgeschriebener CHECK, der dem Spaltentyp widerspricht, bleibt sichtbar") {
        val bothSides = schema(TableDefinition(
            columns = mapOf("mood" to ColumnDefinition(NeutralType.Enum(values = listOf("red", "green")))),
            constraints = listOf(
                ConstraintDefinition(name = "ck", type = ConstraintType.CHECK, expression = "mood IN ('blue')"),
            ),
        ))

        val diff = SchemaComparator(targetAware).compare(bothSides, authored(listOf("red", "green")))

        diff.tablesChanged.single().constraintsRemoved.single().name shouldBe "ck"
    }

    // ── Issue #76: der Soll-Wertevorrat steht nur in einem CHECK ──────────

    /** Eine Textspalte, deren Wertevorrat ein benannter CHECK aufzaehlt — im Soll wie im Ist. */
    fun checked(expression: String?, width: Int = 10, name: String = "ck_t_mood") = schema(TableDefinition(
        columns = mapOf("mood" to ColumnDefinition(NeutralType.Text(width))),
        constraints = listOfNotNull(
            expression?.let { ConstraintDefinition(name = name, type = ConstraintType.CHECK, expression = it) },
        ),
    ))

    test("#76: eine geaenderte Werteliste im CHECK bleibt eine Constraint-Aenderung") {
        // Gefaltet trueg keine Operation die neue Liste: die Spaltenaenderung hat
        // nur den Texttyp, der CHECK waere aus dem Constraint-Vergleich verschwunden.
        val diff = SchemaComparator(targetAware)
            .compare(checked("mood IN ('red', 'green')"), checked("mood IN ('red', 'green', 'blue')"))

        val table = diff.tablesChanged.single()
        table.columnsChanged.shouldBeEmpty()
        val change = table.constraintsChanged.single()
        change.before.expression shouldBe "mood IN ('red', 'green')"
        change.after.expression shouldBe "mood IN ('red', 'green', 'blue')"
    }

    test("#76: dasselbe gegen die zurueckgelesene PostgreSQL-Schreibweise") {
        val diff = SchemaComparator(targetAware).compare(
            checked("((mood = ANY (ARRAY['red'::text, 'green'::text])))"),
            checked("mood IN ('red')"),
        )

        val table = diff.tablesChanged.single()
        table.columnsChanged.shouldBeEmpty()
        table.constraintsChanged.single().after.name shouldBe "ck_t_mood"
    }

    test("#76: ein neuer Wertevorrat-CHECK an einer Textspalte wird angelegt") {
        val diff = SchemaComparator(targetAware).compare(checked(null), checked("mood IN ('red', 'green')"))

        val table = diff.tablesChanged.single()
        table.columnsChanged.shouldBeEmpty()
        table.constraintsAdded.single().name shouldBe "ck_t_mood"
    }

    test("#76: ein entfallender Wertevorrat-CHECK an einer Textspalte wird geloest") {
        val diff = SchemaComparator(targetAware).compare(checked("mood IN ('red', 'green')"), checked(null))

        val table = diff.tablesChanged.single()
        table.columnsChanged.shouldBeEmpty()
        table.constraintsRemoved.single().name shouldBe "ck_t_mood"
    }

    test("#76: eine neue Spalte bringt ihren Wertevorrat-CHECK als Constraint mit") {
        val current = schema(TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Integer))))
        val desired = schema(TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer),
                "mood" to ColumnDefinition(NeutralType.Text(10)),
            ),
            constraints = listOf(
                ConstraintDefinition(name = "ck_t_mood", type = ConstraintType.CHECK, expression = "mood IN ('red')"),
            ),
        ))

        val table = SchemaComparator(targetAware).compare(current, desired).tablesChanged.single()

        table.columnsAdded.keys shouldBe setOf("mood")
        table.constraintsAdded.single().name shouldBe "ck_t_mood"
    }

    test("#76: vom enum zur Textspalte mit anderem CHECK — Spalte und Constraint aendern sich") {
        val diff = SchemaComparator(targetAware)
            .compare(authored(listOf("red", "green")), checked("mood IN ('red', 'green', 'blue')"))

        val table = diff.tablesChanged.single()
        table.columnsChanged.single().type.shouldNotBeNull()
        table.constraintsAdded.single().name shouldBe "ck_t_mood"
    }

    test("#76: gleicher Wertevorrat in zwei Schreibweisen bleibt gefaltet — der zweite Lauf plant nichts") {
        val diff = SchemaComparator(targetAware).compare(
            checked("((mood = ANY (ARRAY['red'::text, 'green'::text])))"),
            checked("mood IN ('green', 'red')"),
        )

        diff.tablesChanged.shouldBeEmpty()
    }

    test("#76: ein enum mit derselben Werteliste wie der Soll-CHECK ist keine Aenderung") {
        val diff = SchemaComparator(targetAware)
            .compare(authored(listOf("red", "green")), checked("mood IN ('red', 'green')", width = 5))

        diff.tablesChanged.shouldBeEmpty()
    }

    test("#76: eine neue Laenge bei gleichem CHECK aendert nur die Spalte") {
        val diff = SchemaComparator(targetAware)
            .compare(checked("mood IN ('red')", width = 5), checked("mood IN ('red')", width = 20))

        val table = diff.tablesChanged.single()
        table.columnsChanged.single().type.shouldNotBeNull()
        table.constraintsChanged.shouldBeEmpty()
        table.constraintsAdded.shouldBeEmpty()
        table.constraintsRemoved.shouldBeEmpty()
    }

    test("ownsValueChecks: nur ein enum im Soll, oder eine Spalte, die das Soll nicht fuehrt") {
        val enumTable = authored(listOf("red")).tables.getValue("t")
        val textTable = checked("mood IN ('red')").tables.getValue("t")

        EnumCheckFolding.ownsValueChecks(enumTable, "mood") shouldBe true
        EnumCheckFolding.ownsValueChecks(textTable, "mood") shouldBe false
        EnumCheckFolding.ownsValueChecks(textTable, "gone") shouldBe true
        EnumCheckFolding.ownsValueChecks(null, "mood") shouldBe true
    }
})
