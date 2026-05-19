package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MysqlCheckEnforcementResolverTest : FunSpec({

    test("null server version → unknown, not enforced") {
        val cap = MysqlCheckEnforcementResolver.resolve(null)
        cap.known shouldBe false
        cap.enforced shouldBe false
        cap.rationale shouldBe "mysqlServerVersion konnte nicht gelesen werden"
    }

    test("MySQL ≥ 8.0.16 → known + enforced") {
        val cap = MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(8, 0, 16))
        cap.known shouldBe true
        cap.enforced shouldBe true
        cap.rationale shouldBe "MySQL ≥ 8.0.16"
    }

    test("MySQL > 8.0.16 patch / minor / major are also enforced") {
        MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(8, 0, 36)).enforced shouldBe true
        MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(8, 4, 0)).enforced shouldBe true
        MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(9, 0, 0)).enforced shouldBe true
    }

    test("MySQL < 8.0.16 → known but NOT enforced") {
        val cap = MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(8, 0, 15))
        cap.known shouldBe true
        cap.enforced shouldBe false
        cap.rationale shouldBe "MySQL < 8.0.16 ignoriert CHECK semantisch"
    }

    test("MySQL 5.7.x → known but NOT enforced") {
        val cap = MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(5, 7, 44))
        cap.known shouldBe true
        cap.enforced shouldBe false
    }

    test("MariaDB ≥ 10.2.1 → known + enforced") {
        val cap = MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(10, 2, 1, "MariaDB"))
        cap.known shouldBe true
        cap.enforced shouldBe true
        cap.rationale shouldBe "MariaDB ≥ 10.2.1"
    }

    test("MariaDB 10.11.x is also enforced") {
        MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(10, 11, 6, "MariaDB")).enforced shouldBe true
    }

    test("MariaDB < 10.2.1 → known but NOT enforced") {
        val cap = MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(10, 2, 0, "MariaDB"))
        cap.known shouldBe true
        cap.enforced shouldBe false
        cap.rationale shouldBe "MariaDB < 10.2.1 ignoriert CHECK semantisch"
    }

    test("MariaDB 10.1.x is below the floor") {
        MysqlCheckEnforcementResolver.resolve(
            MysqlServerVersion(10, 1, 30, "MariaDB"),
        ).enforced shouldBe false
    }

    test("MariaDB floor is independent of MySQL floor (10.x is MariaDB, not 'old MySQL')") {
        // A 10.x version string without vendor would compare as MySQL,
        // which is not realistic — 10.x exists only in the MariaDB
        // line. Pin the contract: vendor tag drives the routing.
        val asMariaDb = MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(10, 2, 1, "MariaDB"))
        val asPlainMysql = MysqlCheckEnforcementResolver.resolve(MysqlServerVersion(10, 2, 1))
        asMariaDb.rationale shouldBe "MariaDB ≥ 10.2.1"
        // 10.2.1 without MariaDB vendor reads as plain MySQL ≥ 8.0.16
        // (lexicographic / Comparable order).
        asPlainMysql.rationale shouldBe "MySQL ≥ 8.0.16"
    }
})
