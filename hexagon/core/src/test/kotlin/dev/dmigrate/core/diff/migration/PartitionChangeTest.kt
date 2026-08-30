package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Die Aufteilung einer Partitionierungsaenderung in den auflösbaren Teil
 * (Kinder kommen dazu oder fallen weg) und den Rest — und was der
 * [OperationMapper] daraus macht.
 */
class PartitionChangeTest : FunSpec({

    fun range(vararg children: PartitionDefinition, key: String = "placed_on") =
        PartitionConfig(PartitionType.RANGE, listOf(key), children.toList())

    fun child(name: String, from: String?, to: String?) = PartitionDefinition(
        name = name,
        from = listOf(from?.let { PartitionBound.Value(it) } ?: PartitionBound.MinValue),
        to = listOf(to?.let { PartitionBound.Value(it) } ?: PartitionBound.MaxValue),
    )

    test("eine hinzugekommene Partition ist ein auflösbares Kind-Delta") {
        val before = range(child("p1", null, "'2026-01-01'"))
        val after = range(child("p1", null, "'2026-01-01'"), child("p2", "'2026-01-01'", null))

        val change = PartitionChangeClassifier.classify(before, after)

        val delta = change.shouldBeInstanceOf<PartitionChange.ChildrenChanged>().delta
        delta.added.map { it.name } shouldBe listOf("p2")
        delta.removed.shouldBeEmpty()
        delta.retained shouldHaveSize 1
    }

    test("eine eingefügte Grenze in SQL-Server-Lesart: ein Kind weg, zwei dazu") {
        // SQL Server deckt die Zahlenachse lückenlos ab und nummeriert die
        // Abschnitte. Eine Grenze mehr teilt den letzten Abschnitt auf.
        val before = range(child("p1", null, "'2026-01-01'"), child("p2", "'2026-01-01'", null))
        val after = range(
            child("p1", null, "'2026-01-01'"),
            child("p2", "'2026-01-01'", "'2026-02-01'"),
            child("p3", "'2026-02-01'", null),
        )

        val delta = PartitionChangeClassifier.classify(before, after)
            .shouldBeInstanceOf<PartitionChange.ChildrenChanged>().delta

        delta.removed.map { it.name } shouldBe listOf("p2")
        delta.added.map { it.name } shouldBe listOf("p2", "p3")
        // Der entfallene Abschnitt ist nicht weg, sondern aufgeteilt — sonst
        // verlangte das Hinzufügen einer Partition `--allow-destructive`.
        delta.splits.single().whole.name shouldBe "p2"
        delta.splits.single().pieces.map { it.name } shouldBe listOf("p2", "p3")
        delta.droppedOutright.shouldBeEmpty()
    }

    test("eine zusammengelegte Grenze deckt die entfallenen Kinder ab") {
        val before = range(
            child("p1", null, "'2026-01-01'"),
            child("p2", "'2026-01-01'", "'2026-02-01'"),
            child("p3", "'2026-02-01'", null),
        )
        val after = range(child("p1", null, "'2026-01-01'"), child("p2", "'2026-01-01'", null))

        val delta = PartitionChangeClassifier.classify(before, after)
            .shouldBeInstanceOf<PartitionChange.ChildrenChanged>().delta

        delta.merges.single().whole.name shouldBe "p2"
        delta.merges.single().pieces.map { it.name } shouldBe listOf("p2", "p3")
    }

    test("umnummerierte Kinder mit gleichen Grenzen sind keine Bestandsänderung") {
        // Dieselben Grenzen, andere Namen: der Abgleich läuft über die Grenzen,
        // sonst meldete jede SQL-Server-Umnummerierung einen Umbau.
        val before = range(child("p1", null, "'2026-01-01'"))
        val after = range(child("p_2025", null, "'2026-01-01'"))

        PartitionChangeClassifier.classify(before, after) shouldBe
            PartitionChange.NotResolvable(PartitionChangeReason.CHILD_NAMES_CHANGED)
    }

    test("gleiche Grenzen und Namen, andere kind-lokale Indizes") {
        val index = IndexDefinition(name = "ix", columns = listOf(IndexColumn("id")))
        val before = range(child("p1", null, "'2026-01-01'"))
        val after = range(child("p1", null, "'2026-01-01'").copy(indices = listOf(index)))

        PartitionChangeClassifier.classify(before, after) shouldBe
            PartitionChange.NotResolvable(PartitionChangeReason.CHILD_INDICES_CHANGED)
    }

    test("eine fehlende Untergrenze wird aus der Reihenfolge ergänzt") {
        // MySQL beschreibt eine RANGE-Partition nur über ihre Obergrenze, und
        // so schreibt man sie auch in eine Schemadatei; der Reverse liefert
        // beide Grenzen. Ohne Ausgleich wäre jedes Kind auf beiden Seiten ein
        // anderes — aus einem Zugang würde ein vollständiger Umbau.
        val authored = PartitionConfig(
            PartitionType.RANGE, listOf("bucket"),
            listOf(
                PartitionDefinition(name = "p100", to = listOf(PartitionBound.Value("100"))),
                PartitionDefinition(name = "p300", to = listOf(PartitionBound.Value("300"))),
                PartitionDefinition(name = "p400", to = listOf(PartitionBound.Value("400"))),
            ),
        )
        val live = PartitionConfig(
            PartitionType.RANGE, listOf("bucket"),
            listOf(
                child("p100", null, "100"),
                PartitionDefinition(
                    name = "p300",
                    from = listOf(PartitionBound.Value("100")),
                    to = listOf(PartitionBound.Value("300")),
                ),
            ),
        )

        val delta = PartitionChangeClassifier.classify(live, authored)
            .shouldBeInstanceOf<PartitionChange.ChildrenChanged>().delta

        delta.added.map { it.name } shouldBe listOf("p400")
        delta.removed.shouldBeEmpty()
    }

    test("eine andere Eimerzahl ist keine Grenzänderung") {
        // HASH: ein anderer Modulus verteilt jede Zeile neu. Ohne diese
        // Einstufung sähe der Kind-Abgleich zwei entfallene und vier
        // hinzugekommene Kinder — und PostgreSQL rendete daraus `DROP TABLE`
        // plus `CREATE TABLE`, also den Verlust jeder Zeile.
        fun buckets(modulus: Int) = PartitionConfig(
            PartitionType.HASH, listOf("id"),
            (0 until modulus).map { PartitionDefinition(name = "h$it", modulus = modulus, remainder = it) },
        )

        PartitionChangeClassifier.classify(buckets(2), buckets(4)) shouldBe
            PartitionChange.NotResolvable(PartitionChangeReason.HASH_BUCKETS_CHANGED)
    }

    test("Strategie- und Schlüsselwechsel bleiben unauflösbar") {
        val before = range(child("p1", null, null))
        PartitionChangeClassifier.classify(before, before.copy(type = PartitionType.LIST)) shouldBe
            PartitionChange.NotResolvable(PartitionChangeReason.STRATEGY_CHANGED)
        PartitionChangeClassifier.classify(before, before.copy(key = listOf("other"))) shouldBe
            PartitionChange.NotResolvable(PartitionChangeReason.KEY_CHANGED)
        PartitionChangeClassifier.classify(null, before) shouldBe
            PartitionChange.NotResolvable(PartitionChangeReason.PARTITIONING_ADDED)
        PartitionChangeClassifier.classify(before, null) shouldBe
            PartitionChange.NotResolvable(PartitionChangeReason.PARTITIONING_REMOVED)
    }

    context("der Mapper") {
        val planner = DiffPlanner()
        val table = TableDefinition(columns = mapOf("placed_on" to ColumnDefinition(NeutralType.Date)))
        fun schemaWith(config: PartitionConfig) =
            SchemaDefinition(name = "App", version = "1", tables = mapOf("orders" to table.copy(partitioning = config)))

        fun plan(before: PartitionConfig, after: PartitionConfig) = planner.plan(
            schemaWith(before),
            schemaWith(after),
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "orders", partitioning = ValueChange(before, after)))),
        )

        test("macht aus einem Kind-Zugang eine Operation ohne Warnung") {
            val before = range(child("p1", null, "'2026-01-01'"))
            val after = range(child("p1", null, "'2026-01-01'"), child("p2", "'2026-01-01'", null))

            val result = plan(before, after)

            val op = result.operations.filterIsInstance<DiffOperation.AlterTablePartitions>().single()
            op.delta.added.map { it.name } shouldBe listOf("p2")
            op.risks.up.destructive shouldBe false
            op.risks.down?.destructive shouldBe true
            result.diagnostics.map { it.code } shouldBe emptyList()
        }

        test("stuft einen Kind-Abgang als zerstörend ein") {
            val before = range(child("p1", null, "'2026-01-01'"), child("p2", "'2026-01-01'", null))
            val after = range(child("p2", "'2026-01-01'", null))

            val op = plan(before, after).operations
                .filterIsInstance<DiffOperation.AlterTablePartitions>().single()

            op.risks.up.destructive shouldBe true
            op.risks.up.requiresManualConfirmation shouldBe true
            op.risks.down?.destructive shouldBe false
        }

        test("eine Aufteilung ist nicht zerstörend") {
            // SQL-Server-Lesart: die Kinder decken die Zahlenachse lückenlos ab,
            // eine eingefügte Grenze ersetzt deshalb ein Kind durch zwei.
            val before = range(child("p1", null, "'2026-01-01'"), child("p2", "'2026-01-01'", null))
            val after = range(
                child("p1", null, "'2026-01-01'"),
                child("p2", "'2026-01-01'", "'2026-02-01'"),
                child("p3", "'2026-02-01'", null),
            )

            val op = plan(before, after).operations
                .filterIsInstance<DiffOperation.AlterTablePartitions>().single()

            op.risks.up.destructive shouldBe false
            op.risks.up.requiresManualConfirmation shouldBe false
        }

        test("benennt den Grund, wenn die Änderung nicht auflösbar ist") {
            val before = range(child("p1", null, null))
            val after = before.copy(key = listOf("other"))

            val result = plan(before, after)

            result.operations.filterIsInstance<DiffOperation.AlterTablePartitions>().shouldBeEmpty()
            result.diagnostics.single { it.code == "PARTITIONING_CHANGE_NOT_APPLIED" }
                .message.contains("partition key changed") shouldBe true
        }
    }
})
