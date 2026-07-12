package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MysqlJdbcUrlBuilderTest : FunSpec({

    val builder = MysqlJdbcUrlBuilder()

    fun cfg(
        params: Map<String, String> = emptyMap(),
        port: Int? = 3306,
        ssl: SslSettings = SslSettings(),
    ) = ConnectionConfig(
        dialect = DatabaseDialect.MYSQL,
        host = "mysql.example.com",
        port = port,
        database = "shop",
        user = "root",
        password = "rootpw",
        params = params,
        ssl = ssl,
    )

    test("sslParams: neutrales SslMode → Connector/J sslMode-Namen; ALLOW → PREFERRED") {
        builder.sslParams(SslSettings(SslMode.DISABLE)) shouldBe mapOf("sslMode" to "DISABLED")
        builder.sslParams(SslSettings(SslMode.ALLOW)) shouldBe mapOf("sslMode" to "PREFERRED")
        builder.sslParams(SslSettings(SslMode.PREFER)) shouldBe mapOf("sslMode" to "PREFERRED")
        builder.sslParams(SslSettings(SslMode.REQUIRE)) shouldBe mapOf("sslMode" to "REQUIRED")
        builder.sslParams(SslSettings(SslMode.VERIFY_CA)) shouldBe mapOf("sslMode" to "VERIFY_CA")
        builder.sslParams(SslSettings(SslMode.VERIFY_FULL)) shouldBe mapOf("sslMode" to "VERIFY_IDENTITY")
    }

    test("sslParams: rootCert ignoriert (MySQL-Truststore Nicht-Scope); leer → leer") {
        builder.sslParams(SslSettings(SslMode.REQUIRE, "/ca.pem")) shouldBe mapOf("sslMode" to "REQUIRED")
        builder.sslParams(SslSettings()) shouldBe emptyMap()
    }

    test("buildJdbcUrl: ssl → sslMode in URL; ohne ssl kein sslMode (Paritaet)") {
        builder.buildJdbcUrl(cfg(ssl = SslSettings(SslMode.REQUIRE))) shouldContain "sslMode=REQUIRED"
        builder.buildJdbcUrl(cfg()) shouldNotContain "sslMode"
    }

    test("dialect is MYSQL") {
        builder.dialect shouldBe DatabaseDialect.MYSQL
    }

    test("defaultParams contains cursor fetch, batched statements and yearIsDateType=false") {
        builder.defaultParams() shouldBe mapOf(
            "useCursorFetch" to "true",
            "rewriteBatchedStatements" to "true",
            "yearIsDateType" to "false",
        )
    }

    test("buildJdbcUrl injects yearIsDateType=false (Y1: YEAR must not be read as Date)") {
        val url = builder.buildJdbcUrl(cfg())
        url shouldContain "yearIsDateType=false"
    }

    test("buildJdbcUrl: user-provided yearIsDateType overrides the default") {
        val url = builder.buildJdbcUrl(cfg(mapOf("yearIsDateType" to "true")))
        url shouldContain "yearIsDateType=true"
        url shouldNotContain "yearIsDateType=false"
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

    test("buildJdbcUrl does not inject allowPublicKeyRetrieval by default") {
        val url = builder.buildJdbcUrl(cfg())
        url shouldNotContain "allowPublicKeyRetrieval"
    }

    test("buildJdbcUrl preserves explicit allowPublicKeyRetrieval opt-in") {
        val url = builder.buildJdbcUrl(cfg(mapOf("allowPublicKeyRetrieval" to "true")))
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
