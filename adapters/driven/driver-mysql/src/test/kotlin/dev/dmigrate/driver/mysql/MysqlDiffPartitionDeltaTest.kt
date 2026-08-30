package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Eine Partition kommt dazu oder faellt weg. MySQL verlangt aufsteigende
 * `VALUES LESS THAN`-Grenzen, und daran haengt, welche Anweisung gilt:
 * hinter der letzten Grenze `ADD PARTITION`, dazwischen `REORGANIZE
 * PARTITION` — das die Zeilen mitnimmt, statt sie zu verlieren.
 */
class MysqlDiffPartitionDeltaTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()

    fun child(name: String, to: PartitionBound) = PartitionDefinition(name = name, to = listOf(to))

    fun config(vararg children: PartitionDefinition) =
        PartitionConfig(PartitionType.RANGE, listOf("bucket"), children.toList())

    fun schema(partitioning: PartitionConfig) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf(
            "events" to TableDefinition(
                columns = linkedMapOf("bucket" to ColumnDefinition(NeutralType.Integer)),
                partitioning = partitioning,
            ),
        ),
    )

    fun renderUp(before: PartitionConfig, after: PartitionConfig) = gen.generateUp(
        planner.plan(
            schema(before),
            schema(after),
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "events", partitioning = ValueChange(before, after)))),
        ),
        DdlGenerationOptions(),
    )

    val p100 = child("p100", PartitionBound.Value("100"))
    val p200 = child("p200", PartitionBound.Value("200"))
    val p300 = child("p300", PartitionBound.Value("300"))

    test("hinter der letzten Grenze wird angehaengt") {
        val sqls = renderUp(config(p100, p200), config(p100, p200, p300)).statements.map { it.sql }

        sqls shouldBe listOf("ALTER TABLE `events` ADD PARTITION (PARTITION `p300` VALUES LESS THAN (300));")
    }

    test("dazwischen wird die folgende Partition aufgeteilt") {
        // Die Untergrenze wird aus der Reihenfolge ergaenzt, deshalb ist `p300`
        // vorher [100, 300) und nachher [200, 300): dasselbe Kind mit anderem
        // Zuschnitt. Genau das kann `REORGANIZE`, und `DROP` + `ADD` nicht.
        val sqls = renderUp(config(p100, p300), config(p100, p200, p300)).statements.map { it.sql }

        sqls shouldBe listOf(
            "ALTER TABLE `events` REORGANIZE PARTITION `p300` INTO " +
                "(PARTITION `p200` VALUES LESS THAN (200), PARTITION `p300` VALUES LESS THAN (300));",
        )
    }

    test("zwei zusammengelegte Partitionen werden zu einer reorganisiert") {
        val sqls = renderUp(config(p100, p200, p300), config(p100, p300)).statements.map { it.sql }

        sqls shouldBe listOf(
            "ALTER TABLE `events` REORGANIZE PARTITION `p200`, `p300` INTO " +
                "(PARTITION `p300` VALUES LESS THAN (300));",
        )
    }

    test("eine entfallene Partition am Ende wird verworfen") {
        // Am Ende faellt der Bereich wirklich weg — er taucht nicht als Teil
        // eines neuen Zuschnitts wieder auf.
        val sqls = renderUp(config(p100, p200, p300), config(p100, p200)).statements.map { it.sql }

        sqls shouldBe listOf("ALTER TABLE `events` DROP PARTITION `p300`;")
    }

    test("der Rueckbau dreht Anhaengen und Verwerfen um") {
        val sqls = gen.generateDown(
            planner.plan(
                schema(config(p100, p200)),
                schema(config(p100, p200, p300)),
                SchemaDiff(
                    tablesChanged = listOf(
                        TableDiff(
                            name = "events",
                            partitioning = ValueChange(config(p100, p200), config(p100, p200, p300)),
                        ),
                    ),
                ),
            ),
            DdlGenerationOptions(),
        ).statements.map { it.sql }

        sqls shouldBe listOf("ALTER TABLE `events` DROP PARTITION `p300`;")
    }
})
