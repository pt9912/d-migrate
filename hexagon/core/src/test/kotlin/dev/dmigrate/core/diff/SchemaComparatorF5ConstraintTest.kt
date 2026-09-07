package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class SchemaComparatorF5ConstraintTest : FunSpec({
    val comparator = SchemaComparator()

    fun schema(table: TableDefinition) = SchemaDefinition(
        name = "s",
        version = "1",
        tables = mapOf("t" to table),
    )

    fun table(constraints: List<ConstraintDefinition>) = TableDefinition(
        columns = mapOf("c" to ColumnDefinition(NeutralType.Integer)),
        constraints = constraints,
    )

    test("§F.5 CHECK and EXCLUDE constraints are compared by conservative text") {
        val left = schema(
            table(
                constraints = listOf(
                    ConstraintDefinition(
                        name = "chk",
                        type = ConstraintType.CHECK,
                        expression = "c > 0",
                    ),
                    ConstraintDefinition(
                        name = "excl",
                        type = ConstraintType.EXCLUDE,
                        columns = listOf("c"),
                    ),
                ),
            ),
        )
        val right = schema(table(constraints = emptyList()))

        val diff = comparator.compare(left, right)

        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].constraintsRemoved.map { it.name } shouldBe listOf("chk", "excl")
    }

    test("§F.5 unchanged CHECK expression ignores surrounding whitespace and line endings only") {
        val left = schema(
            table(
                constraints = listOf(
                    ConstraintDefinition(
                        name = "chk",
                        type = ConstraintType.CHECK,
                        expression = "\r\nc > 0\r\n",
                    ),
                ),
            ),
        )
        val right = schema(
            table(
                constraints = listOf(
                    ConstraintDefinition(
                        name = "chk",
                        type = ConstraintType.CHECK,
                        expression = "c > 0",
                    ),
                ),
            ),
        )

        comparator.compare(left, right).isEmpty() shouldBe true
    }

    // ── Einspaltige Constraints: der Name des Katalogs, nicht ein erfundener ──
    //
    // Ein `DropConstraint` braucht den Namen: kein Dialekt ausser Oracle kennt
    // eine Form, die einen Constraint ueber seine Spalte abbaut. Ein erfundener
    // `_unique_<spalte>` traf an keiner echten Datenbank etwas (Oracle:
    // ORA-02443).

    fun tableWith(
        constraints: List<ConstraintDefinition> = emptyList(),
        unique: Boolean = false,
    ) = TableDefinition(
        columns = mapOf("c" to ColumnDefinition(NeutralType.Integer, unique = unique)),
        constraints = constraints,
    )

    fun namedUnique(name: String) = ConstraintDefinition(
        name = name, type = ConstraintType.UNIQUE, columns = listOf("c"),
    )

    test("dropping a named single-column UNIQUE keeps the name it had") {
        val diff = comparator.compare(
            schema(tableWith(constraints = listOf(namedUnique("uq_users_email")))),
            schema(tableWith()),
        )

        val removed = diff.tablesChanged.single().constraintsRemoved.single()
        removed.name shouldBe "uq_users_email"
    }

    test("the name comes from the side that carries the constraint") {
        // Beim Hinzufuegen zaehlt das Soll, beim Entfernen der Bestand — sonst
        // stuende im Drop ein Name, den die Datenbank nicht kennt.
        val added = comparator.compare(
            schema(tableWith()),
            schema(tableWith(constraints = listOf(namedUnique("uq_desired")))),
        ).tablesChanged.single().constraintsAdded.single()
        added.name shouldBe "uq_desired"
    }

    test("a column-level unique still has no name to keep — and says so recognisably") {
        // Fuehrt die Seite den Constraint als Spalteneigenschaft, gibt es im
        // Modell keinen Namen. Der gebildete ist als solcher erkennbar; was
        // daraus folgt, steht in
        // docs/planning/open/single-column-constraint-synthetic-name.md.
        val diff = comparator.compare(schema(tableWith(unique = true)), schema(tableWith()))

        diff.tablesChanged.single().constraintsRemoved.single().name shouldBe "_unique_c"
    }

    test("a named constraint and a column-level unique still compare as the same thing") {
        // Die Faltung ist der Zweck der Normalisierung: beide Schreibweisen
        // beschreiben denselben Constraint, und ein Namensunterschied allein
        // darf keine Aenderung planen.
        comparator.compare(
            schema(tableWith(constraints = listOf(namedUnique("uq_users_email")))),
            schema(tableWith(unique = true)),
        ).isEmpty() shouldBe true
    }
})
