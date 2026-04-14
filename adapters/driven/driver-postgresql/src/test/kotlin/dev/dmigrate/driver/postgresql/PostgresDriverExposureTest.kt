package dev.dmigrate.driver.postgresql

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

class PostgresDriverExposureTest : FunSpec({

    test("PostgresDriver exposes PostgreSQL component implementations") {
        val driver = PostgresDriver()

        driver.dialect shouldBe DatabaseDialect.POSTGRESQL

        val ddlGenerator = driver.ddlGenerator().shouldBeInstanceOf<PostgresDdlGenerator>()
        ddlGenerator.dialect shouldBe DatabaseDialect.POSTGRESQL

        val dataReader = driver.dataReader().shouldBeInstanceOf<PostgresDataReader>()
        dataReader.dialect shouldBe DatabaseDialect.POSTGRESQL

        val tableLister = driver.tableLister().shouldBeInstanceOf<PostgresTableLister>()
        tableLister.dialect shouldBe DatabaseDialect.POSTGRESQL

        val dataWriter = driver.dataWriter().shouldBeInstanceOf<PostgresDataWriter>()
        dataWriter.dialect shouldBe DatabaseDialect.POSTGRESQL

        val urlBuilder = driver.urlBuilder().shouldBeInstanceOf<PostgresJdbcUrlBuilder>()
        urlBuilder.dialect shouldBe DatabaseDialect.POSTGRESQL

        shouldThrow<UnsupportedOperationException> { driver.schemaReader() }
    }

    test("identifier helpers parse and quote PostgreSQL table names") {
        val qualified = parseQualifiedTableName("Odd Schema.plain_seq")
        qualified.schema shouldBe "Odd Schema"
        qualified.table shouldBe "plain_seq"
        qualified.quotedPath() shouldBe "\"Odd Schema\".\"plain_seq\""
        qualified.pgCatalogName() shouldBe "\"Odd Schema\".\"plain_seq\""

        val unqualified = parseQualifiedTableName("Quote \"Table\"")
        unqualified.schema.shouldBeNull()
        unqualified.table shouldBe "Quote \"Table\""
        unqualified.quotedPath() shouldBe "\"Quote \"\"Table\"\"\""
        unqualified.pgCatalogName() shouldBe "\"Quote \"\"Table\"\"\""

        quotePostgresIdentifier("Quote \"Table\"") shouldBe "\"Quote \"\"Table\"\"\""
    }

    test("schemaOrCurrent uses explicit schema or current_schema fallback") {
        QualifiedTableName("public", "users").schemaOrCurrent(fakeCurrentSchemaConnection("ignored")) shouldBe "public"
        QualifiedTableName(schema = null, table = "users").schemaOrCurrent(fakeCurrentSchemaConnection("Odd Schema")) shouldBe "Odd Schema"
    }

    test("PostgresDataReader keeps PostgreSQL-specific reader settings") {
        val reader = PostgresDataReader()
        val quoteIdentifier = reader.javaClass.getDeclaredMethod("quoteIdentifier", String::class.java)
        quoteIdentifier.isAccessible = true
        quoteIdentifier.invoke(reader, "Quote \"Name\"") shouldBe "\"Quote \"\"Name\"\"\""

        val fetchSize = reader.javaClass.getDeclaredMethod("getFetchSize")
        fetchSize.isAccessible = true
        fetchSize.invoke(reader) shouldBe 1_000

        val needsAutoCommitFalse = reader.javaClass.getDeclaredMethod("getNeedsAutoCommitFalse")
        needsAutoCommitFalse.isAccessible = true
        needsAutoCommitFalse.invoke(reader) shouldBe true
    }

    test("currentSchema fails clearly when PostgreSQL returns no row") {
        val ex = shouldThrow<IllegalStateException> {
            currentSchema(fakeCurrentSchemaConnection("ignored", hasRow = false))
        }

        ex.message shouldBe "SELECT current_schema() returned no row"
    }
})

private fun fakeCurrentSchemaConnection(schema: String, hasRow: Boolean = true): Connection {
    val resultSet = fakeCurrentSchemaResultSet(schema, hasRow)
    val statement = Proxy.newProxyInstance(
        Statement::class.java.classLoader,
        arrayOf(Statement::class.java),
    ) { _, method, args ->
        when (method.name) {
            "executeQuery" -> {
                require(args?.get(0) == "SELECT current_schema()") {
                    "unexpected SQL: ${args?.get(0)}"
                }
                resultSet
            }
            "close" -> null
            "isClosed" -> false
            "unwrap" -> null
            "isWrapperFor" -> false
            "toString" -> "FakeStatement"
            "hashCode" -> 0
            "equals" -> false
            else -> error("Unexpected Statement method in test: ${method.name}")
        }
    } as Statement

    return Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, _, ->
        when (method.name) {
            "createStatement" -> statement
            "close" -> null
            "isClosed" -> false
            "unwrap" -> null
            "isWrapperFor" -> false
            "toString" -> "FakeConnection"
            "hashCode" -> 0
            "equals" -> false
            else -> error("Unexpected Connection method in test: ${method.name}")
        }
    } as Connection
}

private fun fakeCurrentSchemaResultSet(schema: String, hasRow: Boolean): ResultSet {
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
                schema
            }
            "close" -> null
            "isClosed" -> false
            "unwrap" -> null
            "isWrapperFor" -> false
            "toString" -> "FakeResultSet"
            "hashCode" -> 0
            "equals" -> false
            else -> error("Unexpected ResultSet method in test: ${method.name}")
        }
    } as ResultSet
}
