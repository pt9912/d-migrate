package dev.dmigrate.driver.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.ResultSetMetaData

class NullabilityResolverTest : FunSpec({

    test("JDBC NULLABLE ohne SchemaReader -> nullable=true, JDBC_METADATA") {
        val d = NullabilityResolver.resolve(ResultSetMetaData.columnNullable, null)
        d.nullable shouldBe true
        d.origin shouldBe NullabilityOrigin.JDBC_METADATA
        d.diagnostic shouldBe null
    }

    test("JDBC NULLABLE + SchemaReader=true -> nullable=true, MERGED") {
        val d = NullabilityResolver.resolve(ResultSetMetaData.columnNullable, schemaReaderNullable = true)
        d.nullable shouldBe true
        d.origin shouldBe NullabilityOrigin.MERGED
    }

    test("JDBC NULLABLE + SchemaReader=false -> nullable=false, MERGED_CONFLICT") {
        val d = NullabilityResolver.resolve(ResultSetMetaData.columnNullable, schemaReaderNullable = false)
        d.nullable shouldBe false
        d.origin shouldBe NullabilityOrigin.MERGED_CONFLICT
        d.diagnostic shouldBe "JDBC=NULLABLE, SchemaReader=NOT_NULL — SchemaReader gewinnt"
    }

    test("JDBC NOT_NULL ohne SchemaReader -> nullable=false, JDBC_METADATA") {
        val d = NullabilityResolver.resolve(ResultSetMetaData.columnNoNulls, null)
        d.nullable shouldBe false
        d.origin shouldBe NullabilityOrigin.JDBC_METADATA
    }

    test("JDBC NOT_NULL + SchemaReader=false -> nullable=false, MERGED") {
        val d = NullabilityResolver.resolve(ResultSetMetaData.columnNoNulls, schemaReaderNullable = false)
        d.nullable shouldBe false
        d.origin shouldBe NullabilityOrigin.MERGED
    }

    test("JDBC NOT_NULL + SchemaReader=true -> nullable=true, MERGED_CONFLICT") {
        val d = NullabilityResolver.resolve(ResultSetMetaData.columnNoNulls, schemaReaderNullable = true)
        d.nullable shouldBe true
        d.origin shouldBe NullabilityOrigin.MERGED_CONFLICT
        d.diagnostic shouldBe "JDBC=NOT_NULL, SchemaReader=NULLABLE — SchemaReader gewinnt"
    }

    test("JDBC UNKNOWN ohne SchemaReader -> nullable=true, DEFAULT_PERMISSIVE mit Diagnostic") {
        val d = NullabilityResolver.resolve(ResultSetMetaData.columnNullableUnknown, null)
        d.nullable shouldBe true
        d.origin shouldBe NullabilityOrigin.DEFAULT_PERMISSIVE
        d.diagnostic shouldBe "JDBC=UNKNOWN, kein SchemaReader-Hint — Fallback auf nullable=true"
    }

    test("JDBC UNKNOWN + SchemaReader=true -> nullable=true, SCHEMA_READER") {
        val d = NullabilityResolver.resolve(ResultSetMetaData.columnNullableUnknown, schemaReaderNullable = true)
        d.nullable shouldBe true
        d.origin shouldBe NullabilityOrigin.SCHEMA_READER
    }

    test("JDBC UNKNOWN + SchemaReader=false -> nullable=false, SCHEMA_READER") {
        val d = NullabilityResolver.resolve(ResultSetMetaData.columnNullableUnknown, schemaReaderNullable = false)
        d.nullable shouldBe false
        d.origin shouldBe NullabilityOrigin.SCHEMA_READER
    }

    test("Unbekannter JDBC-Wert wird als UNKNOWN behandelt") {
        val d = NullabilityResolver.resolve(jdbcIsNullable = 99, schemaReaderNullable = null)
        d.nullable shouldBe true
        d.origin shouldBe NullabilityOrigin.DEFAULT_PERMISSIVE
    }
})
