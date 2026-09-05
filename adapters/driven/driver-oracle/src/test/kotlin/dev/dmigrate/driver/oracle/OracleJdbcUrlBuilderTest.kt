package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OracleJdbcUrlBuilderTest : FunSpec({

    val builder = OracleJdbcUrlBuilder()

    fun cfg(port: Int? = 1521) = ConnectionConfig(
        dialect = DatabaseDialect.ORACLE,
        host = "db.example.com",
        port = port,
        database = "orclpdb1",
        user = "app",
        password = "secret",
    )

    test("dialect is ORACLE") {
        builder.dialect shouldBe DatabaseDialect.ORACLE
    }

    test("defaultParams is empty") {
        builder.defaultParams() shouldBe emptyMap()
    }

    test("baseJdbcUrl builds the EZConnect thin form with service name") {
        builder.baseJdbcUrl(cfg()) shouldBe "jdbc:oracle:thin:@//db.example.com:1521/orclpdb1"
    }

    test("baseJdbcUrl defaults to listener port 1521") {
        builder.baseJdbcUrl(cfg(port = null)) shouldBe "jdbc:oracle:thin:@//db.example.com:1521/orclpdb1"
    }

    test("mismatched dialect is rejected") {
        shouldThrow<IllegalArgumentException> {
            builder.baseJdbcUrl(cfg().copy(dialect = DatabaseDialect.MSSQL))
        }
    }

    test("driver exposes this builder") {
        OracleDriver().urlBuilder()::class.simpleName shouldBe "OracleJdbcUrlBuilder"
    }
})
