package dev.dmigrate.core.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReverseScopeCodecTest : FunSpec({

    // ── PostgreSQL ──────────────────────────────

    test("postgresName produces canonical format") {
        val name = ReverseScopeCodec.postgresName("mydb", "public")
        name shouldBe "__dmigrate_reverse__:postgresql:database=mydb;schema=public"
    }

    test("postgresName round-trips through parseScope") {
        val name = ReverseScopeCodec.postgresName("mydb", "public")
        val scope = ReverseScopeCodec.parseScope(name)
        scope["dialect"] shouldBe "postgresql"
        scope["database"] shouldBe "mydb"
        scope["schema"] shouldBe "public"
    }

    test("postgresName encodes special characters") {
        val name = ReverseScopeCodec.postgresName("my:db", "sch;ema=1")
        val scope = ReverseScopeCodec.parseScope(name)
        scope["database"] shouldBe "my:db"
        scope["schema"] shouldBe "sch;ema=1"
    }

    test("postgresName encodes percent sign") {
        val name = ReverseScopeCodec.postgresName("db%test", "schema")
        val scope = ReverseScopeCodec.parseScope(name)
        scope["database"] shouldBe "db%test"
    }

    // ── MySQL ───────────────────────────────────

    test("mysqlName produces canonical format") {
        val name = ReverseScopeCodec.mysqlName("shopdb")
        name shouldBe "__dmigrate_reverse__:mysql:database=shopdb"
    }

    test("mysqlName round-trips through parseScope") {
        val name = ReverseScopeCodec.mysqlName("shopdb")
        val scope = ReverseScopeCodec.parseScope(name)
        scope["dialect"] shouldBe "mysql"
        scope["database"] shouldBe "shopdb"
    }

    test("mysqlName encodes special characters") {
        val name = ReverseScopeCodec.mysqlName("my;db=test")
        val scope = ReverseScopeCodec.parseScope(name)
        scope["database"] shouldBe "my;db=test"
    }

    // ── SQLite ──────────────────────────────────

    test("sqliteName produces canonical format") {
        val name = ReverseScopeCodec.sqliteName("main")
        name shouldBe "__dmigrate_reverse__:sqlite:schema=main"
    }

    test("sqliteName round-trips through parseScope") {
        val name = ReverseScopeCodec.sqliteName("main")
        val scope = ReverseScopeCodec.parseScope(name)
        scope["dialect"] shouldBe "sqlite"
        scope["schema"] shouldBe "main"
    }

    // ── isReverseGenerated ──────────────────────

    test("isReverseGenerated returns true for valid marker set") {
        val name = ReverseScopeCodec.postgresName("db", "public")
        ReverseScopeCodec.isReverseGenerated(name, ReverseScopeCodec.REVERSE_VERSION) shouldBe true
    }

    test("isReverseGenerated returns false for wrong version") {
        val name = ReverseScopeCodec.postgresName("db", "public")
        ReverseScopeCodec.isReverseGenerated(name, "1.0.0") shouldBe false
    }

    test("isReverseGenerated returns false for normal name") {
        ReverseScopeCodec.isReverseGenerated("My App", "1.0.0") shouldBe false
    }

    test("isReverseGenerated returns false for prefix without valid scope") {
        ReverseScopeCodec.isReverseGenerated("__dmigrate_reverse__:", ReverseScopeCodec.REVERSE_VERSION) shouldBe false
    }

    test("isReverseGenerated returns false for prefix with empty dialect") {
        ReverseScopeCodec.isReverseGenerated("__dmigrate_reverse__::key=val", ReverseScopeCodec.REVERSE_VERSION) shouldBe false
    }

    // ── parseScope errors ───────────────────────

    test("parseScope throws for non-reverse name") {
        shouldThrow<IllegalArgumentException> {
            ReverseScopeCodec.parseScope("My App")
        }
    }

    test("parseScope throws for incomplete scope") {
        shouldThrow<IllegalArgumentException> {
            ReverseScopeCodec.parseScope("__dmigrate_reverse__:postgresql:")
        }
    }

    // ── encodeComponent / decodeComponent ───────

    test("encodeComponent encodes all structural separators") {
        ReverseScopeCodec.encodeComponent("a;b=c:d%e") shouldBe "a%3Bb%3Dc%3Ad%25e"
    }

    test("decodeComponent reverses encodeComponent") {
        val original = "tricky;name=with:colons%and%25percents"
        ReverseScopeCodec.decodeComponent(ReverseScopeCodec.encodeComponent(original)) shouldBe original
    }

    test("encodeComponent leaves plain strings unchanged") {
        ReverseScopeCodec.encodeComponent("simple_name") shouldBe "simple_name"
    }
})
