package dev.dmigrate.core.diff

import dev.dmigrate.core.diff.migration.CanonicalPayload
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * ADR 0025: `fullTextVectorColumn` / `fullTextAccessMethod` are generate-only
 * reconstruction hints — invariant to `schema compare`, the migration fingerprint and the
 * rename-overlay key, exactly like `ordinal` (see [SchemaComparatorOrdinalTest]). Otherwise
 * an authored FULLTEXT index (hints absent) vs. the reversed live index (hints set) would
 * produce a phantom DROP+CREATE on every migrate. `textSearchConfig`, by contrast, IS
 * semantic and must still be detected.
 */
class SchemaComparatorFullTextHintsTest : FunSpec({

    val comparator = SchemaComparator()

    fun tableWith(index: IndexDefinition) = TableDefinition(
        columns = mapOf(
            "title" to ColumnDefinition(NeutralType.Text()),
            "fulltext" to ColumnDefinition(NeutralType.FullText),
        ),
        indices = listOf(index),
    )

    val authored = IndexDefinition(
        name = "ft", columns = listOf(IndexColumn("title")), type = IndexType.FULLTEXT,
        textSearchConfig = "english",
    )
    val reversed = authored.copy(fullTextVectorColumn = "fulltext", fullTextAccessMethod = IndexType.GIN)

    fun schema(index: IndexDefinition) =
        SchemaDefinition(name = "App", version = "1", tables = mapOf("docs" to tableWith(index)))

    test("compare ignores fullTextVectorColumn / fullTextAccessMethod differences") {
        comparator.compare(schema(authored), schema(reversed)).isEmpty() shouldBe true
    }

    test("fingerprint is invariant to the generate-only hints") {
        MigrationFingerprint.compute(schema(authored)) shouldBe MigrationFingerprint.compute(schema(reversed))
    }

    test("CanonicalPayload.index is invariant to the hints but includes textSearchConfig") {
        CanonicalPayload.index(authored) shouldBe CanonicalPayload.index(reversed)
        CanonicalPayload.index(authored) shouldBe CanonicalPayload.index(authored.copy(textSearchConfig = "english"))
    }

    test("partition-local FULLTEXT indices also ignore the generate-only hints") {
        fun partitioned(index: IndexDefinition) = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "docs" to TableDefinition(
                    columns = mapOf(
                        "title" to ColumnDefinition(NeutralType.Text()),
                        "fulltext" to ColumnDefinition(NeutralType.FullText),
                    ),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("title"),
                        partitions = listOf(
                            PartitionDefinition(name = "docs_p0", indices = listOf(index)),
                        ),
                    ),
                ),
            ),
        )
        comparator.compare(partitioned(authored), partitioned(reversed)).isEmpty() shouldBe true
    }

    test("a textSearchConfig difference IS still a change (it is semantic)") {
        val diff = comparator.compare(schema(authored), schema(authored.copy(textSearchConfig = "french")))
        diff.tablesChanged.single().indicesChanged.single().after.textSearchConfig shouldBe "french"
        CanonicalPayload.index(authored) shouldBe CanonicalPayload.index(reversed) // sanity: hints excluded
        (CanonicalPayload.index(authored) == CanonicalPayload.index(authored.copy(textSearchConfig = "french"))) shouldBe false
    }
})
