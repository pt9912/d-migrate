package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MysqlJdbcUrlBuilderTest : FunSpec({

    val builder = MysqlJdbcUrlBuilder()

    fun cfg(params: Map<String, String> = emptyMap(), port: Int? = 3306) = ConnectionConfig(
        dialect = DatabaseDialect.MYSQL,
        host = "mysql.example.com",
        port = port,
        database = "shop",
        user = "root",
        password = "rootpw",
        params = params,
    )

    test("dialect is MYSQL") {
        builder.dialect shouldBe DatabaseDialect.MYSQL
    }

    test("defaultParams contains cursor fetch, batched statements and allowPublicKeyRetrieval") {
        builder.defaultParams() shouldBe mapOf(
            "useCursorFetch" to "true",
            "rewriteBatchedStatements" to "true",
            "allowPublicKeyRetrieval" to "true",
        )
    }

    test("baseJdbcUrl with explicit port") {
        builder.baseJdbcUrl(cfg(port = 33060)) shouldBe "jdbc:mysql://mysql.example.com:33060/shop"
    }

    test("baseJdbcUrl falls back to port 3306") {
        builder.baseJdbcUrl(cfg(port = null)) shouldBe "jdbc:mysql://mysql.example.com:3306/shop"
    }

    test("buildJdbcUrl injects useCursorFetch=true and rewriteBatchedStatements=true") {
        val url = builder.buildJdbcUrl(cfg())
        url shouldContain "jdbc:mysql://mysql.example.com:3306/shop"
        url shouldContain "useCursorFetch=true"
        url shouldContain "rewriteBatchedStatements=true"
    }

    test("buildJdbcUrl injects allowPublicKeyRetrieval") {
        val url = builder.buildJdbcUrl(cfg())
        url shouldContain "allowPublicKeyRetrieval=true"
    }

    test("buildJdbcUrl: user-provided useCursorFetch overrides the default") {
        val url = builder.buildJdbcUrl(cfg(mapOf("useCursorFetch" to "false")))
        url shouldContain "useCursorFetch=false"
        url shouldNotContain "useCursorFetch=true"
    }

    test("buildJdbcUrl rejects mismatched dialect") {
        val mismatched = cfg().copy(dialect = DatabaseDialect.POSTGRESQL)
        shouldThrow<IllegalArgumentException> { builder.buildJdbcUrl(mismatched) }
    }

    test("MysqlDriver exposes MysqlJdbcUrlBuilder") {
        val builder = MysqlDriver().urlBuilder()
        builder::class.simpleName shouldBe "MysqlJdbcUrlBuilder"
    }
})
