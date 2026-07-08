package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * AP1 of the implicit-`identifier`-PK-materialisation slice: the
 * [OperationMapper] materialises the *effective* PK — the shared v3
 * rule [dev.dmigrate.core.diff.EffectivePrimaryKey] that Fingerprint and
 * the target-aware Comparator already use — into a `CreateTable` op's
 * definition, so every dialect renderer emits the PK uniformly (MySQL
 * AUTO_INCREMENT gets its required KEY; PG `SERIAL` gets `PRIMARY KEY`;
 * SQLite's inline PK is deduped downstream). Slice plan:
 * `docs/planning/in-progress/generate-implicit-identifier-pk-materialization.md`.
 */
class OperationMapperEffectivePrimaryKeyTest : FunSpec({

    val planner = DiffPlanner()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun createTableFor(table: TableDefinition): DiffOperation.CreateTable {
        val desired = emptySchema().copy(tables = mapOf("t" to table))
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("t", table)))
        return planner.plan(emptySchema(), desired, diff)
            .operations.filterIsInstance<DiffOperation.CreateTable>().single()
    }

    test("identifier-only table (no explicit PK) materialises the effective PK") {
        val table = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        createTableFor(table).table.primaryKey shouldBe listOf("id")
    }

    test("explicit primary key wins verbatim over the identifier derivation") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier()),
                "code" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("code"),
        )
        createTableFor(table).table.primaryKey shouldBe listOf("code")
    }

    test("multiple identifier columns stay ambiguous — no PK materialised") {
        val table = TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(NeutralType.Identifier()),
                "b" to ColumnDefinition(NeutralType.Identifier()),
            ),
        )
        createTableFor(table).table.primaryKey shouldBe emptyList()
    }

    test("non-identifier table without a PK is left unchanged") {
        val table = TableDefinition(columns = mapOf("name" to ColumnDefinition(NeutralType.Integer)))
        createTableFor(table).table.primaryKey shouldBe emptyList()
    }

    test("materialisation copies — the desired schema definition is not mutated") {
        val table = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        val desired = emptySchema().copy(tables = mapOf("t" to table))
        planner.plan(emptySchema(), desired, SchemaDiff(tablesAdded = listOf(NamedTable("t", table))))
        // The source objects stay untouched (immutability + copy, not mutation),
        // so the Fingerprint — which applies EffectivePrimaryKey.of itself — is unaffected.
        desired.tables.getValue("t").primaryKey shouldBe emptyList()
        table.primaryKey shouldBe emptyList()
    }

    test("operation id is content-consistent with the materialised payload") {
        val table = TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())))
        val createTable = createTableFor(table)
        val ref = DiffObjectRef(DiffObjectType.TABLE, listOf("t"))
        createTable.id shouldBe OperationIdFactory.makeId("CreateTable", ref, CanonicalPayload.table(createTable.table))
    }
})
