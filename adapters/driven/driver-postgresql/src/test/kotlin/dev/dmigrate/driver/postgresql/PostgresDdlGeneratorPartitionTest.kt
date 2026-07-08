package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

/**
 * Partitions-Generate-Tests (ADR 0019) — eigene Spec, damit
 * PostgresDdlGeneratorTableTest unter der LargeClass-Schwelle bleibt
 * (echte Aufteilung, kein `@Suppress`).
 */
class PostgresDdlGeneratorPartitionTest : FunSpec({

    val generator = PostgresDdlGenerator()

    fun schema(
        name: String = "test_schema",
        version: String = "1.0",
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap()
    ) = SchemaDefinition(
        name = name,
        version = version,
        tables = tables,
        customTypes = customTypes,
        sequences = sequences,
        views = views,
        functions = functions,
        procedures = procedures,
        triggers = triggers
    )

    fun table(
        columns: Map<String, ColumnDefinition>,
        primaryKey: List<String> = emptyList(),
        indices: List<IndexDefinition> = emptyList(),
        constraints: List<ConstraintDefinition> = emptyList(),
        partitioning: PartitionConfig? = null
    ) = TableDefinition(
        columns = columns,
        primaryKey = primaryKey,
        indices = indices,
        constraints = constraints,
        partitioning = partitioning
    )

    fun col(
        type: NeutralType,
        required: Boolean = false,
        unique: Boolean = false,
        default: DefaultValue? = null,
        references: ReferenceDefinition? = null,
        generation: ColumnGeneration? = null,
    ) = ColumnDefinition(
        type = type,
        required = required,
        unique = unique,
        default = default,
        references = references,
        generation = generation,
    )

    test("partitioning generates PARTITION BY RANGE with sub-partitions") {
        val s = schema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "event_date" to col(NeutralType.Date)
                    ),
                    primaryKey = listOf("id", "event_date"),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("event_date"),
                        partitions = listOf(
                            PartitionDefinition(
                                name = "events_2024",
                                from = listOf(PartitionBound.Value("'2024-01-01'")),
                                to = listOf(PartitionBound.Value("'2025-01-01'")),
                            ),
                            PartitionDefinition(
                                name = "events_2025",
                                from = listOf(PartitionBound.Value("'2025-01-01'")),
                                to = listOf(PartitionBound.Value("'2026-01-01'")),
                            )
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "PARTITION BY RANGE (\"event_date\")"
        ddl shouldContain "CREATE TABLE \"events_2024\" PARTITION OF \"events\" FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');"
        ddl shouldContain "CREATE TABLE \"events_2025\" PARTITION OF \"events\" FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');"
    }

    test("child-local partition index is emitted on the partition table (AP2a)") {
        val s = schema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "event_date" to col(NeutralType.Date),
                    ),
                    primaryKey = listOf("id", "event_date"),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("event_date"),
                        partitions = listOf(
                            PartitionDefinition(
                                name = "events_2024",
                                from = listOf(PartitionBound.Value("'2024-01-01'")),
                                to = listOf(PartitionBound.Value("'2025-01-01'")),
                                indices = listOf(IndexDefinition(
                                    name = "idx_events_2024_id", columns = listOf(IndexColumn("id")),
                                )),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE TABLE \"events_2024\" PARTITION OF \"events\""
        ddl shouldContain "CREATE INDEX \"idx_events_2024_id\" ON \"events_2024\" (\"id\");"
    }

    test("DEFAULT partition is emitted as PARTITION OF ... DEFAULT (AP3)") {
        val s = schema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "region" to col(NeutralType.Text()),
                    ),
                    partitioning = PartitionConfig(
                        type = PartitionType.LIST,
                        key = listOf("region"),
                        partitions = listOf(
                            PartitionDefinition(name = "events_eu", values = listOf("'eu'")),
                            PartitionDefinition(name = "events_rest", isDefault = true),
                        ),
                    ),
                ),
            ),
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE TABLE \"events_eu\" PARTITION OF \"events\" FOR VALUES IN ('eu');"
        ddl shouldContain "CREATE TABLE \"events_rest\" PARTITION OF \"events\" DEFAULT;"
    }

    // ── AP6.5 (ADR 0020 §6): a MySQL-reverse-shaped model — `from` reconstructed from
    //    contiguity (first = MINVALUE), HASH modulus/remainder synthesized — generates
    //    valid PG DDL. Mirrors what MysqlPartitionReader produces (round-trip MySQL→PG). ──

    test("MySQL-reconstructed RANGE model (first from = MINVALUE) generates valid PG FROM/TO (AP6.5)") {
        val s = schema(
            tables = mapOf(
                "payment" to table(
                    columns = mapOf(
                        "payment_id" to col(NeutralType.Integer),
                        "payment_date" to col(NeutralType.DateTime(timezone = true)),
                    ),
                    primaryKey = listOf("payment_id", "payment_date"),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("payment_date"),
                        partitions = listOf(
                            PartitionDefinition(
                                name = "p1",
                                from = listOf(PartitionBound.MinValue),
                                to = listOf(PartitionBound.Value("'2022-02-01 00:00:00'")),
                            ),
                            PartitionDefinition(
                                name = "p_max",
                                from = listOf(PartitionBound.Value("'2022-02-01 00:00:00'")),
                                to = listOf(PartitionBound.MaxValue),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "PARTITION BY RANGE (\"payment_date\")"
        ddl shouldContain "CREATE TABLE \"p1\" PARTITION OF \"payment\" FOR VALUES FROM (MINVALUE) TO ('2022-02-01 00:00:00');"
        ddl shouldContain "CREATE TABLE \"p_max\" PARTITION OF \"payment\" FOR VALUES FROM ('2022-02-01 00:00:00') TO (MAXVALUE);"
    }

    test("MySQL-reconstructed HASH model (modulus/remainder) generates valid PG WITH clause (AP6.5)") {
        val s = schema(
            tables = mapOf(
                "data" to table(
                    columns = mapOf("id" to col(NeutralType.Integer)),
                    primaryKey = listOf("id"),
                    partitioning = PartitionConfig(
                        type = PartitionType.HASH,
                        key = listOf("id"),
                        partitions = listOf(
                            PartitionDefinition(name = "p0", modulus = 4, remainder = 0),
                            PartitionDefinition(name = "p1", modulus = 4, remainder = 1),
                            PartitionDefinition(name = "p2", modulus = 4, remainder = 2),
                            PartitionDefinition(name = "p3", modulus = 4, remainder = 3),
                        ),
                    ),
                ),
            ),
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "PARTITION BY HASH (\"id\")"
        ddl shouldContain "CREATE TABLE \"p0\" PARTITION OF \"data\" FOR VALUES WITH (MODULUS 4, REMAINDER 0);"
        ddl shouldContain "CREATE TABLE \"p3\" PARTITION OF \"data\" FOR VALUES WITH (MODULUS 4, REMAINDER 3);"
    }

})
