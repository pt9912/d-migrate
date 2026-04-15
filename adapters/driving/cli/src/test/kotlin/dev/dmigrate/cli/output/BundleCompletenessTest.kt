package dev.dmigrate.cli.output

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.Locale
import java.util.ResourceBundle

class BundleCompletenessTest : FunSpec({

    val rootBundle = ResourceBundle.getBundle("messages.messages", Locale.ENGLISH)
    val deBundle = ResourceBundle.getBundle("messages.messages", Locale.GERMAN)

    test("root bundle loads with basename messages.messages") {
        rootBundle.getString("cli.validation.passed_marker") shouldBe "Validation passed"
    }

    test("German bundle loads") {
        deBundle.getString("cli.validation.passed_marker") shouldBe "Validierung bestanden"
    }

    test("every root key exists in German bundle") {
        val rootKeys = rootBundle.keys.toList().toSet()
        val deKeys = deBundle.keys.toList().toSet()
        val missingInDe = rootKeys - deKeys
        missingInDe.shouldBeEmpty()
    }

    test("no extra keys in German that are missing from root") {
        val rootKeys = rootBundle.keys.toList().toSet()
        val deKeys = deBundle.keys.toList().toSet()
        val extraInDe = deKeys - rootKeys
        extraInDe.shouldBeEmpty()
    }

    test("no empty values in root bundle") {
        val emptyKeys = rootBundle.keys.toList().filter { rootBundle.getString(it).isBlank() }
        emptyKeys.shouldBeEmpty()
    }

    test("no empty values in German bundle") {
        val emptyKeys = deBundle.keys.toList().filter { deBundle.getString(it).isBlank() }
        emptyKeys.shouldBeEmpty()
    }
})
