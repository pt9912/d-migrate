package dev.dmigrate.integration

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.migration.MigrationIdentity
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.MigrationVersionSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RenderHelpersTest : FunSpec({

    val identity = MigrationIdentity(
        tool = MigrationTool.FLYWAY,
        dialect = DatabaseDialect.POSTGRESQL,
        version = "1.0",
        versionSource = MigrationVersionSource.CLI,
        slug = "my_app",
    )

    // ── File name generation ──────────────────────

    test("flywayFileName with V prefix") {
        RenderHelpers.flywayFileName(identity, "V") shouldBe "V1.0__my_app.sql"
    }

    test("flywayFileName with U prefix") {
        RenderHelpers.flywayFileName(identity, "U") shouldBe "U1.0__my_app.sql"
    }

    // ── XML escaping ──────────────────────────────

    test("escapeXml preserves plain SQL") {
        RenderHelpers.escapeXml("CREATE TABLE t (id INT);") shouldBe
            "CREATE TABLE t (id INT);"
    }

    test("escapeXml escapes ampersand") {
        RenderHelpers.escapeXml("a & b") shouldBe "a &amp; b"
    }

    test("escapeXml escapes angle brackets") {
        RenderHelpers.escapeXml("a < b > c") shouldBe "a &lt; b &gt; c"
    }

    test("escapeXml handles combined special chars") {
        RenderHelpers.escapeXml("x < y & z > w") shouldBe "x &lt; y &amp; z &gt; w"
    }

    // ── Python escaping ───────────────────────────

    test("escapePython preserves plain SQL") {
        RenderHelpers.escapePython("CREATE TABLE t (id INT);") shouldBe
            "CREATE TABLE t (id INT);"
    }

    test("escapePython escapes backslashes") {
        RenderHelpers.escapePython("path\\to\\file") shouldBe "path\\\\to\\\\file"
    }

    test("escapePython escapes triple quotes") {
        RenderHelpers.escapePython("""a""" + "\"\"\"" + """b""") shouldBe
            "a\\\"\\\"\\\"b"
    }

    // ── JavaScript escaping ───────────────────────

    test("escapeJavaScript preserves plain SQL") {
        RenderHelpers.escapeJavaScript("CREATE TABLE t (id INT);") shouldBe
            "CREATE TABLE t (id INT);"
    }

    test("escapeJavaScript escapes backticks") {
        RenderHelpers.escapeJavaScript("a`b") shouldBe "a\\`b"
    }

    test("escapeJavaScript escapes backslashes") {
        RenderHelpers.escapeJavaScript("a\\b") shouldBe "a\\\\b"
    }

    test("escapeJavaScript escapes template literals") {
        RenderHelpers.escapeJavaScript("\${var}") shouldBe "\\\${var}"
    }
})
