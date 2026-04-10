package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilderRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class SqliteJdbcUrlBuilderTest : FunSpec({

    val builder = SqliteJdbcUrlBuilder()

    fun cfg(params: Map<String, String> = emptyMap(), database: String = "/tmp/test.db") = ConnectionConfig(
        dialect = DatabaseDialect.SQLITE,
        host = null,
        port = null,
        database = database,
        user = null,
        password = null,
        params = params,
    )

    test("dialect is SQLITE") {
        builder.dialect shouldBe DatabaseDialect.SQLITE
    }

    test("defaultParams contains journal_mode=wal and foreign_keys=true") {
        builder.defaultParams() shouldBe mapOf(
            "journal_mode" to "wal",
            "foreign_keys" to "true",
        )
    }

    test("baseJdbcUrl for absolute path") {
        builder.baseJdbcUrl(cfg(database = "/var/lib/test.db")) shouldBe "jdbc:sqlite:/var/lib/test.db"
    }

    test("baseJdbcUrl for :memory:") {
        builder.baseJdbcUrl(cfg(database = ":memory:")) shouldBe "jdbc:sqlite::memory:"
    }

    test("buildJdbcUrl injects WAL and foreign_keys defaults") {
        val url = builder.buildJdbcUrl(cfg())
        url shouldContain "jdbc:sqlite:/tmp/test.db"
        url shouldContain "journal_mode=wal"
        url shouldContain "foreign_keys=true"
    }

    test("buildJdbcUrl uses file::memory: URI form for :memory: with params") {
        val url = builder.buildJdbcUrl(
            cfg(
                params = linkedMapOf(
                    "cache" to "shared",
                    "app name" to "d migrate",
                ),
                database = ":memory:",
            )
        )
        url shouldContain "jdbc:sqlite:file::memory:"
        url shouldContain "cache=shared"
        url shouldContain "app+name=d+migrate"
        url shouldNotContain "jdbc:sqlite::memory:?"
    }

    test("buildJdbcUrl: user can disable foreign_keys") {
        val url = builder.buildJdbcUrl(cfg(mapOf("foreign_keys" to "false")))
        url shouldContain "foreign_keys=false"
        url shouldNotContain "foreign_keys=true"
    }

    test("buildJdbcUrl: user can override journal_mode") {
        val url = builder.buildJdbcUrl(cfg(mapOf("journal_mode" to "delete")))
        url shouldContain "journal_mode=delete"
        url shouldNotContain "journal_mode=wal"
    }

    test("buildJdbcUrl rejects mismatched dialect") {
        val mismatched = cfg().copy(dialect = DatabaseDialect.MYSQL)
        shouldThrow<IllegalArgumentException> { builder.buildJdbcUrl(mismatched) }
    }

    test("companion register adds an instance to the registry") {
        try {
            SqliteJdbcUrlBuilder.register()
            JdbcUrlBuilderRegistry.find(DatabaseDialect.SQLITE)!!::class.simpleName shouldBe "SqliteJdbcUrlBuilder"
        } finally {
            JdbcUrlBuilderRegistry.clear()
        }
    }
})
