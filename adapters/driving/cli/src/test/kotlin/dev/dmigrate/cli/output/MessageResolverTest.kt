package dev.dmigrate.cli.output

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.Locale
import java.util.ResourceBundle

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
        val deResolver = MessageResolver(Locale.GERMAN)
        val enResolver = MessageResolver(Locale.ENGLISH)
        val key = "cli.validation.path_arrow"
        deResolver.text(key, "test") shouldContain "test"
        enResolver.text(key, "test") shouldContain "test"
    }

    test("missing key returns key string itself as fallback") {
        val resolver = MessageResolver(Locale.GERMAN)
        resolver.text("cli.totally.nonexistent.key") shouldBe "cli.totally.nonexistent.key"
    }

    test("missing key with args still returns key") {
        val resolver = MessageResolver(Locale.ENGLISH)
        resolver.text("cli.no.such.key", "arg1", 42) shouldBe "cli.no.such.key"
    }

    test("progress export started") {
        val resolver = MessageResolver(Locale.ENGLISH)
        resolver.text("cli.progress.run_started_export", 3) shouldBe "Exporting 3 table(s)"
    }

    test("German progress export started") {
        val resolver = MessageResolver(Locale.GERMAN)
        resolver.text("cli.progress.run_started_export", 3) shouldBe "3 Tabelle(n) werden exportiert"
    }

    // ────────────────────────────────────────────────────────────────
    // 0.8.0 Phase G R4 (docs/ImpPlan-0.8.0-G.md §2.2 / §6):
    // Direkter Nachweis, dass ein Key, der NUR im Root-Bundle liegt und
    // im DE-Bundle absichtlich fehlt, unter Locale.GERMAN ueber den
    // ResourceBundle-Parent-Chain-Fallback den englischen Wert liefert.
    //
    // Wir pruefen das gegen ein eigenes Test-Bundle-Paar
    // (test-messages-phase-g/phasegmsg[ _de ].properties), damit der Test
    // ein echtes Lueckenszenario modelliert — die produktiven Bundles
    // sind absichtlich schluesselgleich und koennen den Fall so nicht
    // zeigen.
    // ────────────────────────────────────────────────────────────────

    context("Phase G R4: DE-only missing key → Root-Fallback") {
        val baseName = "test-messages-phase-g.phasegmsg"

        test("shared key: German bundle liefert DE-Uebersetzung") {
            val de = ResourceBundle.getBundle(baseName, Locale.GERMAN)
            de.getString("shared.key") shouldBe "Geteilt Deutsch"
        }

        test("root-only key: German bundle faellt auf Root-Bundle zurueck") {
            val de = ResourceBundle.getBundle(baseName, Locale.GERMAN)
            // Der Key existiert nur in phasegmsg.properties, nicht in
            // phasegmsg_de.properties. Java's ResourceBundle-Parent-Chain
            // laedt ihn trotzdem ohne MissingResourceException aus dem
            // Root-Bundle.
            de.getString("root.only.key") shouldBe "Only in root"
        }

        test("root bundle: beide Keys liefern englische Werte") {
            val en = ResourceBundle.getBundle(baseName, Locale.ENGLISH)
            en.getString("shared.key") shouldBe "Shared English"
            en.getString("root.only.key") shouldBe "Only in root"
        }
    }
})
