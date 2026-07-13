package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.data.DataReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * LN-005: `dataReader(fetchSize)` fixiert den Cursor-fetchSize beim Reader-Bau.
 * `fetchSize` ist `protected` — der Test liest den erzeugten Getter per Reflection,
 * um exakt den Produktionspfad (`PostgresDriver.dataReader` → `PostgresDataReader`) zu prüfen.
 */
class PostgresDataReaderFetchSizeTest : FunSpec({

    fun fetchSizeOf(reader: DataReader): Int =
        reader.javaClass.getDeclaredMethod("getFetchSize")
            .apply { isAccessible = true }
            .invoke(reader) as Int

    test("dataReader(fetchSize) applies the override") {
        fetchSizeOf(PostgresDriver().dataReader(2000)) shouldBe 2000
    }

    test("dataReader() / dataReader(null) uses the PostgreSQL dialect default (1000)") {
        fetchSizeOf(PostgresDriver().dataReader()) shouldBe 1000
        fetchSizeOf(PostgresDriver().dataReader(null)) shouldBe 1000
    }
})
