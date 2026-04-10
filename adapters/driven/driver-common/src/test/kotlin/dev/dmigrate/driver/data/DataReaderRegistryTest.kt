package dev.dmigrate.driver.data

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DataReaderRegistryTest : FunSpec({

    afterEach {
        DataReaderRegistry.clear()
    }

    test("register + lookup returns the same instance") {
        val reader = StubReader(DatabaseDialect.SQLITE)
        DataReaderRegistry.registerDataReader(reader)
        DataReaderRegistry.dataReader(DatabaseDialect.SQLITE) shouldBe reader
    }

    test("re-register overwrites the previous entry") {
        DataReaderRegistry.registerDataReader(StubReader(DatabaseDialect.SQLITE))
        val second = StubReader(DatabaseDialect.SQLITE)
        DataReaderRegistry.registerDataReader(second)
        DataReaderRegistry.dataReader(DatabaseDialect.SQLITE) shouldBe second
    }

    test("missing reader throws with helpful message") {
        val ex = shouldThrow<IllegalArgumentException> {
            DataReaderRegistry.dataReader(DatabaseDialect.POSTGRESQL)
        }
        ex.message!!.contains("d-migrate-driver-postgresql") shouldBe true
    }

    test("missing lister throws") {
        shouldThrow<IllegalArgumentException> {
            DataReaderRegistry.tableLister(DatabaseDialect.MYSQL)
        }
    }

    test("register + lookup TableLister") {
        val lister = StubLister(DatabaseDialect.MYSQL)
        DataReaderRegistry.registerTableLister(lister)
        DataReaderRegistry.tableLister(DatabaseDialect.MYSQL) shouldBe lister
    }

    test("readers and listers are independently keyed") {
        DataReaderRegistry.registerDataReader(StubReader(DatabaseDialect.SQLITE))
        // No TableLister registered for SQLITE
        shouldThrow<IllegalArgumentException> {
            DataReaderRegistry.tableLister(DatabaseDialect.SQLITE)
        }
    }
})

private class StubReader(override val dialect: DatabaseDialect) : DataReader {
    override fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
    ): ChunkSequence = error("not used in this test")
}

private class StubLister(override val dialect: DatabaseDialect) : TableLister {
    override fun listTables(pool: ConnectionPool): List<String> = emptyList()
}
