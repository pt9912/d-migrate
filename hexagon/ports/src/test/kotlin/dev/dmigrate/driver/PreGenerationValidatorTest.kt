package dev.dmigrate.driver

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Pins the [PreGenerationValidator] port surface so the default no-op
 * path and the [DatabaseDriver.preGenerationValidator] default method
 * stay covered.
 */
class PreGenerationValidatorTest : FunSpec({

    val nonEmptySchema = SchemaDefinition(
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
    )

    test("PreGenerationValidator.NoOp returns an empty list for any schema + options") {
        PreGenerationValidator.NoOp
            .validate(nonEmptySchema, DdlGenerationOptions())
            .shouldBeEmpty()
        PreGenerationValidator.NoOp
            .validate(SchemaDefinition(name = "T", version = "1"), DdlGenerationOptions())
            .shouldBeEmpty()
    }

    test("DatabaseDriver.preGenerationValidator defaults to PreGenerationValidator.NoOp") {
        // Driver that overrides nothing should pick up the interface default.
        val driver: DatabaseDriver = object : DatabaseDriver {
            override val dialect = DatabaseDialect.POSTGRESQL
            override fun ddlGenerator(): DdlGenerator = error("not needed")
            override fun dataReader(): DataReader = error("not needed")
            override fun tableLister(): TableLister = error("not needed")
            override fun dataWriter(): DataWriter = error("not needed")
            override fun urlBuilder(): JdbcUrlBuilder = error("not needed")
            override fun schemaReader(): SchemaReader = error("not needed")
        }

        driver.preGenerationValidator() shouldBe PreGenerationValidator.NoOp
    }

    test("DatabaseDriver.typeCanonicalizer defaults to the identity projection") {
        val driver: DatabaseDriver = object : DatabaseDriver {
            override val dialect = DatabaseDialect.POSTGRESQL
            override fun ddlGenerator(): DdlGenerator = error("not needed")
            override fun dataReader(): DataReader = error("not needed")
            override fun tableLister(): TableLister = error("not needed")
            override fun dataWriter(): DataWriter = error("not needed")
            override fun urlBuilder(): JdbcUrlBuilder = error("not needed")
            override fun schemaReader(): SchemaReader = error("not needed")
        }

        driver.typeCanonicalizer() shouldBe NeutralTypeCanonicalizer.IDENTITY
        driver.typeCanonicalizer().canonicalize(NeutralType.SmallInt) shouldBe NeutralType.SmallInt
    }
})
