package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.AggregateDefinition
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class OracleDdlGeneratorObjectsTest : FunSpec({

    val generator = OracleDdlGenerator()

    fun schema(
        tables: Map<String, TableDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
        aggregates: Map<String, AggregateDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = "s", version = "1.0", tables = tables, sequences = sequences, views = views,
        functions = functions, procedures = procedures, triggers = triggers, aggregates = aggregates,
    )

    // ── Sequences ────────────────────────────────

    test("sequence with all bounds renders a full CREATE SEQUENCE") {
        val seq = SequenceDefinition(start = 100, increment = 5, minValue = 1, maxValue = 9999, cycle = true, cache = 20)
        val sql = generator.generate(schema(sequences = mapOf("order_seq" to seq))).render()
        sql shouldContain "CREATE SEQUENCE \"order_seq\" START WITH 100 INCREMENT BY 5 " +
            "MINVALUE 1 MAXVALUE 9999 CYCLE CACHE 20;"
    }

    test("sequence without bounds uses NOMINVALUE/NOMAXVALUE/NOCYCLE/NOCACHE") {
        val seq = SequenceDefinition()
        val sql = generator.generate(schema(sequences = mapOf("s" to seq))).render()
        sql shouldContain "CREATE SEQUENCE \"s\" START WITH 1 INCREMENT BY 1 " +
            "NOMINVALUE NOMAXVALUE NOCYCLE NOCACHE;"
    }

    // ── Indices ──────────────────────────────────

    fun tableWith(index: IndexDefinition, columnType: NeutralType = NeutralType.Integer) = TableDefinition(
        columns = mapOf("a" to ColumnDefinition(type = columnType, ordinal = 1)),
        indices = listOf(index),
    )

    test("a plain BTREE index renders CREATE INDEX with a derived name") {
        val table = tableWith(IndexDefinition(columns = listOf(IndexColumn("a"))))
        val sql = generator.generate(schema(tables = mapOf("t" to table))).render()
        sql shouldContain "CREATE INDEX \"idx_t_a\" ON \"t\" (\"a\");"
    }

    test("a unique index renders CREATE UNIQUE INDEX; direction is preserved") {
        val table = tableWith(
            IndexDefinition(name = "ux_a", columns = listOf(IndexColumn("a", IndexSortDirection.DESC)), unique = true),
        )
        val sql = generator.generate(schema(tables = mapOf("t" to table))).render()
        sql shouldContain "CREATE UNIQUE INDEX \"ux_a\" ON \"t\" (\"a\" DESC);"
    }

    test("a non-BTREE index degrades to a standard B-tree index with a W102 note") {
        val table = tableWith(IndexDefinition(name = "ix_hash", columns = listOf(IndexColumn("a")), type = IndexType.HASH))
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.render() shouldContain "CREATE INDEX \"ix_hash\" ON \"t\" (\"a\");"
        result.notes.single { it.objectName == "ix_hash" }.code shouldBe "W102"
    }

    test("a FULLTEXT index is rejected with E057 (Oracle Text is Slice 8)") {
        val table = tableWith(IndexDefinition(name = "ix_ft", columns = listOf(IndexColumn("a")), type = IndexType.FULLTEXT))
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.notes.single { it.objectName == "ix_ft" }.code shouldBe "E057"
    }

    test("an index on a CLOB column is skipped with W152 (not key/index-eligible)") {
        val table = tableWith(
            IndexDefinition(name = "ix_big", columns = listOf(IndexColumn("a"))),
            columnType = NeutralType.Text(null),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.notes.single { it.objectName == "ix_big" }.code shouldBe "W152"
    }

    // ── Views ────────────────────────────────────

    test("a simple view renders CREATE OR REPLACE FORCE VIEW") {
        val view = ViewDefinition(query = "SELECT 1 AS one", sourceDialect = "oracle")
        val sql = generator.generate(schema(views = mapOf("v" to view))).render()
        sql shouldContain "CREATE OR REPLACE FORCE VIEW \"v\" AS\nSELECT 1 AS one;"
    }

    test("a materialized view is rendered as a regular view with a W103 warning") {
        val view = ViewDefinition(query = "SELECT 1", materialized = true, sourceDialect = "oracle")
        val result = generator.generate(schema(views = mapOf("v" to view)))
        result.render() shouldContain "CREATE OR REPLACE FORCE VIEW \"v\""
        result.notes.single().code shouldBe "W103"
    }

    test("a view without a query is skipped, not rendered as broken DDL") {
        val view = ViewDefinition(query = null)
        val result = generator.generate(schema(views = mapOf("v" to view)))
        result.skippedObjects.single().name shouldBe "v"
    }

    test("a non-portable view (LIMIT clause, no Oracle equivalent) is skipped with E053") {
        val view = ViewDefinition(query = "SELECT 1 LIMIT 10", sourceDialect = "postgresql")
        val result = generator.generate(schema(views = mapOf("v" to view)))
        result.skippedObjects.single().code shouldBe "E053"
    }

    // ── Routines / aggregates (Slice 9 out of scope) ──

    test("functions/procedures/triggers are not rendered and land as E053 skipped objects") {
        val result = generator.generate(
            schema(
                functions = mapOf("f" to FunctionDefinition(body = "BEGIN RETURN 1; END;")),
                procedures = mapOf("p" to ProcedureDefinition(body = "BEGIN NULL; END;")),
                triggers = mapOf(
                    "trg" to TriggerDefinition(
                        table = "t", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER, body = "BEGIN NULL; END;",
                    ),
                ),
                tables = mapOf("t" to TableDefinition(columns = mapOf("a" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1)))),
            ),
        )
        result.skippedObjects.map { it.code }.toSet() shouldBe setOf("E053")
        result.skippedObjects.map { it.name } shouldBe listOf("f", "p", "trg")
    }

    test("aggregates are rejected with E054 (no ODCI implementation type carried by the model)") {
        val agg = AggregateDefinition(stateType = "internal", transitionFunction = "sf")
        val result = generator.generate(schema(aggregates = mapOf("agg" to agg)))
        result.skippedObjects.single().code shouldBe "E054"
    }

    // ── Rollback (invertStatement) ────────────────

    test("rollback drops CREATE TABLE/INDEX/SEQUENCE/VIEW without IF EXISTS (Oracle has no such clause)") {
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1)),
            indices = listOf(IndexDefinition(name = "ix_id", columns = listOf(IndexColumn("id")))),
        )
        val rollback = generator.generateRollback(
            schema(
                tables = mapOf("t" to table),
                sequences = mapOf("seq" to SequenceDefinition()),
                views = mapOf("v" to ViewDefinition(query = "SELECT 1")),
            ),
        ).render()
        rollback shouldContain "DROP VIEW \"v\";"
        rollback shouldContain "DROP SEQUENCE \"seq\";"
        rollback shouldContain "DROP INDEX \"ix_id\";"
        rollback shouldContain "DROP TABLE \"t\";"
        rollback shouldNotContain "IF EXISTS"
    }

    test("rollback of a deferred foreign key drops the named constraint, no IF EXISTS") {
        val parent = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1)),
            primaryKey = listOf("id"),
        )
        val child = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1),
                "parent_id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    references = dev.dmigrate.core.model.ReferenceDefinition(table = "parent", column = "id"),
                    ordinal = 2,
                ),
            ),
        )
        val schemaDef = schema(tables = mapOf("parent" to parent, "child" to child))
        val options = dev.dmigrate.driver.DdlGenerationOptions(deferForeignKeys = true)
        val forward = generator.generate(schemaDef, options)
        forward.render() shouldContain "ALTER TABLE \"child\" ADD CONSTRAINT \"fk_child_parent_id\""

        val rollback = generator.generateRollback(schemaDef, options).render()
        rollback shouldContain "ALTER TABLE \"child\" DROP CONSTRAINT \"fk_child_parent_id\";"
        rollback shouldNotContain "IF EXISTS"
    }
})
