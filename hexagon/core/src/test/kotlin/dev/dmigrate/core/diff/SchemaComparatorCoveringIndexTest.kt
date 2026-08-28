package dev.dmigrate.core.diff

import dev.dmigrate.core.diff.migration.CanonicalPayload
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * ADR 0049: `includeColumns` und `clustered` sind **semantisch**, nicht wie die
 * Volltext-Hinweise nur Rekonstruktionswissen. Sie müssen deshalb in allen drei
 * Projektionen der Index-Identität ankommen — Comparator, Fingerprint und
 * `CanonicalPayload` —, sonst meldete `schema compare` zwei verschiedene Schemata
 * als gleich. Das Gegenstück zu [SchemaComparatorFullTextHintsTest], der für die
 * Hinweise genau das Umgekehrte festhält.
 */
class SchemaComparatorCoveringIndexTest : FunSpec({

    val comparator = SchemaComparator()

    fun tableWith(index: IndexDefinition) = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer),
            "title" to ColumnDefinition(NeutralType.Text()),
        ),
        indices = listOf(index),
    )

    fun schema(index: IndexDefinition) =
        SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to tableWith(index)))

    val plain = IndexDefinition(name = "ix", columns = listOf(IndexColumn("id")))
    val covering = plain.copy(includeColumns = listOf("title"))
    val clustered = plain.copy(clustered = true)

    test("compare reports an index that gains INCLUDE columns as changed") {
        val diff = comparator.compare(schema(plain), schema(covering))
        diff.isEmpty() shouldBe false
    }

    test("compare reports a change of the table's physical storage") {
        comparator.compare(schema(plain), schema(clustered)).isEmpty() shouldBe false
    }

    test("both fields reach the fingerprint") {
        MigrationFingerprint.compute(schema(plain)) shouldNotBe MigrationFingerprint.compute(schema(covering))
        MigrationFingerprint.compute(schema(plain)) shouldNotBe MigrationFingerprint.compute(schema(clustered))
    }

    test("both fields reach the canonical payload") {
        CanonicalPayload.index(plain) shouldNotBe CanonicalPayload.index(covering)
        CanonicalPayload.index(plain) shouldNotBe CanonicalPayload.index(clustered)
    }

    test("an unchanged index stays quiet in all three projections") {
        comparator.compare(schema(covering), schema(covering)).isEmpty() shouldBe true
        MigrationFingerprint.compute(schema(covering)) shouldBe MigrationFingerprint.compute(schema(covering))
        CanonicalPayload.index(covering) shouldBe CanonicalPayload.index(covering)
    }
})
