package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * postgresql-default-schema-context.md (Weg A): jeder schema-gebundene
 * Bezeichner -- und jede Referenz darauf -- wird mit `default_schema`
 * qualifiziert; Spalten-, Index- und Constraint-Namen sowie der
 * Trigger-eigene Name nie. Ohne `default_schema`: unqualifiziert, identisch
 * zum bisherigen Verhalten (Golden-Master-Sicherheit).
 */
class PostgresDefaultSchemaQualificationTest : FunSpec({

    val generator = PostgresDdlGenerator()
    val qualifiedOptions = DdlGenerationOptions(
        dialectContext = DdlDialectContext.Postgres(defaultSchema = "analytics"),
    )

    test("CREATE TABLE, its index and its FK reference are qualified; PK/columns are not") {
        val schema = SchemaDefinition(
            name = "app", version = "1.0",
            tables = mapOf(
                "customers" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier(true), required = true)),
                    primaryKey = listOf("id"),
                ),
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(true), required = true),
                        "customer_id" to ColumnDefinition(
                            NeutralType.Integer, required = true,
                            references = ReferenceDefinition(table = "customers", column = "id"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(IndexDefinition(columns = listOf(IndexColumn("customer_id")))),
                ),
            ),
        )
        val sql = generator.generate(schema, qualifiedOptions).render()

        sql shouldContain "CREATE TABLE \"analytics\".\"customers\""
        sql shouldContain "CREATE TABLE \"analytics\".\"orders\""
        sql shouldContain "CONSTRAINT \"fk_orders_customer_id\" FOREIGN KEY (\"customer_id\") " +
            "REFERENCES \"analytics\".\"customers\" (\"id\")"
        sql shouldContain "PRIMARY KEY (\"id\")"
        sql shouldContain "ON \"analytics\".\"orders\""
        // The index's own name is never schema-qualified in PostgreSQL syntax.
        sql shouldNotContain "INDEX \"analytics\"."
    }

    test("partition parent and child tables are both qualified") {
        val schema = SchemaDefinition(
            name = "app", version = "1.0",
            tables = mapOf(
                "events" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(true), required = true),
                        "created_at" to ColumnDefinition(NeutralType.Date, required = true),
                    ),
                    primaryKey = listOf("id", "created_at"),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("created_at"),
                        partitions = listOf(
                            PartitionDefinition(name = "events_2026", isDefault = true),
                        ),
                    ),
                ),
            ),
        )
        val sql = generator.generate(schema, qualifiedOptions).render()

        sql shouldContain "CREATE TABLE \"analytics\".\"events\""
        sql shouldContain "CREATE TABLE \"analytics\".\"events_2026\" PARTITION OF \"analytics\".\"events\""
    }

    test("a named sequence and its DEFAULT nextval() reference are both qualified") {
        val schema = SchemaDefinition(
            name = "app", version = "1.0",
            sequences = mapOf("order_no_seq" to SequenceDefinition()),
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(
                            NeutralType.Integer, required = true,
                            default = DefaultValue.SequenceNextVal("order_no_seq"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val sql = generator.generate(schema, qualifiedOptions).render()

        sql shouldContain "CREATE SEQUENCE \"analytics\".\"order_no_seq\""
        sql shouldContain "DEFAULT nextval('analytics.order_no_seq')"
    }

    test("an ENUM custom type and a column referencing it via refType are both qualified") {
        val schema = SchemaDefinition(
            name = "app", version = "1.0",
            customTypes = mapOf("order_status" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("NEW", "PAID"))),
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(true), required = true),
                        "status" to ColumnDefinition(NeutralType.Enum(refType = "order_status"), required = true),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val sql = generator.generate(schema, qualifiedOptions).render()

        sql shouldContain "CREATE TYPE \"analytics\".\"order_status\" AS ENUM ('NEW', 'PAID')"
        sql shouldContain "\"status\" \"analytics\".\"order_status\""
    }

    test("without default_schema, rendering is unqualified — identical to the pre-existing default") {
        val schema = SchemaDefinition(
            name = "app", version = "1.0",
            sequences = mapOf("seq" to SequenceDefinition()),
            customTypes = mapOf("status" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("A"))),
            tables = mapOf(
                "t" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(true), required = true),
                        "s" to ColumnDefinition(NeutralType.Enum(refType = "status"), required = true),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        // deterministic=true: the header's runtime timestamp would otherwise
        // differ between the two calls and defeat the equality check below,
        // which is not what this test is about.
        val defaultSql = generator.generate(schema, DdlGenerationOptions(deterministic = true)).render()
        val explicitNoneSql = generator.generate(
            schema,
            DdlGenerationOptions(deterministic = true, dialectContext = DdlDialectContext.None),
        ).render()

        defaultSql shouldContain "CREATE TABLE \"t\" ("
        defaultSql shouldContain "CREATE SEQUENCE \"seq\""
        defaultSql shouldContain "CREATE TYPE \"status\" AS ENUM ('A')"
        defaultSql shouldContain "\"s\" \"status\""
        defaultSql shouldNotContain "\".\""
        defaultSql shouldBe explicitNoneSql
    }
})
