package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.data.DataReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * LN-005: `dataReader(fetchSize)` fixiert den fetchSize beim Reader-Bau. Für SQLite ist der
 * Wert nur ein Hint (kein serverseitiger Cursor), aber die Override-Mechanik gilt identisch.
 */
class SqliteDataReaderFetchSizeTest : FunSpec({

    fun fetchSizeOf(reader: DataReader): Int =
        reader.javaClass.getDeclaredMethod("getFetchSize")
            .apply { isAccessible = true }
            .invoke(reader) as Int

    test("dataReader(fetchSize) applies the override") {
        fetchSizeOf(SqliteDriver().dataReader(256)) shouldBe 256
    }

    test("dataReader() / dataReader(null) uses the SQLite dialect default (1000)") {
        fetchSizeOf(SqliteDriver().dataReader()) shouldBe 1000
        fetchSizeOf(SqliteDriver().dataReader(null)) shouldBe 1000
    }
})
