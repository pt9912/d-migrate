package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SqliteDriverExposureTest : FunSpec({

    test("SqliteDriver exposes SQLite component implementations") {
        val driver = SqliteDriver()

        driver.dialect shouldBe DatabaseDialect.SQLITE
        driver.ddlGenerator().shouldBeInstanceOf<SqliteDdlGenerator>()
        driver.dataReader().shouldBeInstanceOf<SqliteDataReader>()
        driver.tableLister().shouldBeInstanceOf<SqliteTableLister>()
        driver.dataWriter().shouldBeInstanceOf<SqliteDataWriter>()
        driver.urlBuilder().shouldBeInstanceOf<SqliteJdbcUrlBuilder>()
        driver.schemaReader().shouldBeInstanceOf<SqliteSchemaReader>()
    }
})
