package dev.dmigrate.driver.mysql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MysqlIdentifiersTest : FunSpec({

    test("parseMysqlQualifiedTableName with schema.table") {
        val qtn = parseMysqlQualifiedTableName("mydb.users")
        qtn.schema shouldBe "mydb"
        qtn.table shouldBe "users"
    }

    test("parseMysqlQualifiedTableName with table only") {
        val qtn = parseMysqlQualifiedTableName("users")
        qtn.schema shouldBe null
        qtn.table shouldBe "users"
    }

    test("quoteMysqlIdentifier quotes table name") {
        val quoted = quoteMysqlIdentifier("users")
        quoted shouldBe "`users`"
    }

    test("normalizeMysqlMetadataIdentifier with lctn=0 keeps case") {
        normalizeMysqlMetadataIdentifier("MyTable", 0) shouldBe "MyTable"
    }

    test("normalizeMysqlMetadataIdentifier with lctn=1 lowercases") {
        normalizeMysqlMetadataIdentifier("MyTable", 1) shouldBe "mytable"
    }

    test("normalizeMysqlMetadataIdentifier with lctn=2 lowercases") {
        normalizeMysqlMetadataIdentifier("MyTable", 2) shouldBe "mytable"
    }

    test("parseMysqlQualifiedTableName with schema.table.extra uses first dot") {
        val qtn = parseMysqlQualifiedTableName("db.schema.table")
        qtn.schema shouldBe "db"
        qtn.table shouldBe "schema.table"
    }
})
