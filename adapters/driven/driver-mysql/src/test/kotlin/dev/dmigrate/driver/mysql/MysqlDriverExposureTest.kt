package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

class MysqlDriverExposureTest : FunSpec({

    test("MysqlDriver exposes MySQL component implementations") {
        val driver = MysqlDriver()

        driver.dialect shouldBe DatabaseDialect.MYSQL
        driver.ddlGenerator().shouldBeInstanceOf<MysqlDdlGenerator>()
        driver.dataReader().shouldBeInstanceOf<MysqlDataReader>()
        driver.tableLister().shouldBeInstanceOf<MysqlTableLister>()
        driver.dataWriter().shouldBeInstanceOf<MysqlDataWriter>()
        driver.urlBuilder().shouldBeInstanceOf<MysqlJdbcUrlBuilder>()

        driver.schemaReader().shouldBeInstanceOf<MysqlSchemaReader>()
    }

    test("identifier helpers parse quote and normalize metadata names") {
        val qualified = parseMysqlQualifiedTableName("Odd Schema.Table`Name")
        qualified.schema shouldBe "Odd Schema"
        qualified.table shouldBe "Table`Name"
        qualified.quotedPath() shouldBe "`Odd Schema`.`Table``Name`"
        qualified.schemaOrCurrent(fakeMysqlConnection(catalog = "ignored", database = null, lowerCaseNames = 0)) shouldBe "Odd Schema"
        qualified.metadataSchema(fakeMysqlConnection(catalog = "ignored", database = null, lowerCaseNames = 1), 1) shouldBe "odd schema"
        qualified.metadataTable(1) shouldBe "table`name"

        val unqualified = parseMysqlQualifiedTableName("Users")
        unqualified.schema.shouldBeNull()
        unqualified.table shouldBe "Users"
        unqualified.schemaOrCurrent(fakeMysqlConnection(catalog = null, database = "AppDb", lowerCaseNames = 0)) shouldBe "AppDb"

        quoteMysqlIdentifier("Table`Name") shouldBe "`Table``Name`"
        normalizeMysqlMetadataIdentifier("MixedCase", 0) shouldBe "MixedCase"
        normalizeMysqlMetadataIdentifier("MixedCase", 1) shouldBe "mixedcase"
    }

    test("currentDatabase prefers catalog and otherwise queries DATABASE()") {
        currentDatabase(fakeMysqlConnection(catalog = "CatalogDb", database = null, lowerCaseNames = 0)) shouldBe "CatalogDb"
        currentDatabase(fakeMysqlConnection(catalog = null, database = "QueryDb", lowerCaseNames = 0)) shouldBe "QueryDb"
    }

    test("lowerCaseTableNames reads @@lower_case_table_names and query helpers fail clearly on empty results") {
        lowerCaseTableNames(fakeMysqlConnection(catalog = null, database = "QueryDb", lowerCaseNames = 1)) shouldBe 1

        shouldThrow<IllegalStateException> {
            currentDatabase(fakeMysqlConnection(catalog = null, database = null, lowerCaseNames = 0, databaseHasRow = false))
        }.message shouldBe "SELECT DATABASE() returned no row"

        shouldThrow<IllegalStateException> {
            lowerCaseTableNames(fakeMysqlConnection(catalog = null, database = "QueryDb", lowerCaseNames = 0, lowerCaseHasRow = false))
        }.message shouldBe "SELECT @@lower_case_table_names returned no row"
    }
})

private fun fakeMysqlConnection(
    catalog: String?,
    database: String?,
    lowerCaseNames: Int,
    databaseHasRow: Boolean = true,
    lowerCaseHasRow: Boolean = true,
): Connection {
    return Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, _, ->
        when (method.name) {
            "getCatalog" -> catalog
            "createStatement" -> fakeMysqlStatement(database, lowerCaseNames, databaseHasRow, lowerCaseHasRow)
            "close" -> null
            "isClosed" -> false
            "unwrap" -> null
            "isWrapperFor" -> false
            "toString" -> "FakeMysqlConnection"
            "hashCode" -> 0
            "equals" -> false
            else -> error("Unexpected Connection method in test: ${method.name}")
        }
    } as Connection
}

private fun fakeMysqlStatement(
    database: String?,
    lowerCaseNames: Int,
    databaseHasRow: Boolean,
    lowerCaseHasRow: Boolean,
): Statement {
    return Proxy.newProxyInstance(
        Statement::class.java.classLoader,
        arrayOf(Statement::class.java),
    ) { _, method, args ->
        when (method.name) {
            "executeQuery" -> when (args?.get(0)) {
                "SELECT DATABASE()" -> fakeMysqlResultSet(databaseHasRow, stringValue = database, intValue = null)
                "SELECT @@lower_case_table_names" -> fakeMysqlResultSet(lowerCaseHasRow, stringValue = null, intValue = lowerCaseNames)
                else -> error("Unexpected SQL in test: ${args?.get(0)}")
            }
            "close" -> null
            "isClosed" -> false
            "unwrap" -> null
            "isWrapperFor" -> false
            "toString" -> "FakeMysqlStatement"
            "hashCode" -> 0
            "equals" -> false
            else -> error("Unexpected Statement method in test: ${method.name}")
        }
    } as Statement
}

private fun fakeMysqlResultSet(hasRow: Boolean, stringValue: String?, intValue: Int?): ResultSet {
    var nextCalls = 0
    return Proxy.newProxyInstance(
        ResultSet::class.java.classLoader,
        arrayOf(ResultSet::class.java),
    ) { _, method, args ->
        when (method.name) {
            "next" -> {
                nextCalls += 1
                hasRow && nextCalls == 1
            }
            "getString" -> {
                require(args?.get(0) == 1) { "unexpected column index: ${args?.get(0)}" }
                stringValue
            }
            "getInt" -> {
                require(args?.get(0) == 1) { "unexpected column index: ${args?.get(0)}" }
                intValue ?: 0
            }
            "close" -> null
            "isClosed" -> false
            "unwrap" -> null
            "isWrapperFor" -> false
            "toString" -> "FakeMysqlResultSet"
            "hashCode" -> 0
            "equals" -> false
            else -> error("Unexpected ResultSet method in test: ${method.name}")
        }
    } as ResultSet
}
