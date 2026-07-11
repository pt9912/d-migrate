package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PostgresJdbcUrlBuilderTest : FunSpec({

    val builder = PostgresJdbcUrlBuilder()

    fun cfg(
        params: Map<String, String> = emptyMap(),
        port: Int? = 5432,
        ssl: SslSettings = SslSettings(),
    ) = ConnectionConfig(
        dialect = DatabaseDialect.POSTGRESQL,
        host = "db.example.com",
        port = port,
        database = "mydb",
        user = "admin",
        password = "secret",
        params = params,
        ssl = ssl,
    )

    test("sslParams: alle Modi 1:1 auf pgjdbc sslmode; rootCert → sslrootcert") {
        builder.sslParams(SslSettings(SslMode.DISABLE)) shouldBe mapOf("sslmode" to "disable")
        builder.sslParams(SslSettings(SslMode.ALLOW)) shouldBe mapOf("sslmode" to "allow")
        builder.sslParams(SslSettings(SslMode.PREFER)) shouldBe mapOf("sslmode" to "prefer")
        builder.sslParams(SslSettings(SslMode.REQUIRE)) shouldBe mapOf("sslmode" to "require")
        builder.sslParams(SslSettings(SslMode.VERIFY_CA)) shouldBe mapOf("sslmode" to "verify-ca")
        builder.sslParams(SslSettings(SslMode.VERIFY_FULL, "/ca.pem")) shouldBe
            mapOf("sslmode" to "verify-full", "sslrootcert" to "/ca.pem")
        builder.sslParams(SslSettings()) shouldBe emptyMap()
    }

    test("buildJdbcUrl: ssl → sslmode in URL; ohne ssl kein sslmode (Paritaet)") {
        builder.buildJdbcUrl(cfg(ssl = SslSettings(SslMode.REQUIRE))) shouldContain "sslmode=require"
        builder.buildJdbcUrl(cfg()) shouldNotContain "sslmode"
    }

    test("dialect is POSTGRESQL") {
        builder.dialect shouldBe DatabaseDialect.POSTGRESQL
    }

    test("defaultParams contains ApplicationName + reWriteBatchedInserts") {
        builder.defaultParams() shouldBe mapOf(
            "ApplicationName" to "d-migrate",
            // Import-Durchsatz (Schritt 0): pgjdbc bündelt Batch-INSERTs zu Multi-Row;
            // Pendant zu MySQLs rewriteBatchedStatements (done/import-throughput-copy-path.md).
            "reWriteBatchedInserts" to "true",
        )
    }

    test("buildJdbcUrl injects reWriteBatchedInserts=true by default") {
        builder.buildJdbcUrl(cfg()) shouldContain "reWriteBatchedInserts=true"
    }

    test("baseJdbcUrl with explicit port") {
        builder.baseJdbcUrl(cfg(port = 6543)) shouldBe "jdbc:postgresql://db.example.com:6543/mydb"
    }

    test("baseJdbcUrl falls back to port 5432") {
        builder.baseJdbcUrl(cfg(port = null)) shouldBe "jdbc:postgresql://db.example.com:5432/mydb"
    }

    test("buildJdbcUrl injects ApplicationName=d-migrate by default") {
        val url = builder.buildJdbcUrl(cfg())
        url shouldContain "jdbc:postgresql://db.example.com:5432/mydb"
        url shouldContain "ApplicationName=d-migrate"
    }

    test("buildJdbcUrl: user-provided ApplicationName overrides the default") {
        val url = builder.buildJdbcUrl(cfg(mapOf("ApplicationName" to "my-app")))
        url shouldContain "ApplicationName=my-app"
        url shouldNotContain "ApplicationName=d-migrate"
    }

    test("buildJdbcUrl: extra user params are URL-encoded and merged") {
        val url = builder.buildJdbcUrl(cfg(mapOf("custom" to "a b", "x" to "1&2")))
        // Defaults still present
        url shouldContain "ApplicationName=d-migrate"
        // User params encoded
        url shouldContain "custom=a+b"
        url shouldContain "x=1%262"
    }

    test("buildJdbcUrl rejects mismatched dialect") {
        val mismatched = cfg().copy(dialect = DatabaseDialect.MYSQL)
        shouldThrow<IllegalArgumentException> { builder.buildJdbcUrl(mismatched) }
    }

    test("PostgresDriver exposes PostgresJdbcUrlBuilder") {
        val builder = PostgresDriver().urlBuilder()
        builder shouldBe instanceOfPostgresBuilder()
    }
})

private fun instanceOfPostgresBuilder() = io.kotest.matchers.Matcher<dev.dmigrate.driver.connection.JdbcUrlBuilder?> { value ->
    io.kotest.matchers.MatcherResult(
        value is PostgresJdbcUrlBuilder,
        { "expected a PostgresJdbcUrlBuilder, got ${value?.javaClass?.simpleName}" },
        { "expected NOT a PostgresJdbcUrlBuilder" },
    )
}
