package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * E.1 Routine-Migration Slice C.1.a: real-world MySQL/MariaDB version
 * strings parse into a structural [MysqlServerVersion] with vendor
 * suffix captured. Comparison ignores the vendor suffix.
 */
class MysqlServerVersionTest : FunSpec({

    test("parses MySQL community + log suffix") {
        MysqlServerVersion.parse("8.0.36-log") shouldBe
            MysqlServerVersion(major = 8, minor = 0, patch = 36, vendor = "log")
    }

    test("parses MySQL 5.7 patch release without suffix") {
        MysqlServerVersion.parse("5.7.44") shouldBe
            MysqlServerVersion(major = 5, minor = 7, patch = 44, vendor = null)
    }

    test("parses MariaDB version with vendor token") {
        MysqlServerVersion.parse("10.11.6-MariaDB") shouldBe
            MysqlServerVersion(major = 10, minor = 11, patch = 6, vendor = "MariaDB")
    }

    test("detects MariaDB vendor token") {
        MysqlServerVersion.parse("10.11.6-MariaDB")!!.isMariaDb shouldBe true
        MysqlServerVersion.parse("8.4.0")!!.isMariaDb shouldBe false
        MysqlServerVersion.parse("8.0.36-log")!!.isMariaDb shouldBe false
    }

    test("parses MySQL 8.4 LTS clean triple") {
        MysqlServerVersion.parse("8.4.0") shouldBe
            MysqlServerVersion(major = 8, minor = 4, patch = 0, vendor = null)
    }

    test("returns null for an unrecognised version string") {
        MysqlServerVersion.parse("unknown") shouldBe null
    }

    test("trims surrounding whitespace before parsing") {
        MysqlServerVersion.parse("  8.0.36  ") shouldBe
            MysqlServerVersion(major = 8, minor = 0, patch = 36, vendor = null)
    }

    test("compareTo ignores vendor suffix") {
        val mysql = MysqlServerVersion.parse("8.0.36-log")!!
        val mariadb = MysqlServerVersion.parse("8.0.36-MariaDB")!!
        mysql.compareTo(mariadb) shouldBe 0
    }

    test("compareTo orders by major then minor then patch") {
        val older = MysqlServerVersion(5, 7, 44)
        val newer = MysqlServerVersion(8, 0, 0)
        (older < newer) shouldBe true
        (newer > older) shouldBe true
    }

    test("returns null for fragments missing the patch component") {
        MysqlServerVersion.parse("8.0") shouldBe null
    }
})
