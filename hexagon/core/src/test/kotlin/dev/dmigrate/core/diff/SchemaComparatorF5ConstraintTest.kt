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
})
