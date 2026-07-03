package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * AP7 (postcompare-type-canonicalization slice): pins the target-aware
 * comparison mode — differences the TARGET dialect cannot express (type
 * folding, PK-implied required, effective PK) are suppressed so the migrate
 * plan CONVERGES, while the strict default (schema compare) keeps reporting
 * them.
 */
class SchemaComparatorTargetAwareTest : FunSpec({

    val sqliteLike: (NeutralType) -> NeutralType =
        { t -> if (t == NeutralType.SmallInt) NeutralType.Integer else t }

    fun schemaWith(table: TableDefinition) = SchemaDefinition(
        name = "App", version = "1", tables = mapOf("t" to table),
    )

    fun typed(t: NeutralType, required: Boolean = false) = schemaWith(
        TableDefinition(columns = mapOf("val" to ColumnDefinition(t, required = required))),
    )

    test("target-folded type difference is suppressed in target-aware mode, kept strict by default") {
        val current = typed(NeutralType.Integer)
        val desired = typed(NeutralType.SmallInt)
        SchemaComparator(sqliteLike).compare(current, desired).isEmpty() shouldBe true
        SchemaComparator().compare(current, desired).isEmpty() shouldBe false
    }

    test("a genuinely different type stays a difference in target-aware mode") {
        val current = typed(NeutralType.Integer)
        val desired = typed(NeutralType.Text())
        SchemaComparator(sqliteLike).compare(current, desired).isEmpty() shouldBe false
    }

    test("PK-implied required is suppressed in target-aware mode, non-PK required stays strict") {
        fun pkTable(required: Boolean) = schemaWith(TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = required)),
            primaryKey = listOf("id"),
        ))
        SchemaComparator(sqliteLike).compare(pkTable(true), pkTable(false)).isEmpty() shouldBe true
        SchemaComparator().compare(pkTable(true), pkTable(false)).isEmpty() shouldBe false
        // Nicht-PK-Spalte: required bleibt auch target-aware ein Unterschied.
        SchemaComparator(sqliteLike)
            .compare(typed(NeutralType.Integer, required = true), typed(NeutralType.Integer, required = false))
            .isEmpty() shouldBe false
    }

    test("implicit identifier PK equals explicit PK in target-aware mode (effective PK)") {
        val implicit = schemaWith(TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true))),
        ))
        val explicit = schemaWith(TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true))),
            primaryKey = listOf("id"),
        ))
        SchemaComparator(sqliteLike).compare(implicit, explicit).isEmpty() shouldBe true
        SchemaComparator().compare(implicit, explicit).isEmpty() shouldBe false
    }
})
