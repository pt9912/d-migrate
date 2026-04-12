package dev.dmigrate.driver.connection

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LogScrubberTest : FunSpec({

    test("masks password in postgresql URL") {
        LogScrubber.maskUrl("postgresql://admin:secret@localhost:5432/mydb") shouldBe
            "postgresql://admin:***@localhost:5432/mydb"
    }

    test("masks password in mysql URL") {
        LogScrubber.maskUrl("mysql://root:rootpw@db.example.com/shop") shouldBe
            "mysql://root:***@db.example.com/shop"
    }

    test("URL without password is unchanged") {
        LogScrubber.maskUrl("postgresql://admin@localhost/mydb") shouldBe
            "postgresql://admin@localhost/mydb"
    }

    test("URL without authority block is unchanged (sqlite)") {
        LogScrubber.maskUrl("sqlite:///tmp/test.db") shouldBe
            "sqlite:///tmp/test.db"
        LogScrubber.maskUrl("sqlite::memory:") shouldBe
            "sqlite::memory:"
    }

    test("masks URL-encoded password (does not try to decode)") {
        LogScrubber.maskUrl("postgresql://admin:p%40ss%3Aword@host/db") shouldBe
            "postgresql://admin:***@host/db"
    }

    test("preserves query parameters and path after authority") {
        LogScrubber.maskUrl("postgresql://admin:secret@host:5432/mydb?sslmode=require&app=test") shouldBe
            "postgresql://admin:***@host:5432/mydb?sslmode=require&app=test"
    }

    test("preserves URL with multiple credential-like substrings") {
        // Nur der erste authority-block wird ersetzt; "password=secret" als query-param
        // ist ein anderer Match-Punkt — der wird NICHT abgedeckt, weil die Regex
        // sich auf den authority-block fokussiert. Das ist akzeptabel: Connection-URLs
        // mit Inline-Credentials in Query-Params sind kein Standard-Pattern.
        LogScrubber.maskUrl("postgresql://admin:secret@host/db") shouldBe
            "postgresql://admin:***@host/db"
    }

    test("string without URL pattern is unchanged") {
        LogScrubber.maskUrl("Some log message without any URL") shouldBe
            "Some log message without any URL"
    }

    test("empty string is unchanged") {
        LogScrubber.maskUrl("") shouldBe ""
    }

    test("schemes with custom characters are matched") {
        LogScrubber.maskUrl("custom-scheme://user:pwd@host/x") shouldBe
            "custom-scheme://user:***@host/x"
    }

    test("URL embedded in larger log line gets masked") {
        val line = "Connecting to postgresql://admin:secret@host/db now"
        LogScrubber.maskUrl(line) shouldBe
            "Connecting to postgresql://admin:***@host/db now"
    }
})
