package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.col
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.notesWithCode
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.schema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MssqlDdlGeneratorIndexTest : FunSpec({

    val generator = MssqlDdlGenerator()

    fun table(vararg indices: IndexDefinition) = TableDefinition(
        columns = linkedMapOf(
            "id" to col(NeutralType.Identifier(autoIncrement = true)),
            "name" to col(NeutralType.Text(100)),
            "state" to col(NeutralType.Text(20)),
            "body" to col(NeutralType.Text()),
            "doc" to col(NeutralType.Json),
            "geo" to col(NeutralType.Geometry(GeometryType("point"), 4326)),
            "shape" to col(NeutralType.Geometry(GeometryType("polygon"), 3857)),
        ),
        primaryKey = listOf("id"),
        indices = indices.toList(),
    )

    fun render(vararg indices: IndexDefinition, profile: SpatialProfile = SpatialProfile.NATIVE) =
        generator.generate(schema(tables = mapOf("t" to table(*indices))), DdlGenerationOptions(profile))

    test("plain, unique, direction and filtered indexes") {
        val ddl = render(
            IndexDefinition("ix_name", listOf(IndexColumn("name"))),
            IndexDefinition("ux_state", listOf(IndexColumn("state", IndexSortDirection.DESC)), unique = true),
            IndexDefinition("ix_open", listOf(IndexColumn("state")), where = "[state] = N'open'"),
        ).render()
        ddl shouldContain "CREATE INDEX [ix_name] ON [t] ([name]);"
        ddl shouldContain "CREATE UNIQUE INDEX [ux_state] ON [t] ([state] DESC);"
        ddl shouldContain "CREATE INDEX [ix_open] ON [t] ([state]) WHERE [state] = N'open';"
    }

    // ADR 0049: abdeckende und clustered Indizes

    test("include columns render as INCLUDE, outside the key") {
        val ddl = render(
            IndexDefinition("ix_cover", listOf(IndexColumn("name")), includeColumns = listOf("state", "doc")),
        ).render()
        ddl shouldContain "CREATE INDEX [ix_cover] ON [t] ([name]) INCLUDE ([state], [doc]);"
    }

    test("INCLUDE stands between the key and a filter, the order T-SQL demands") {
        val ddl = render(
            IndexDefinition(
                "ix_cover_open", listOf(IndexColumn("name")),
                where = "[state] = N'open'", includeColumns = listOf("state"),
            ),
        ).render()
        ddl shouldContain "CREATE INDEX [ix_cover_open] ON [t] ([name]) INCLUDE ([state]) WHERE [state] = N'open';"
    }

    test("a clustered index renders as CLUSTERED and pushes the primary key to NONCLUSTERED") {
        // Es gibt genau eine Ablage. Ohne das Umschalten des Primaerschluessels
        // antwortet SQL Server mit Msg 1902.
        val ddl = render(IndexDefinition("ix_storage", listOf(IndexColumn("state")), clustered = true)).render()
        ddl shouldContain "CREATE CLUSTERED INDEX [ix_storage] ON [t] ([state]);"
        ddl shouldContain "PRIMARY KEY NONCLUSTERED ([id])"
    }

    test("without a clustered index the primary key keeps the default storage") {
        val ddl = render(IndexDefinition("ix_name", listOf(IndexColumn("name")))).render()
        ddl shouldContain "PRIMARY KEY ([id])"
        ddl shouldNotContain "NONCLUSTERED"
    }

    test("a unique clustered index keeps both keywords in T-SQL's order") {
        val ddl = render(
            IndexDefinition("ux_storage", listOf(IndexColumn("state")), unique = true, clustered = true),
        ).render()
        ddl shouldContain "CREATE UNIQUE CLUSTERED INDEX [ux_storage] ON [t] ([state]);"
    }

    test("two indexes claiming the storage are blocked with E066, not guessed") {
        val result = render(
            IndexDefinition("ix_a", listOf(IndexColumn("name")), clustered = true),
            IndexDefinition("ix_b", listOf(IndexColumn("state")), clustered = true),
        )
        // Welcher gemeint ist, kann das Werkzeug nicht entscheiden — und der
        // zweite `CREATE CLUSTERED INDEX` scheiterte mit Msg 1902.
        result.render() shouldNotContain "CREATE CLUSTERED INDEX"
        result.notes.filter { it.code == "E066" }.map { it.objectName } shouldBe listOf("ix_a", "ix_b")
    }

    test("unnamed index gets idx_<table>_<cols>") {
        render(IndexDefinition(columns = listOf(IndexColumn("name"), IndexColumn("state")))).render() shouldContain
            "CREATE INDEX [idx_t_name_state] ON [t] ([name], [state]);"
    }

    test("non-btree access methods are created as nonclustered indexes with W102") {
        val result = render(
            IndexDefinition("ix_hash", listOf(IndexColumn("state")), type = IndexType.HASH),
            IndexDefinition("ix_gin", listOf(IndexColumn("name")), type = IndexType.GIN),
        )
        result.render() shouldContain "CREATE INDEX [ix_hash] ON [t] ([state]);"
        result.render() shouldContain "CREATE INDEX [ix_gin] ON [t] ([name]);"
        result.notesWithCode("W102").map { it.objectName } shouldBe listOf("ix_hash", "ix_gin")
    }

    test("large-object key columns are skipped with W141") {
        val result = render(
            IndexDefinition("ix_body", listOf(IndexColumn("body"))),
            IndexDefinition("ix_doc", listOf(IndexColumn("doc"))),
        )
        result.render() shouldNotContain "CREATE INDEX [ix_body]"
        result.render() shouldNotContain "CREATE INDEX [ix_doc]"
        result.notesWithCode("W141").map { it.objectName } shouldBe listOf("ix_body", "ix_doc")
    }

    test("prefix lengths are dropped with W126") {
        val result = render(IndexDefinition("ix_pref", listOf(IndexColumn("name", prefixLength = 10))))
        result.render() shouldContain "CREATE INDEX [ix_pref] ON [t] ([name]);"
        result.notesWithCode("W126").single().objectName shouldBe "ix_pref"
    }

    test("spatial indexes render on geography columns; planar geometry and full-text indexes are E057") {
        val result = render(
            IndexDefinition("sx_geo", listOf(IndexColumn("geo")), type = IndexType.SPATIAL),
            IndexDefinition("gx_geo", listOf(IndexColumn("geo")), type = IndexType.GIST),
            IndexDefinition("sx_shape", listOf(IndexColumn("shape")), type = IndexType.SPATIAL),
            IndexDefinition("fx_name", listOf(IndexColumn("name")), type = IndexType.FULLTEXT),
        )
        val ddl = result.render()
        ddl shouldContain "CREATE SPATIAL INDEX [sx_geo] ON [t] ([geo]);"
        ddl shouldContain "CREATE SPATIAL INDEX [gx_geo] ON [t] ([geo]);"
        ddl shouldNotContain "CREATE SPATIAL INDEX [sx_shape]"
        result.statements.joinToString("\n") { it.sql } shouldNotContain "FULLTEXT"
        result.notesWithCode("E057").map { it.objectName } shouldBe listOf("sx_shape", "fx_name")
    }

    test("index on a domain-over-text enum column is a LOB key and skipped with W141") {
        val customTypes = mapOf(
            "email_t" to dev.dmigrate.core.model.CustomTypeDefinition(dev.dmigrate.core.model.CustomTypeKind.DOMAIN, baseType = "text"),
        )
        val table = TableDefinition(
            columns = linkedMapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "mail" to col(NeutralType.Enum(refType = "email_t")),
            ),
            primaryKey = listOf("id"),
            indices = listOf(IndexDefinition("ix_mail", listOf(IndexColumn("mail")))),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table), customTypes = customTypes))
        result.render() shouldNotContain "CREATE INDEX [ix_mail]"
        result.notesWithCode("W141").single().objectName shouldBe "ix_mail"
    }

    test("spatial index needs a primary key and exactly one column, otherwise E057") {
        val noPk = TableDefinition(
            columns = linkedMapOf("geo" to col(NeutralType.Geometry(srid = 4326))),
            indices = listOf(IndexDefinition("sx_nopk", listOf(IndexColumn("geo")), type = IndexType.SPATIAL)),
        )
        val multi = TableDefinition(
            columns = linkedMapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "a" to col(NeutralType.Geometry(srid = 4326)),
                "b" to col(NeutralType.Geometry(srid = 4326)),
            ),
            primaryKey = listOf("id"),
            indices = listOf(IndexDefinition("sx_multi", listOf(IndexColumn("a"), IndexColumn("b")), type = IndexType.SPATIAL)),
        )
        val result = generator.generate(
            schema(tables = mapOf("nopk" to noPk, "multi" to multi)),
            DdlGenerationOptions(SpatialProfile.NATIVE),
        )
        result.statements.joinToString("\n") { it.sql } shouldNotContain "CREATE SPATIAL INDEX"
        result.notesWithCode("E057").map { it.objectName } shouldBe listOf("sx_nopk", "sx_multi")
        result.notesWithCode("E057").first { it.objectName == "sx_nopk" }.message shouldContain "primary key"
        result.notesWithCode("E057").first { it.objectName == "sx_multi" }.message shouldContain "exactly one column"
    }

    test("spatial index is E057 when the primary key itself is dropped as a LOB key") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "code" to col(NeutralType.Text(), required = true),
                "geo" to col(NeutralType.Geometry(srid = 4326)),
            ),
            primaryKey = listOf("code"),
            indices = listOf(IndexDefinition("sx_geo", listOf(IndexColumn("geo")), type = IndexType.SPATIAL)),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)), DdlGenerationOptions(SpatialProfile.NATIVE))
        result.statements.joinToString("\n") { it.sql } shouldNotContain "CREATE SPATIAL INDEX"
        result.notesWithCode("E057").map { it.objectName } shouldBe listOf("pk_t", "sx_geo")
    }
})
