package dev.dmigrate.driver.mysql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Die Abgrenzung, um die es hier allein geht: `DEFAULT_GENERATED` traegt das
 * Wort, ist aber keine berechnete Spalte. Die `EXTRA`-Werte sind gegen
 * MySQL 9.7.2 gemessen (`MysqlDataWriterIntegrationTest` haelt sie live fest).
 */
class MysqlGeneratedColumnsTest : FunSpec({

    test("VIRTUAL and STORED GENERATED are computed columns") {
        MysqlGeneratedColumns.isGenerated("VIRTUAL GENERATED") shouldBe true
        MysqlGeneratedColumns.isGenerated("STORED GENERATED") shouldBe true
    }

    test("DEFAULT_GENERATED is not — a default expression stays writable") {
        MysqlGeneratedColumns.isGenerated("DEFAULT_GENERATED") shouldBe false
        MysqlGeneratedColumns.isGenerated("DEFAULT_GENERATED on update CURRENT_TIMESTAMP") shouldBe false
    }

    test("auto_increment and an empty EXTRA are not computed either") {
        MysqlGeneratedColumns.isGenerated("auto_increment") shouldBe false
        MysqlGeneratedColumns.isGenerated("") shouldBe false
    }

    test("only the stored form reports stored") {
        MysqlGeneratedColumns.isStored("STORED GENERATED") shouldBe true
        MysqlGeneratedColumns.isStored("VIRTUAL GENERATED") shouldBe false
    }

    test("the check does not depend on case") {
        MysqlGeneratedColumns.isGenerated("stored generated") shouldBe true
        MysqlGeneratedColumns.isStored("stored generated") shouldBe true
    }
})
