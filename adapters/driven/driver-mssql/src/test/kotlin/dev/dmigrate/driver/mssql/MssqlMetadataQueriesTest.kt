package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class MssqlMetadataQueriesTest : FunSpec({

    test("listTableRefs scopes to schema and excludes ms-shipped tables") {
        val sql = slot<String>()
        val jdbc = mockk<JdbcOperations> {
            every { queryList(capture(sql), any()) } returns listOf(
                mapOf("table_name" to "a", "schema_name" to "dbo"),
                mapOf("table_name" to "b", "schema_name" to "dbo"),
            )
        }
        MssqlMetadataQueries.listTableRefs(jdbc, "dbo").map { it.name } shouldBe listOf("a", "b")
        sql.captured shouldContain "FROM sys.tables t"
        sql.captured shouldContain "t.is_ms_shipped = 0"
        sql.captured shouldContain "s.name = ?"
    }

    test("listColumns maps identity, default and computed metadata; driver number types vary") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("FROM sys.columns c") }, any()) } returns listOf(
                mapOf(
                    "column_name" to "id", "type_name" to "int",
                    // mssql-jdbc liefert Katalogzahlen je nach Spalte als
                    // Short/Int/Long — der Mapper normalisiert über Number.
                    "max_length" to 4.toShort(), "precision" to 10.toShort(), "scale" to 0.toShort(),
                    "is_nullable" to false, "is_identity" to true, "is_computed" to false,
                    "column_id" to 1, "default_definition" to null,
                    "seed_value" to java.math.BigDecimal(5), "increment_value" to java.math.BigDecimal(2),
                    "computed_definition" to null,
                ),
                mapOf(
                    "column_name" to "total", "type_name" to "money",
                    "max_length" to 8.toShort(), "precision" to 19.toShort(), "scale" to 4.toShort(),
                    "is_nullable" to true, "is_identity" to false, "is_computed" to true,
                    "column_id" to 2, "default_definition" to "((0))",
                    "seed_value" to null, "increment_value" to null,
                    "computed_definition" to "([a]+[b])",
                ),
            )
        }
        val rows = MssqlMetadataQueries.listColumns(jdbc, "[dbo].[t]")
        rows[0].isIdentity shouldBe true
        rows[0].identitySeed shouldBe 5L
        rows[0].identityIncrement shouldBe 2L
        rows[0].maxLength shouldBe 4
        rows[0].nullable shouldBe false
        rows[1].isComputed shouldBe true
        rows[1].computedDefinition shouldBe "([a]+[b])"
        rows[1].defaultDefinition shouldBe "((0))"
        rows[1].ordinal shouldBe 2
    }

    test("listForeignKeys groups composite FKs and converts action descs to space form") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("FROM sys.foreign_keys fk") }, any()) } returns listOf(
                mapOf(
                    "constraint_name" to "fk_ab", "column_name" to "a1",
                    "referenced_table" to "parent", "referenced_column" to "p1",
                    "delete_referential_action_desc" to "SET_NULL",
                    "update_referential_action_desc" to "NO_ACTION",
                    "constraint_column_id" to 1,
                ),
                mapOf(
                    "constraint_name" to "fk_ab", "column_name" to "a2",
                    "referenced_table" to "parent", "referenced_column" to "p2",
                    "delete_referential_action_desc" to "SET_NULL",
                    "update_referential_action_desc" to "NO_ACTION",
                    "constraint_column_id" to 2,
                ),
            )
        }
        val fks = MssqlMetadataQueries.listForeignKeys(jdbc, "[dbo].[t]")
        fks.size shouldBe 1
        fks[0].columns shouldBe listOf("a1", "a2")
        fks[0].referencedColumns shouldBe listOf("p1", "p2")
        fks[0].onDelete shouldBe "SET NULL"
        fks[0].onUpdate shouldBe "NO ACTION"
    }

    test("scanIndexes filters INCLUDE columns from keys and reports affected indexes") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("FROM sys.indexes i") }, any()) } returns listOf(
                mapOf(
                    "index_name" to "ix_a", "is_unique" to false, "has_filter" to false,
                    "filter_definition" to null, "column_name" to "a",
                    "key_ordinal" to 1, "is_descending_key" to true, "is_included_column" to false,
                ),
                mapOf(
                    "index_name" to "ix_a", "is_unique" to false, "has_filter" to false,
                    "filter_definition" to null, "column_name" to "payload",
                    "key_ordinal" to 0, "is_descending_key" to false, "is_included_column" to true,
                ),
                mapOf(
                    "index_name" to "ux_b", "is_unique" to true, "has_filter" to true,
                    "filter_definition" to "([b] IS NOT NULL)", "column_name" to "b",
                    "key_ordinal" to 1, "is_descending_key" to false, "is_included_column" to false,
                ),
            )
        }
        val scan = MssqlMetadataQueries.scanIndexes(jdbc, "[dbo].[t]")
        scan.indexesWithIncludedColumns shouldBe listOf("ix_a")
        val ixA = scan.indices.first { it.name == "ix_a" }
        ixA.columns shouldBe listOf("a")
        ixA.directions shouldBe listOf(IndexSortDirection.DESC)
        val uxB = scan.indices.first { it.name == "ux_b" }
        uxB.isUnique shouldBe true
        uxB.where shouldBe "([b] IS NOT NULL)"
    }

    test("listCheckConstraints unwraps the outer parenthesis pair") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("FROM sys.check_constraints cc") }, any()) } returns listOf(
                mapOf("constraint_name" to "ck_pos", "definition" to "([amount]>(0))"),
            )
        }
        val checks = MssqlMetadataQueries.listCheckConstraints(jdbc, "[dbo].[t]")
        checks[0].expression shouldBe "amount>(0)"
        checks[0].type shouldBe "CHECK"
    }

    test("listSequences maps bigint-cast attributes; cache only when cached") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("FROM sys.sequences seq") }, any()) } returns listOf(
                mapOf(
                    "sequence_name" to "order_seq", "type_name" to "bigint",
                    "start_value" to 100L, "increment_value" to 5L,
                    "minimum_value" to Long.MIN_VALUE, "maximum_value" to Long.MAX_VALUE,
                    "is_cycling" to false, "is_cached" to true, "cache_size" to 50,
                ),
                mapOf(
                    "sequence_name" to "nocache_seq", "type_name" to "int",
                    "start_value" to 1L, "increment_value" to 1L,
                    "minimum_value" to 1L, "maximum_value" to 9999L,
                    "is_cycling" to true, "is_cached" to false, "cache_size" to null,
                ),
            )
        }
        val seqs = MssqlMetadataQueries.listSequences(jdbc, "dbo")
        seqs[0].start shouldBe 100L
        seqs[0].increment shouldBe 5L
        seqs[0].cache shouldBe 50
        seqs[1].cycle shouldBe true
        seqs[1].cache.shouldBeNull()
    }

    test("listUnreadObjects lists routines and triggers with trimmed type codes") {
        val sql = slot<String>()
        val jdbc = mockk<JdbcOperations> {
            every { queryList(capture(sql), any()) } returns listOf(
                mapOf("object_type" to "P ", "object_name" to "usp_do"),
                mapOf("object_type" to "PC", "object_name" to "usp_clr"),
                mapOf("object_type" to "TR", "object_name" to "trg_audit"),
            )
        }
        val unread = MssqlMetadataQueries.listUnreadObjects(jdbc, "dbo")
        unread shouldBe listOf(
            MssqlMetadataQueries.UnreadObject("P", "usp_do"),
            MssqlMetadataQueries.UnreadObject("PC", "usp_clr"),
            MssqlMetadataQueries.UnreadObject("TR", "trg_audit"),
        )
        // CLR-Varianten gehoeren in den Scan — sonst fallen Objekte still weg.
        listOf("'PC'", "'FS'", "'FT'", "'TA'").forEach { sql.captured shouldContain it }
    }

    test("listPrimaryKeyColumns keeps key ordinal order") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("kc.type = 'PK'") }, any()) } returns listOf(
                mapOf("column_name" to "tenant"),
                mapOf("column_name" to "id"),
            )
        }
        MssqlMetadataQueries.listPrimaryKeyColumns(jdbc, "[dbo].[t]") shouldBe listOf("tenant", "id")
    }
})
