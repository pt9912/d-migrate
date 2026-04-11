package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class PostgresDriverWriterLookupTest : FunSpec({

    beforeTest {
        DatabaseDriverRegistry.clear()
    }

    afterTest {
        DatabaseDriverRegistry.clear()
    }

    test("returns driver writer for registered dialect") {
        DatabaseDriverRegistry.register(PostgresDriver())

        DatabaseDriverRegistry
            .get(DatabaseDialect.POSTGRESQL)
            .dataWriter()
            .shouldBeInstanceOf<PostgresDataWriter>()
    }

    test("clear removes writer lookup together with driver") {
        DatabaseDriverRegistry.register(PostgresDriver())
        DatabaseDriverRegistry.clear()

        val ex = shouldThrow<IllegalArgumentException> {
            DatabaseDriverRegistry.get(DatabaseDialect.POSTGRESQL).dataWriter()
        }
        ex.message.shouldContain("No DatabaseDriver registered")
    }

    test("missing dialect error still lists registered dialects") {
        DatabaseDriverRegistry.register(PostgresDriver())

        val ex = shouldThrow<IllegalArgumentException> {
            DatabaseDriverRegistry.get(DatabaseDialect.MYSQL)
        }

        ex.message.shouldContain("Registered: POSTGRESQL")
    }
})
