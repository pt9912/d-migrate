package dev.dmigrate.driver.connection

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConnectionSecretMaskerTest : FunSpec({

    test("masks authority passwords in native connection URLs") {
        ConnectionSecretMasker.mask("postgresql://admin:secret@localhost:5432/mydb") shouldBe
            "postgresql://admin:***@localhost:5432/mydb"
    }

    test("masks query parameter secrets in jdbc URLs") {
        ConnectionSecretMasker.mask("jdbc:postgresql://host:5432/mydb?user=admin&password=secret&sslmode=require") shouldBe
            "jdbc:postgresql://host:5432/mydb?user=admin&password=***&sslmode=require"
    }

    test("masks query parameter secrets in sqlite dsn forms") {
        ConnectionSecretMasker.mask("sqlite::memory:?password=secret&cache=shared") shouldBe
            "sqlite::memory:?password=***&cache=shared"
    }

    test("masks every configured sensitive query key") {
        ConnectionSecretMasker.sensitiveQueryKeys.forEach { key ->
            ConnectionSecretMasker.mask("jdbc:postgresql://host/db?$key=secret&visible=ok") shouldBe
                "jdbc:postgresql://host/db?$key=***&visible=ok"
        }
    }

    test("leaves non-secret strings unchanged") {
        ConnectionSecretMasker.mask("analytics-prod") shouldBe "analytics-prod"
    }
})
