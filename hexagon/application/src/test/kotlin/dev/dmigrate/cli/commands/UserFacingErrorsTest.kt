package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class UserFacingErrorsTest : FunSpec({

    test("scrubMessage masks credentials in URLs") {
        val errors = UserFacingErrors()

        val rendered = errors.scrubMessage(
            "Connection failed for postgresql://alice:secret@db.example.com/app"
        )

        rendered shouldContain "postgresql://alice:***@db.example.com/app"
        rendered shouldNotContain "secret@"
    }

    test("scrubMessage masks password query parameters in jdbc URLs") {
        val errors = UserFacingErrors()

        val rendered = errors.scrubMessage(
            "JDBC failed for jdbc:postgresql://db.example.com/app?user=alice&password=secret"
        )

        rendered shouldContain "jdbc:postgresql://db.example.com/app?user=alice&password=***"
        rendered shouldNotContain "password=secret"
    }

    test("scrubRef keeps db prefix and masks nested URL") {
        val errors = UserFacingErrors()

        errors.scrubRef("db:mysql://root:secret@localhost/test") shouldBe
            "db:mysql://root:***@localhost/test"
    }

    test("scrubRef keeps db prefix and masks jdbc query-parameter secrets") {
        val errors = UserFacingErrors()

        errors.scrubRef("db:jdbc:postgresql://db.example.com/app?user=alice&password=secret") shouldBe
            "db:jdbc:postgresql://db.example.com/app?user=alice&password=***"
    }

    test("scrubRef leaves aliases unchanged") {
        val errors = UserFacingErrors()

        errors.scrubRef("analytics-prod") shouldBe "analytics-prod"
    }

    test("stderrSink scrubs URLs before delegating") {
        val lines = mutableListOf<String>()
        val errors = UserFacingErrors()

        errors.stderrSink(lines::add)("Warning: retry for mysql://root:secret@localhost/test")

        lines shouldBe listOf("Warning: retry for mysql://root:***@localhost/test")
    }

    test("printError scrubs both message and source") {
        val calls = mutableListOf<Pair<String, String>>()
        val errors = UserFacingErrors()

        errors.printError { message, source -> calls += message to source }(
            "Failed for sqlite://user:secret@host/tmp.db",
            "db:postgresql://alice:secret@db.example.com/app",
        )

        calls shouldBe listOf(
            "Failed for sqlite://user:***@host/tmp.db" to
                "db:postgresql://alice:***@db.example.com/app"
        )
    }

    test("scrubMessage returns fallback for null") {
        val errors = UserFacingErrors()

        errors.scrubMessage(null) shouldBe "unknown error"
    }
})
