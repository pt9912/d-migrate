package dev.dmigrate.driver.postgresql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PostgresIdentifiersTest : FunSpec({

    test("parseQualifiedTableName with schema.table") {
        val qtn = parseQualifiedTableName("myschema.users")
        qtn.schema shouldBe "myschema"
        qtn.table shouldBe "users"
    }

    test("parseQualifiedTableName with table only") {
        val qtn = parseQualifiedTableName("users")
        qtn.schema shouldBe null
        qtn.table shouldBe "users"
    }

    test("quotePostgresIdentifier quotes table name") {
        val quoted = quotePostgresIdentifier("users")
        quoted shouldBe "\"users\""
    }

    test("QualifiedTableName.quotedPath with schema") {
        val qtn = QualifiedTableName("public", "users")
        qtn.quotedPath() shouldBe "\"public\".\"users\""
    }

    test("QualifiedTableName.quotedPath without schema") {
        val qtn = QualifiedTableName(null, "users")
        qtn.quotedPath() shouldBe "\"users\""
    }
})
