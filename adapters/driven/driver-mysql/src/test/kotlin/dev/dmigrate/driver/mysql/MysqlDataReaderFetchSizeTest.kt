package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.data.DataReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** LN-005: `dataReader(fetchSize)` fixiert den Cursor-fetchSize beim Reader-Bau (s. Postgres-Analogon). */
class MysqlDataReaderFetchSizeTest : FunSpec({

    fun fetchSizeOf(reader: DataReader): Int =
        reader.javaClass.getDeclaredMethod("getFetchSize")
            .apply { isAccessible = true }
            .invoke(reader) as Int

    test("dataReader(fetchSize) applies the override") {
        fetchSizeOf(MysqlDriver().dataReader(4096)) shouldBe 4096
    }

    test("dataReader() / dataReader(null) uses the MySQL dialect default (1000)") {
        fetchSizeOf(MysqlDriver().dataReader()) shouldBe 1000
        fetchSizeOf(MysqlDriver().dataReader(null)) shouldBe 1000
    }
})
