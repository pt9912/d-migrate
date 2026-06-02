package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SqliteNamedSequenceMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SqliteDriverExposureTest : FunSpec({

    test("SqliteDriver exposes SQLite component implementations") {
        val driver = SqliteDriver()

        driver.dialect shouldBe DatabaseDialect.SQLITE
        driver.ddlGenerator().shouldBeInstanceOf<SqliteDdlGenerator>()
        driver.dataReader().shouldBeInstanceOf<SqliteDataReader>()
        driver.tableLister().shouldBeInstanceOf<SqliteTableLister>()
        driver.dataWriter().shouldBeInstanceOf<SqliteDataWriter>()
        driver.urlBuilder().shouldBeInstanceOf<SqliteJdbcUrlBuilder>()
        driver.schemaReader().shouldBeInstanceOf<SqliteSchemaReader>()
    }

    // ── PreGenerationValidator wiring ──────────────────────────────

    fun schemaWithPkSeq(): SchemaDefinition = SchemaDefinition(
        name = "T",
        version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(
                        type = NeutralType.BigInteger,
                        default = DefaultValue.SequenceNextVal("order_seq"),
                    ),
                ),
                primaryKey = listOf("id"),
            ),
        ),
        sequences = mapOf("order_seq" to SequenceDefinition()),
    )

    fun options(mode: SqliteNamedSequenceMode?): DdlGenerationOptions {
        val ctx: DdlDialectContext =
            mode?.let { DdlDialectContext.Sqlite(namedSequenceMode = it) } ?: DdlDialectContext.None
        return DdlGenerationOptions(dialectContext = ctx)
    }

    test("SqliteDriver wires SqlitePreGenerationValidator (delegates to helper-table validator)") {
        val driver = SqliteDriver()
        val schema = schemaWithPkSeq()

        val errors = driver.preGenerationValidator()
            .validate(schema, options(SqliteNamedSequenceMode.HELPER_TABLE))

        errors.size shouldBe 1
        errors[0].code shouldBe "E059"
    }

    test("SqlitePreGenerationValidator is no-op in ACTION_REQUIRED mode") {
        SqliteDriver().preGenerationValidator()
            .validate(schemaWithPkSeq(), options(SqliteNamedSequenceMode.ACTION_REQUIRED))
            .shouldBeEmpty()
    }

    test("SqlitePreGenerationValidator defaults to ACTION_REQUIRED when sqliteContext is absent") {
        // SchemaMigrateRenderPipeline / other call sites may build
        // DdlGenerationOptions without ever populating dialectContext.
        // The wrapper must not throw — the absent context implies the
        // backward-compatible ACTION_REQUIRED skip path.
        SqliteDriver().preGenerationValidator()
            .validate(schemaWithPkSeq(), options(mode = null))
            .shouldBeEmpty()
    }
})
