package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SchemaNodeParserTest : FunSpec({

    val mapper = ObjectMapper()

    test("parse wires structure and programmability sections through the split helpers") {
        val root = mapper.readTree(
            """
            {
              "schema_format": "1.1",
              "name": "demo",
              "version": "2.0",
              "description": "Demo schema",
              "encoding": "latin-1",
              "locale": "de_DE",
              "custom_types": {
                "status_type": {
                  "kind": "enum",
                  "values": ["OPEN", "DONE"],
                  "description": "Order status"
                }
              },
              "tables": {
                "orders": {
                  "description": "Orders",
                  "columns": {
                    "id": { "type": "identifier", "auto_increment": true },
                    "status": { "type": "enum", "ref_type": "status_type" },
                    "next_id": { "type": "integer", "default": { "sequence_nextval": "order_seq" } }
                  },
                  "primary_key": ["id"],
                  "indices": [
                    { "name": "idx_orders_status", "columns": ["status"], "unique": true }
                  ],
                  "constraints": [
                    { "name": "chk_orders_status", "type": "check", "expression": "status <> ''" }
                  ],
                  "partitioning": {
                    "type": "range",
                    "key": ["id"],
                    "partitions": [
                      { "name": "p0", "from": "0", "to": "100" }
                    ]
                  },
                  "metadata": {
                    "engine": "InnoDB",
                    "without_rowid": true
                  }
                }
              },
              "procedures": {
                "refresh_orders": {
                  "description": "Refreshes order cache",
                  "parameters": [
                    { "name": "limit_rows", "type": "integer", "direction": "inout" }
                  ],
                  "language": "sql",
                  "body": "BEGIN END",
                  "dependencies": {
                    "tables": ["orders"]
                  },
                  "source_dialect": "mysql"
                }
              },
              "functions": {
                "calc_total": {
                  "parameters": [
                    { "name": "net", "type": "decimal" }
                  ],
                  "returns": { "type": "decimal", "precision": 10, "scale": 2 },
                  "language": "sql",
                  "deterministic": true,
                  "body": "RETURN net",
                  "dependencies": {
                    "tables": ["orders"],
                    "columns": { "orders": ["status"] }
                  },
                  "source_dialect": "mysql"
                }
              },
              "views": {
                "order_view": {
                  "description": "Order projection",
                  "materialized": true,
                  "refresh": "manual",
                  "query": "select id from orders",
                  "dependencies": {
                    "tables": ["orders"],
                    "views": ["source_view"],
                    "columns": { "orders": ["id"] },
                    "functions": ["calc_total"]
                  },
                  "source_dialect": "postgresql"
                }
              },
              "triggers": {
                "orders_audit": {
                  "description": "Audit trigger",
                  "table": "orders",
                  "event": "update",
                  "timing": "after",
                  "for_each": "statement",
                  "condition": "NEW.id IS NOT NULL",
                  "body": "INSERT INTO audit_log VALUES (NEW.id)",
                  "dependencies": {
                    "tables": ["orders"]
                  },
                  "source_dialect": "mysql"
                }
              },
              "sequences": {
                "order_seq": {
                  "description": "Sequence for orders",
                  "start": 100,
                  "increment": 5,
                  "min_value": 10,
                  "max_value": 999,
                  "cycle": true,
                  "cache": 20
                }
              }
            }
            """.trimIndent()
        )

        val schema = SchemaNodeParser.parse(root)

        schema.schemaFormat shouldBe "1.1"
        schema.name shouldBe "demo"
        schema.version shouldBe "2.0"
        schema.description shouldBe "Demo schema"
        schema.encoding shouldBe "latin-1"
        schema.locale shouldBe "de_DE"

        val customType = schema.customTypes.getValue("status_type")
        customType.kind shouldBe CustomTypeKind.ENUM
        customType.values shouldContainExactly listOf("OPEN", "DONE")
        customType.description shouldBe "Order status"

        val table = schema.tables.getValue("orders")
        table.description shouldBe "Orders"
        table.primaryKey shouldContainExactly listOf("id")
        table.indices.single().name shouldBe "idx_orders_status"
        table.indices.single().unique shouldBe true
        table.constraints.single().type shouldBe ConstraintType.CHECK
        table.partitioning!!.type shouldBe PartitionType.RANGE
        table.partitioning!!.partitions.single().name shouldBe "p0"
        table.metadata!!.engine shouldBe "InnoDB"
        table.metadata!!.withoutRowid shouldBe true
        table.columns.getValue("next_id").default
            .shouldBeInstanceOf<DefaultValue.SequenceNextVal>()
            .sequenceName shouldBe "order_seq"

        val procedure = schema.procedures.getValue("refresh_orders")
        procedure.parameters.single().direction shouldBe ParameterDirection.INOUT
        procedure.dependencies!!.tables shouldContainExactly listOf("orders")
        procedure.sourceDialect shouldBe "mysql"

        val function = schema.functions.getValue("calc_total")
        function.returns!!.type shouldBe "decimal"
        function.returns!!.precision shouldBe 10
        function.returns!!.scale shouldBe 2
        function.deterministic shouldBe true
        function.dependencies!!.columns.getValue("orders") shouldContainExactly listOf("status")

        val view = schema.views.getValue("order_view")
        view.materialized shouldBe true
        view.refresh shouldBe "manual"
        view.dependencies!!.functions shouldContainExactly listOf("calc_total")
        view.dependencies!!.columns.getValue("orders") shouldContainExactly listOf("id")

        val trigger = schema.triggers.getValue("orders_audit")
        trigger.event shouldBe TriggerEvent.UPDATE
        trigger.timing shouldBe TriggerTiming.AFTER
        trigger.forEach shouldBe TriggerForEach.STATEMENT
        trigger.sourceDialect shouldBe "mysql"

        val sequence = schema.sequences.getValue("order_seq")
        sequence.start shouldBe 100L
        sequence.increment shouldBe 5L
        sequence.minValue shouldBe 10L
        sequence.maxValue shouldBe 999L
        sequence.cycle shouldBe true
        sequence.cache shouldBe 20
    }
})
