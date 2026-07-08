package dev.dmigrate.core.diff

import dev.dmigrate.core.diff.migration.CanonicalPayload
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * ADR 0021: `ordinal` ist bewusst invariant gegenüber `schema compare` und
 * dem Migration-Fingerprint. Eine reine Spalten-Umsortierung ist kein
 * Migrationsschritt; ohne diese Invarianz würde der Hybrid-Ansatz Phantom-
 * Diffs / instabile Operation-IDs erzeugen.
 */
class SchemaComparatorOrdinalTest : FunSpec({

    val comparator = SchemaComparator()

    // Gleiche Tabelle, aber unterschiedliche Ordinale UND Einfügereihenfolge.
    val left = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Identifier(true), ordinal = 1),
            "name" to ColumnDefinition(NeutralType.Text(100), ordinal = 2),
        ),
        primaryKey = listOf("id"),
    )
    val right = TableDefinition(
        columns = linkedMapOf(
            "name" to ColumnDefinition(NeutralType.Text(100), ordinal = 9),
            "id" to ColumnDefinition(NeutralType.Identifier(true), ordinal = 7),
        ),
        primaryKey = listOf("id"),
    )

    test("compare ignores column ordinal differences") {
        val l = SchemaDefinition(name = "App", version = "1", tables = mapOf("users" to left))
        val r = SchemaDefinition(name = "App", version = "1", tables = mapOf("users" to right))
        comparator.compare(l, r).isEmpty() shouldBe true
    }

    test("canonical fingerprint is invariant to column ordinal") {
        CanonicalPayload.table(left) shouldBe CanonicalPayload.table(right)
    }
})
