package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Der **Name** eines einspaltigen UNIQUE-Constraints im Vergleich.
 *
 * Er ist Teil des Vertrags, nicht Zierrat: Anwendungen lesen ihn in der
 * Fehlerbehandlung, und ein `DROP CONSTRAINT` trifft ohne ihn nichts. Bisher
 * fiel er beim Falten auf `column.unique` weg, und der Vergleich schwieg zu
 * einem abweichenden Namen.
 *
 * Verglichen wird nur, wo **beide** Seiten einen nennen. Ein
 * handgeschriebenes `unique: true` nennt keinen — daraus eine Aenderung zu
 * machen hiesse, dem Autor einen Namen zu unterstellen.
 */
class SchemaComparatorConstraintNameTest : FunSpec({

    val comparator = SchemaComparator()

    fun schema(uniqueName: String?) = SchemaDefinition(
        name = "s",
        version = "1",
        tables = mapOf(
            "users" to TableDefinition(
                columns = mapOf(
                    "email" to ColumnDefinition(
                        type = NeutralType.Text(),
                        unique = true,
                        uniqueConstraintName = uniqueName,
                    ),
                ),
            ),
        ),
    )

    test("a differing constraint name is a change — dropped under the old name, added under the new") {
        val diff = comparator.compare(schema("uq_users_email"), schema("users_email_key"))
        val table = diff.tablesChanged.single()

        withClue(table.toString()) {
            table.constraintsRemoved.map { it.name } shouldContainExactly listOf("uq_users_email")
            table.constraintsAdded.map { it.name } shouldContainExactly listOf("users_email_key")
        }
        table.constraintsRemoved.single().type shouldBe ConstraintType.UNIQUE
        table.constraintsRemoved.single().columns shouldContainExactly listOf("email")
    }

    test("the same name is no change") {
        comparator.compare(schema("uq_users_email"), schema("uq_users_email")).tablesChanged.isEmpty() shouldBe true
    }

    test("a side that names nothing makes no claim — and no change") {
        comparator.compare(schema(null), schema("uq_users_email")).tablesChanged.isEmpty() shouldBe true
        comparator.compare(schema("uq_users_email"), schema(null)).tablesChanged.isEmpty() shouldBe true
    }
})
