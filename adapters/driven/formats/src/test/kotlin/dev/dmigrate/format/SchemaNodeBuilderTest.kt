package dev.dmigrate.format

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TableMetadata
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class SchemaNodeBuilderTest : FunSpec({

    val mapper = ObjectMapper()

    test("build keeps deterministic root order and emits all split sections") {
        val schema = SchemaDefinition(
            schemaFormat = "1.1",
            name = "demo",
            version = "2.0",
            description = "Demo schema",
            encoding = "latin-1",
            locale = "de_DE",
            customTypes = mapOf(
                "status_type" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("OPEN", "DONE"),
                    description = "Order status",
                )
            ),
            tables = mapOf(
                "orders" to TableDefinition(
                    description = "Orders",
                    columns = mapOf(
                        "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                        "status" to ColumnDefinition(type = NeutralType.Enum(refType = "status_type")),
                        "next_id" to ColumnDefinition(
                            type = NeutralType.Integer,
                            default = DefaultValue.SequenceNextVal("order_seq"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(IndexDefinition(name = "idx_orders_status", columns = listOf("status"), unique = true)),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "chk_orders_status",
                            type = ConstraintType.CHECK,
                            expression = "status <> ''",
                        )
                    ),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("id"),
                        partitions = listOf(PartitionDefinition(name = "p0", from = "0", to = "100")),
                    ),
                    metadata = TableMetadata(engine = "InnoDB", withoutRowid = true),
                )
            ),
            procedures = mapOf(
                "refresh_orders" to ProcedureDefinition(
                    description = "Refreshes order cache",
                    parameters = listOf(
                        ParameterDefinition("limit_rows", "integer", ParameterDirection.INOUT)
                    ),
                    language = "sql",
                    body = "BEGIN END",
                    dependencies = DependencyInfo(tables = listOf("orders")),
                    sourceDialect = "mysql",
                )
            ),
            functions = mapOf(
                "calc_total" to FunctionDefinition(
                    parameters = listOf(ParameterDefinition("net", "decimal")),
                    returns = ReturnType("decimal", precision = 10, scale = 2),
                    language = "sql",
                    deterministic = true,
                    body = "RETURN net",
                    dependencies = DependencyInfo(columns = mapOf("orders" to listOf("status"))),
                    sourceDialect = "mysql",
                )
            ),
            views = mapOf(
                "order_view" to ViewDefinition(
                    description = "Order projection",
                    materialized = true,
                    refresh = "manual",
                    query = "select id from orders",
                    dependencies = DependencyInfo(
                        tables = listOf("orders"),
                        views = listOf("source_view"),
                        columns = mapOf("orders" to listOf("id")),
                        functions = listOf("calc_total"),
                    ),
                    sourceDialect = "postgresql",
                )
            ),
            triggers = mapOf(
                "orders_audit" to TriggerDefinition(
                    description = "Audit trigger",
                    table = "orders",
                    event = TriggerEvent.UPDATE,
                    timing = TriggerTiming.AFTER,
                    forEach = TriggerForEach.STATEMENT,
                    condition = "NEW.id IS NOT NULL",
                    body = "INSERT INTO audit_log VALUES (NEW.id)",
                    dependencies = DependencyInfo(tables = listOf("orders")),
                    sourceDialect = "mysql",
                )
            ),
            sequences = mapOf(
                "order_seq" to SequenceDefinition(
                    description = "Sequence for orders",
                    start = 100,
                    increment = 5,
                    minValue = 10,
                    maxValue = 999,
                    cycle = true,
                    cache = 20,
                )
            ),
        )

        val root = SchemaNodeBuilder.build(mapper, schema)

        root.fieldNames().asSequence().toList() shouldContainExactly listOf(
            "schema_format",
            "name",
            "version",
            "description",
            "encoding",
            "locale",
            "custom_types",
            "tables",
            "procedures",
            "functions",
            "views",
            "triggers",
            "sequences",
        )

        root["schema_format"].asText() shouldBe "1.1"
        root["encoding"].asText() shouldBe "latin-1"
        root["custom_types"]["status_type"]["values"].map { it.asText() } shouldContainExactly listOf("OPEN", "DONE")
        root["tables"]["orders"]["columns"]["next_id"]["default"]["sequence_nextval"].asText() shouldBe "order_seq"
        root["tables"]["orders"]["metadata"]["engine"].asText() shouldBe "InnoDB"
        root["tables"]["orders"]["metadata"]["without_rowid"].asBoolean() shouldBe true
        root["tables"]["orders"]["partitioning"]["partitions"].single()["name"].asText() shouldBe "p0"
        root["procedures"]["refresh_orders"]["parameters"].single()["direction"].asText() shouldBe "inout"
        root["functions"]["calc_total"]["returns"]["precision"].asInt() shouldBe 10
        root["functions"]["calc_total"]["deterministic"].asBoolean() shouldBe true
        root["views"]["order_view"]["dependencies"]["functions"].map { it.asText() } shouldContainExactly listOf("calc_total")
        root["views"]["order_view"]["dependencies"]["columns"]["orders"].map { it.asText() } shouldContainExactly listOf("id")
        root["triggers"]["orders_audit"]["for_each"].asText() shouldBe "statement"
        root["sequences"]["order_seq"]["cache"].asInt() shouldBe 20
    }
})

