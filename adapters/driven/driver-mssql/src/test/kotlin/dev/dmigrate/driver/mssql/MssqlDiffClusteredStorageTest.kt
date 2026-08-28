package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [ADR 0049]: Wechselt die Ablage einer Tabelle, ist der Primärschlüssel
 * mitbetroffen — und die Reihenfolge ist nicht frei wählbar. Solange der
 * clustered Primärschlüssel steht, scheitert jedes `CREATE CLUSTERED INDEX`
 * mit Msg 1902; umgekehrt kann der Schlüssel die Ablage erst zurückbekommen,
 * wenn kein Index sie mehr hält.
 *
 * Geprüft wird deshalb nicht nur, *dass* die Anweisungen kommen, sondern in
 * welcher Reihenfolge sie stehen.
 */
class MssqlDiffClusteredStorageTest : FunSpec({

    val comparator = SchemaComparator()
    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    fun schema(vararg indices: IndexDefinition) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf("orders" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "placed_on" to ColumnDefinition(NeutralType.Date),
            ),
            primaryKey = listOf("id"),
            indices = indices.toList(),
        )),
    )

    fun upSql(current: SchemaDefinition, desired: SchemaDefinition): List<String> =
        gen.generateUp(planner.plan(current, desired, comparator.compare(current, desired)), DdlGenerationOptions())
            .statements.map { it.sql }

    val ordinary = IndexDefinition("ix_placed", listOf(IndexColumn("placed_on")))
    val storage = ordinary.copy(clustered = true)

    test("an index taking over the storage flips the primary key first") {
        val sqls = upSql(schema(ordinary), schema(storage))
        val dropPk = sqls.indexOfFirst { it.contains("DROP CONSTRAINT") && it.contains("@pk") }
        val addPk = sqls.indexOfFirst { it.contains("PRIMARY KEY NONCLUSTERED") }
        val createClustered = sqls.indexOfFirst { it.contains("CREATE CLUSTERED INDEX") }

        (dropPk >= 0) shouldBe true
        (addPk > dropPk) shouldBe true
        // Der Kern: erst der Schluessel, dann der Index. Andersherum Msg 1902.
        (createClustered > addPk) shouldBe true
    }

    test("an index giving the storage back flips the primary key afterwards") {
        val sqls = upSql(schema(storage), schema(ordinary))
        val dropIndex = sqls.indexOfFirst { it.contains("DROP INDEX") }
        val addPk = sqls.indexOfFirst { it.contains("PRIMARY KEY (") }

        (dropIndex >= 0) shouldBe true
        // Der Schluessel kann die Ablage erst uebernehmen, wenn sie frei ist.
        (addPk > dropIndex) shouldBe true
        sqls.none { it.contains("PRIMARY KEY NONCLUSTERED") } shouldBe true
    }

    test("an index change that leaves the storage alone touches no primary key") {
        val sqls = upSql(schema(ordinary), schema(ordinary.copy(includeColumns = listOf("id"))))
        sqls.any { it.contains("INCLUDE ([id])") } shouldBe true
        // Der Normalfall: die allermeisten Index-Operationen fassen die Ablage nicht an.
        sqls.none { it.contains("PRIMARY KEY") } shouldBe true
    }

    test("a table without a primary key needs no flip; it is simply a heap") {
        val heapCurrent = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("orders" to TableDefinition(
                columns = linkedMapOf("placed_on" to ColumnDefinition(NeutralType.Date)),
                indices = listOf(ordinary),
            )),
        )
        val heapDesired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("orders" to TableDefinition(
                columns = linkedMapOf("placed_on" to ColumnDefinition(NeutralType.Date)),
                indices = listOf(storage),
            )),
        )
        val sqls = upSql(heapCurrent, heapDesired)
        sqls.any { it.contains("CREATE CLUSTERED INDEX") } shouldBe true
        sqls.none { it.contains("PRIMARY KEY") } shouldBe true
    }
})
