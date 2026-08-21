package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MssqlJdbcUrlBuilderTest : FunSpec({

    val builder = MssqlJdbcUrlBuilder()

    fun cfg(
        params: Map<String, String> = emptyMap(),
        port: Int? = 1433,
        ssl: SslSettings = SslSettings(),
    ) = ConnectionConfig(
        dialect = DatabaseDialect.MSSQL,
        host = "db.example.com",
        port = port,
        database = "shop",
        user = "sa",
        password = "secret",
        params = params,
        ssl = ssl,
    )

    test("dialect is MSSQL") {
        builder.dialect shouldBe DatabaseDialect.MSSQL
    }

    test("defaultParams inject applicationName=d-migrate") {
        builder.defaultParams() shouldBe mapOf("applicationName" to "d-migrate")
    }

    test("buildJdbcUrl assembles semicolon properties") {
        builder.buildJdbcUrl(cfg()) shouldBe
            "jdbc:sqlserver://db.example.com:1433;databaseName=shop;applicationName=d-migrate"
    }

    test("baseJdbcUrl defaults to port 1433") {
        builder.baseJdbcUrl(cfg(port = null)) shouldBe
            "jdbc:sqlserver://db.example.com:1433;databaseName=shop"
    }

    test("user params override defaults") {
        val url = builder.buildJdbcUrl(cfg(params = mapOf("applicationName" to "my-app")))
        url shouldContain "applicationName=my-app"
        url shouldNotContain "applicationName=d-migrate"
    }

    test("property values with special characters are brace-escaped") {
        val url = builder.buildJdbcUrl(cfg(params = mapOf("k" to "a;b", "closing" to "x}y")))
        url shouldContain "k={a;b}"
        url shouldContain "closing={x}}y}"
    }

    test("ssl DISABLE maps to encrypt=false") {
        builder.sslParams(SslSettings(SslMode.DISABLE)) shouldBe mapOf("encrypt" to "false")
    }

    test("ssl ALLOW/PREFER/REQUIRE map to encrypted-but-trusting connection") {
        listOf(SslMode.ALLOW, SslMode.PREFER, SslMode.REQUIRE).forEach { mode ->
            builder.sslParams(SslSettings(mode)) shouldBe
                mapOf("encrypt" to "true", "trustServerCertificate" to "true")
        }
    }

    test("ssl VERIFY_CA/VERIFY_FULL map to verified connection") {
        listOf(SslMode.VERIFY_CA, SslMode.VERIFY_FULL).forEach { mode ->
            builder.sslParams(SslSettings(mode)) shouldBe
                mapOf("encrypt" to "true", "trustServerCertificate" to "false")
        }
    }

    test("absent ssl mode emits no ssl properties") {
        builder.sslParams(SslSettings()) shouldBe emptyMap()
        builder.buildJdbcUrl(cfg()) shouldNotContain "encrypt"
    }

    test("ssl params land in the URL between defaults and user params") {
        val url = builder.buildJdbcUrl(cfg(ssl = SslSettings(SslMode.REQUIRE)))
        url shouldContain ";encrypt=true;trustServerCertificate=true"
    }

    test("user params override ssl-derived properties") {
        val url = builder.buildJdbcUrl(
            cfg(ssl = SslSettings(SslMode.REQUIRE), params = mapOf("encrypt" to "false")),
        )
        url shouldContain "encrypt=false"
        url shouldNotContain "encrypt=true"
    }

    test("rootCert is ignored (truststore out of scope, MySQL precedent)") {
        builder.sslParams(SslSettings(SslMode.REQUIRE, rootCert = "/ca.pem")) shouldBe
            mapOf("encrypt" to "true", "trustServerCertificate" to "true")
    }

    test("mismatched dialect is rejected") {
        shouldThrow<IllegalArgumentException> {
            builder.buildJdbcUrl(cfg().copy(dialect = DatabaseDialect.MYSQL))
        }
    }

    test("driver exposes this builder") {
        MssqlDriver().urlBuilder()::class.simpleName shouldBe "MssqlJdbcUrlBuilder"
    }
})
