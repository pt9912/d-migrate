package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.col
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.idTable
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.schema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain

class MssqlDdlGeneratorRollbackTest : FunSpec({

    val generator = MssqlDdlGenerator()

    test("rollback inverts tables, indexes (with ON table), views, sequences and deferred FKs in reverse order") {
        val a = idTable("b_id" to col(NeutralType.Integer, references = ReferenceDefinition("b", "id")))
        val b = idTable("a_id" to col(NeutralType.Integer, references = ReferenceDefinition("a", "id"))).copy(
            indices = listOf(IndexDefinition("ix_b", listOf(IndexColumn("a_id")), unique = true)),
        )
        val down = generator.generateRollback(
            schema(
                tables = mapOf("a" to a, "b" to b),
                sequences = mapOf("s" to SequenceDefinition()),
                views = mapOf("v" to ViewDefinition(query = "SELECT id FROM a")),
            ),
        ).statements.map { it.sql }

        down shouldBe listOf(
            "DROP VIEW IF EXISTS [v];",
            "ALTER TABLE [b] DROP CONSTRAINT IF EXISTS [fk_b_a_id];",
            "ALTER TABLE [a] DROP CONSTRAINT IF EXISTS [fk_a_b_id];",
            "DROP INDEX IF EXISTS [ix_b] ON [b];",
            "DROP TABLE IF EXISTS [b];",
            "DROP TABLE IF EXISTS [a];",
            "DROP SEQUENCE IF EXISTS [s];",
        )
    }

    test("bracketed identifiers with spaces and escaped brackets survive inversion") {
        val table = idTable("n" to col(NeutralType.Text(10))).copy(
            indices = listOf(IndexDefinition("my idx]x", listOf(IndexColumn("n")))),
        )
        val down = generator.generateRollback(schema(tables = mapOf("my table" to table))).statements.map { it.sql }
        down shouldContain "DROP INDEX IF EXISTS [my idx]]x] ON [my table];"
        down shouldContain "DROP TABLE IF EXISTS [my table];"
    }

    test("spatial indexes and bracketed constraint names invert bracket-aware") {
        val places = idTable(
            "loc" to col(NeutralType.Geometry(srid = 4326)),
            "other_id" to col(NeutralType.Integer, references = ReferenceDefinition("other tbl", "id")),
        ).copy(indices = listOf(IndexDefinition("sx loc", listOf(IndexColumn("loc")), type = dev.dmigrate.core.model.IndexType.SPATIAL)))
        val other = idTable("p_id" to col(NeutralType.Integer, references = ReferenceDefinition("places", "id")))
        val down = generator.generateRollback(
            schema(tables = mapOf("places" to places, "other tbl" to other)),
            DdlGenerationOptions(spatialProfile = SpatialProfile.NATIVE),
        ).statements.map { it.sql }
        down shouldContain "DROP INDEX IF EXISTS [sx loc] ON [places];"
        down shouldContain "ALTER TABLE [places] DROP CONSTRAINT IF EXISTS [fk_places_other_id];"
        down shouldContain "ALTER TABLE [other tbl] DROP CONSTRAINT IF EXISTS [fk_other tbl_p_id];"
    }

    test("an index name containing ' on ' still inverts to the right table") {
        val table = idTable("d" to col(NeutralType.Date)).copy(
            indices = listOf(IndexDefinition("discount on weekends", listOf(IndexColumn("d")))),
        )
        val down = generator.generateRollback(schema(tables = mapOf("weekends" to table))).statements.map { it.sql }
        down shouldContain "DROP INDEX IF EXISTS [discount on weekends] ON [weekends];"
    }
})
