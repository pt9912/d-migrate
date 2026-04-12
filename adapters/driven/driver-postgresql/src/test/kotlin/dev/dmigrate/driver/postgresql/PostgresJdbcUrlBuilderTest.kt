package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PostgresJdbcUrlBuilderTest : FunSpec({

    val builder = PostgresJdbcUrlBuilder()

    fun cfg(params: Map<String, String> = emptyMap(), port: Int? = 5432) = ConnectionConfig(
        dialect = DatabaseDialect.POSTGRESQL,
        host = "db.example.com",
        port = port,
        database = "mydb",
        user = "admin",
        password = "secret",
        params = params,
    )

    test("dialect is POSTGRESQL") {
        builder.dialect shouldBe DatabaseDialect.POSTGRESQL
    }

    test("defaultParams contains ApplicationName=d-migrate") {
        builder.defaultParams() shouldBe mapOf("ApplicationName" to "d-migrate")
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
