package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class OracleMetadataQueriesTest : FunSpec({

    test("listTableRefs scopes to owner and excludes recycle-bin objects") {
        val sql = slot<String>()
        val jdbc = mockk<JdbcOperations> {
            every { queryList(capture(sql), any()) } returns listOf(
                mapOf("table_name" to "A"),
                mapOf("table_name" to "B"),
            )
        }
        OracleMetadataQueries.listTableRefs(jdbc, "APP").map { it.name } shouldBe listOf("A", "B")
        sql.captured shouldContain "FROM all_tables"
        sql.captured shouldContain "owner = ?"
        sql.captured shouldContain "BIN\$"
    }

    test("listColumns maps identity metadata via the joined identity-cols sight") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("FROM all_tab_columns c") }, "APP", "T") } returns listOf(
                mapOf(
                    "column_name" to "ID", "data_type" to "NUMBER", "data_length" to 22,
                    "data_precision" to null, "data_scale" to null, "nullable" to "N",
                    "column_id" to 1, "data_default" to null,
                    "identity_generation" to "ALWAYS", "identity_sequence" to "ISEQ\$\$_1",
                ),
                mapOf(
                    "column_name" to "NAME", "data_type" to "VARCHAR2", "data_length" to 100,
                    "data_precision" to null, "data_scale" to null, "nullable" to "Y",
                    "column_id" to 2, "data_default" to "'anon' ",
                    "identity_generation" to null, "identity_sequence" to null,
                ),
            )
        }
        val rows = OracleMetadataQueries.listColumns(jdbc, "APP", "T")
        rows[0].isIdentity shouldBe true
        rows[0].identityGeneration shouldBe "ALWAYS"
        rows[0].identitySequenceName shouldBe "ISEQ\$\$_1"
        rows[0].nullable shouldBe false
        rows[1].isIdentity shouldBe false
        rows[1].nullable shouldBe true
        rows[1].defaultDefinition shouldBe "'anon'"
        rows[1].ordinal shouldBe 2
    }

    test("listPrimaryKeyColumns keeps constraint-column position order") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("constraint_type = 'P'") }, "APP", "T") } returns listOf(
                mapOf("column_name" to "TENANT"),
                mapOf("column_name" to "ID"),
            )
        }
        OracleMetadataQueries.listPrimaryKeyColumns(jdbc, "APP", "T") shouldBe listOf("TENANT", "ID")
    }

    test("listForeignKeys groups composite FKs and resolves the referenced table via r_owner/r_constraint_name") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("constraint_type = 'R'") }, "APP", "T") } returns listOf(
                mapOf(
                    "constraint_name" to "FK_AB", "column_name" to "A1", "position" to 1,
                    "referenced_table" to "PARENT", "referenced_column" to "P1",
                    "delete_rule" to "CASCADE",
                ),
                mapOf(
                    "constraint_name" to "FK_AB", "column_name" to "A2", "position" to 2,
                    "referenced_table" to "PARENT", "referenced_column" to "P2",
                    "delete_rule" to "CASCADE",
                ),
            )
        }
        val fks = OracleMetadataQueries.listForeignKeys(jdbc, "APP", "T")
        fks.size shouldBe 1
        fks[0].columns shouldBe listOf("A1", "A2")
        fks[0].referencedColumns shouldBe listOf("P1", "P2")
        fks[0].referencedTable shouldBe "PARENT"
        fks[0].onDelete shouldBe "CASCADE"
        // Oracle FKs kennen kein ON UPDATE.
        fks[0].onUpdate.shouldBeNull()
    }

    test("scanIndexes excludes the PK-backing index but keeps UNIQUE-constraint-backing ones") {
        val jdbc = mockk<JdbcOperations> {
            every {
                queryList(match { it.contains("constraint_type = 'P'") }, "APP", "T")
            } returns listOf(mapOf("index_name" to "SYS_C001"))
            every {
                queryList(match { it.contains("FROM all_indexes i") }, "APP", "T")
            } returns listOf(
                mapOf(
                    "index_name" to "SYS_C001", "uniqueness" to "UNIQUE",
                    "column_name" to "ID", "column_position" to 1, "descend" to "ASC",
                ),
                mapOf(
                    "index_name" to "SYS_C002", "uniqueness" to "UNIQUE",
                    "column_name" to "EMAIL", "column_position" to 1, "descend" to "ASC",
                ),
                mapOf(
                    "index_name" to "IX_NAME", "uniqueness" to "NONUNIQUE",
                    "column_name" to "NAME", "column_position" to 1, "descend" to "DESC",
                ),
            )
        }
        val scan = OracleMetadataQueries.scanIndexes(jdbc, "APP", "T")
        // SYS_C001 traegt die PK und ist ausgeschlossen (schon ueber
        // listPrimaryKeyColumns erfasst); SYS_C002 traegt eine UNIQUE-
        // Constraint und bleibt erhalten -- dafuer gibt es keine gesonderte
        // Oracle-Abfrage.
        scan.indices.map { it.name } shouldBe listOf("SYS_C002", "IX_NAME")
        scan.indices[0].isUnique shouldBe true
        scan.indices[1].isUnique shouldBe false
        scan.indices[1].directions shouldBe listOf(IndexSortDirection.DESC)
    }

    test("listCheckConstraints drops Oracle's implicit NOT-NULL checks") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("constraint_type = 'C'") }, "APP", "T") } returns listOf(
                mapOf("constraint_name" to "SYS_C002", "search_condition_vc" to "\"ID\" IS NOT NULL"),
                mapOf("constraint_name" to "CK_POS", "search_condition_vc" to "\"AMOUNT\" > 0"),
            )
        }
        val checks = OracleMetadataQueries.listCheckConstraints(jdbc, "APP", "T")
        checks.map { it.name } shouldBe listOf("CK_POS")
        checks[0].expression shouldBe "\"AMOUNT\" > 0"
        checks[0].type shouldBe "CHECK"
    }

    test("listSequences falls back to LAST_NUMBER and only reports cache when positive") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("FROM all_sequences") }, "APP") } returns listOf(
                mapOf(
                    "sequence_name" to "ORDER_SEQ", "last_number" to 101L, "increment_by" to 1L,
                    "min_value" to 1L, "max_value" to Long.MAX_VALUE, "cycle_flag" to "N", "cache_size" to 20,
                ),
                mapOf(
                    "sequence_name" to "NOCACHE_SEQ", "last_number" to 1L, "increment_by" to 1L,
                    "min_value" to 1L, "max_value" to 999L, "cycle_flag" to "Y", "cache_size" to 0,
                ),
            )
        }
        val seqs = OracleMetadataQueries.listSequences(jdbc, "APP")
        seqs[0].lastNumber shouldBe 101L
        seqs[0].cache shouldBe 20
        seqs[1].cycle shouldBe true
        seqs[1].cache.shouldBeNull()
    }

    test("listViews reads the raw select text without a CREATE VIEW wrapper") {
        val jdbc = mockk<JdbcOperations> {
            every { queryList(match { it.contains("FROM all_views") }, "APP") } returns listOf(
                mapOf("view_name" to "V_OPEN", "text" to "SELECT 1 AS ONE FROM DUAL"),
            )
        }
        val views = OracleMetadataQueries.listViews(jdbc, "APP")
        views.single().name shouldBe "V_OPEN"
        views.single().text shouldBe "SELECT 1 AS ONE FROM DUAL"
    }

    test("listUnreadObjects lists routines, triggers and packages") {
        val sql = slot<String>()
        val jdbc = mockk<JdbcOperations> {
            every { queryList(capture(sql), any()) } returns listOf(
                mapOf("object_type" to "PROCEDURE", "object_name" to "P_DO"),
                mapOf("object_type" to "FUNCTION", "object_name" to "F_CALC"),
                mapOf("object_type" to "TRIGGER", "object_name" to "TRG_AUDIT"),
                mapOf("object_type" to "PACKAGE", "object_name" to "PKG_UTIL"),
            )
        }
        val unread = OracleMetadataQueries.listUnreadObjects(jdbc, "APP")
        unread shouldBe listOf(
            OracleMetadataQueries.UnreadObject("PROCEDURE", "P_DO"),
            OracleMetadataQueries.UnreadObject("FUNCTION", "F_CALC"),
            OracleMetadataQueries.UnreadObject("TRIGGER", "TRG_AUDIT"),
            OracleMetadataQueries.UnreadObject("PACKAGE", "PKG_UTIL"),
        )
        sql.captured shouldContain "'PROCEDURE', 'FUNCTION', 'TRIGGER', 'PACKAGE'"
    }
})
