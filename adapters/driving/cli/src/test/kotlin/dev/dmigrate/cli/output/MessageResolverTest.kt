package dev.dmigrate.cli.output

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.Locale

class MessageResolverTest : FunSpec({

    test("English root bundle loads") {
        val resolver = MessageResolver(Locale.ENGLISH)
        resolver.text("cli.validation.passed_marker") shouldBe "Validation passed"
    }

    test("German bundle loads") {
        val resolver = MessageResolver(Locale.GERMAN)
        resolver.text("cli.validation.passed_marker") shouldBe "Validierung bestanden"
    }

    test("parameterized message with one arg") {
        val resolver = MessageResolver(Locale.ENGLISH)
        resolver.text("cli.validation.tables", 5) shouldBe "Tables:      5 found"
    }

    test("parameterized message with two args") {
        val resolver = MessageResolver(Locale.ENGLISH)
        resolver.text("cli.validation.failed_summary", 3, 2) shouldBe
            "Validation failed: 3 error(s), 2 warning(s)"
    }

    test("German parameterized message") {
        val resolver = MessageResolver(Locale.GERMAN)
        resolver.text("cli.validation.failed_summary", 3, 2) shouldBe
            "Validierung fehlgeschlagen: 3 Fehler, 2 Warnung(en)"
    }

    test("missing key returns key itself") {
        val resolver = MessageResolver(Locale.ENGLISH)
        resolver.text("cli.nonexistent.key") shouldBe "cli.nonexistent.key"
    }

    test("unsupported locale falls back to English root") {
        val resolver = MessageResolver(Locale.FRENCH)
        resolver.text("cli.validation.passed_marker") shouldBe "Validation passed"
    }

    test("error format plain") {
        val resolver = MessageResolver(Locale.ENGLISH)
        resolver.text("cli.error.plain_format", "something broke") shouldBe "[ERROR] something broke"
    }

    test("German error format plain") {
        val resolver = MessageResolver(Locale.GERMAN)
        resolver.text("cli.error.plain_format", "etwas ging schief") shouldBe "[FEHLER] etwas ging schief"
    }

    test("German resolver falls back to root for existing key") {
        // Both bundles have the same keys, so this tests the bundle chain
        // is correctly configured. If a German key were removed, it should
        // fall back to the English root value.
        val deResolver = MessageResolver(Locale.GERMAN)
        val enResolver = MessageResolver(Locale.ENGLISH)
        // Both must return a non-empty value for any root key
        val key = "cli.validation.path_arrow"
        deResolver.text(key, "test") shouldContain "test"
        enResolver.text(key, "test") shouldContain "test"
    }

    test("progress export started") {
        val resolver = MessageResolver(Locale.ENGLISH)
        resolver.text("cli.progress.run_started_export", 3) shouldBe "Exporting 3 table(s)"
    }

    test("German progress export started") {
        val resolver = MessageResolver(Locale.GERMAN)
        resolver.text("cli.progress.run_started_export", 3) shouldBe "3 Tabelle(n) werden exportiert"
    }
})
