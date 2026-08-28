package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Fingerprint `v9` (ADR 0049): abdeckende und clustered Indizes stehen in der
 * Projektion, und die Sicht des Ziel-Dialekts faltet weg, was der Dialekt nicht
 * ausdruecken kann.
 */
class MigrationFingerprintCoveringIndexTest : FunSpec({

    fun schema(tables: Map<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables)

    test("an index carrying INCLUDE columns hashes differently than one without") {
        fun docs(include: List<String>) = schema(tables = mapOf("docs" to TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer),
                "title" to ColumnDefinition(NeutralType.Text()),
            ),
            indices = listOf(IndexDefinition(
                name = "idx_id", columns = listOf(IndexColumn("id")), includeColumns = include,
            )),
        )))
        MigrationFingerprint.compute(docs(emptyList())) shouldNotBe
            MigrationFingerprint.compute(docs(listOf("title")))
    }

    test("INCLUDE columns are not the same statement as key columns") {
        // Ein Index ueber (id) INCLUDE (title) deckt dieselben Spalten ab wie einer
        // ueber (id, title) — bei `unique` sind sie aber verschieden, und der
        // Fingerabdruck darf sie nicht zusammenfallen lassen.
        fun docs(index: IndexDefinition) = schema(tables = mapOf("docs" to TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer),
                "title" to ColumnDefinition(NeutralType.Text()),
            ),
            indices = listOf(index),
        )))
        val covering = IndexDefinition(
            name = "ix", columns = listOf(IndexColumn("id")),
            unique = true, includeColumns = listOf("title"),
        )
        val composite = IndexDefinition(
            name = "ix", columns = listOf(IndexColumn("id"), IndexColumn("title")), unique = true,
        )
        MigrationFingerprint.compute(docs(covering)) shouldNotBe MigrationFingerprint.compute(docs(composite))
    }

    test("the clustered flag changes the fingerprint") {
        fun docs(clustered: Boolean) = schema(tables = mapOf("docs" to TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
            indices = listOf(IndexDefinition(
                name = "idx_id", columns = listOf(IndexColumn("id")), clustered = clustered,
            )),
        )))
        MigrationFingerprint.compute(docs(false)) shouldNotBe MigrationFingerprint.compute(docs(true))
    }

    test("a dialect projection that drops both fields makes the two schemas hash alike") {
        // So sieht MySQL die Indizes: es kann weder INCLUDE noch clustered melden.
        // Ohne diese Projektion meldete der Post-Compare Drift fuer etwas, das der
        // Zielserver gar nicht ausdruecken kann.
        fun docs(index: IndexDefinition) = schema(tables = mapOf("docs" to TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer),
                "title" to ColumnDefinition(NeutralType.Text()),
            ),
            indices = listOf(index),
        )))
        val authored = IndexDefinition(
            name = "ix", columns = listOf(IndexColumn("id")),
            includeColumns = listOf("title"), clustered = true,
        )
        val reversed = IndexDefinition(name = "ix", columns = listOf(IndexColumn("id")))
        val blind: (IndexDefinition) -> IndexDefinition =
            { it.copy(includeColumns = emptyList(), clustered = false) }
        MigrationFingerprint.compute(docs(authored), { it }, blind) shouldBe
            MigrationFingerprint.compute(docs(reversed), { it }, blind)
    }

    test("the projection reaches partition-local indices too") {
        val authored = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("c" to ColumnDefinition(NeutralType.Integer)),
            partitioning = PartitionConfig(
                PartitionType.RANGE, listOf("c"),
                listOf(PartitionDefinition(
                    name = "p1",
                    to = listOf(PartitionBound.Value("1")),
                    indices = listOf(IndexDefinition(
                        name = "idx_p1", columns = listOf(IndexColumn("c")), clustered = true,
                    )),
                )),
            ),
        )))
        val blind: (IndexDefinition) -> IndexDefinition = { it.copy(clustered = false) }
        MigrationFingerprint.project(authored, { it }, blind) shouldContain "clustered=false"
    }
})
