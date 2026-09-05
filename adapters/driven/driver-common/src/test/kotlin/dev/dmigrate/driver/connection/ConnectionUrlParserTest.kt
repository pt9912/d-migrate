package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConnectionUrlParserTest : FunSpec({

    // ─── PostgreSQL ──────────────────────────────────────────────

    test("postgresql full URL with credentials, port and database") {
        val cfg = ConnectionUrlParser.parse("postgresql://admin:secret@db.example.com:5432/mydb")
        cfg.dialect shouldBe DatabaseDialect.POSTGRESQL
        cfg.host shouldBe "db.example.com"
        cfg.port shouldBe 5432
        cfg.database shouldBe "mydb"
        cfg.user shouldBe "admin"
        cfg.password shouldBe "secret"
        cfg.params shouldBe emptyMap()
    }

    test("postgresql alias 'postgres' is normalized") {
        ConnectionUrlParser.parse("postgres://localhost/db").dialect shouldBe DatabaseDialect.POSTGRESQL
    }

    // ─── SSL: sslrootcert-No-Op-Erkennung (Befund 8b, ADR 0038) ──

    test("rootCertIneffective: CA-Pin bei nicht-verifizierendem Modus ist wirkungslos") {
        SslSettings(SslMode.PREFER, "/ca.pem").rootCertIneffective() shouldBe true
        SslSettings(SslMode.REQUIRE, "/ca.pem").rootCertIneffective() shouldBe true
        SslSettings(null, "/ca.pem").rootCertIneffective() shouldBe true // fehlender sslmode → Treiber-Default prefer
    }

    test("rootCertIneffective: verify-ca/verify-full nutzen den CA-Pin — kein No-Op") {
        SslSettings(SslMode.VERIFY_CA, "/ca.pem").rootCertIneffective() shouldBe false
        SslSettings(SslMode.VERIFY_FULL, "/ca.pem").rootCertIneffective() shouldBe false
    }

    test("rootCertIneffective: kein CA-Pin → nie ein No-Op") {
        SslSettings(SslMode.REQUIRE, null).rootCertIneffective() shouldBe false
        SslSettings(null, null).rootCertIneffective() shouldBe false
    }

    test("parse: sslrootcert ohne sslmode wird geparst (warnt, bricht nicht — ADR 0038)") {
        val cfg = ConnectionUrlParser.parse("postgresql://h/db?sslrootcert=/ca.pem")
        cfg.ssl.rootCert shouldBe "/ca.pem"
        cfg.ssl.mode shouldBe null
    }

    test("postgresql alias 'pg' is normalized") {
        ConnectionUrlParser.parse("pg://localhost/db").dialect shouldBe DatabaseDialect.POSTGRESQL
    }

    test("postgresql without port → port is null") {
        val cfg = ConnectionUrlParser.parse("postgresql://localhost/mydb")
        cfg.port shouldBe null
    }

    test("postgresql without credentials") {
        val cfg = ConnectionUrlParser.parse("postgresql://localhost:5432/mydb")
        cfg.user shouldBe null
        cfg.password shouldBe null
    }

    test("postgresql with user but no password") {
        val cfg = ConnectionUrlParser.parse("postgresql://admin@localhost/mydb")
        cfg.user shouldBe "admin"
        cfg.password shouldBe null
    }

    test("postgresql with query parameters") {
        val cfg = ConnectionUrlParser.parse("postgresql://localhost/mydb?sslmode=require&applicationName=d-migrate")
        // LN-026: sslmode wird typisiert nach cfg.ssl extrahiert und aus params entfernt.
        cfg.params shouldBe mapOf("applicationName" to "d-migrate")
        cfg.ssl shouldBe SslSettings(SslMode.REQUIRE)
    }

    test("postgresql with URL-encoded password (special chars)") {
        // Passwort 'p@ss:word' → URL-encoded 'p%40ss%3Aword'
        val cfg = ConnectionUrlParser.parse("postgresql://admin:p%40ss%3Aword@localhost/mydb")
        cfg.password shouldBe "p@ss:word"
    }

    test("postgresql with URL-encoded user") {
        val cfg = ConnectionUrlParser.parse("postgresql://user%2Bone:secret@localhost/mydb")
        cfg.user shouldBe "user+one"
    }

    // ─── MySQL ───────────────────────────────────────────────────

    test("mysql full URL") {
        val cfg = ConnectionUrlParser.parse("mysql://root:rootpw@localhost:3306/shop?ssl=true")
        cfg.dialect shouldBe DatabaseDialect.MYSQL
        cfg.host shouldBe "localhost"
        cfg.port shouldBe 3306
        cfg.database shouldBe "shop"
        cfg.user shouldBe "root"
        cfg.password shouldBe "rootpw"
        // LN-026: Legacy ssl=true → opportunistisch PREFER (nach cfg.ssl extrahiert).
        cfg.params shouldBe emptyMap()
        cfg.ssl shouldBe SslSettings(SslMode.PREFER)
    }

    test("mysql alias 'maria' is normalized") {
        ConnectionUrlParser.parse("maria://localhost/db").dialect shouldBe DatabaseDialect.MYSQL
    }

    test("mysql alias 'mariadb' is normalized") {
        ConnectionUrlParser.parse("mariadb://localhost/db").dialect shouldBe DatabaseDialect.MYSQL
    }

    // ─── SQLite ──────────────────────────────────────────────────

    test("sqlite absolute path with three slashes") {
        val cfg = ConnectionUrlParser.parse("sqlite:///var/lib/d-migrate/test.db")
        cfg.dialect shouldBe DatabaseDialect.SQLITE
        cfg.host shouldBe null
        cfg.port shouldBe null
        cfg.database shouldBe "/var/lib/d-migrate/test.db"
        cfg.user shouldBe null
        cfg.password shouldBe null
    }

    test("sqlite relative path with two slashes") {
        val cfg = ConnectionUrlParser.parse("sqlite://relative/test.db")
        cfg.database shouldBe "relative/test.db"
    }

    test("sqlite in-memory") {
        val cfg = ConnectionUrlParser.parse("sqlite::memory:")
        cfg.dialect shouldBe DatabaseDialect.SQLITE
        cfg.database shouldBe ":memory:"
    }

    test("sqlite alias 'sqlite3'") {
        val cfg = ConnectionUrlParser.parse("sqlite3:///tmp/test.db")
        cfg.dialect shouldBe DatabaseDialect.SQLITE
        cfg.database shouldBe "/tmp/test.db"
    }

    test("sqlite with query parameters") {
        val cfg = ConnectionUrlParser.parse("sqlite:///tmp/test.db?foreign_keys=true&journal_mode=wal")
        cfg.database shouldBe "/tmp/test.db"
        cfg.params shouldBe mapOf("foreign_keys" to "true", "journal_mode" to "wal")
    }

    test("sqlite memory with query parameters") {
        val cfg = ConnectionUrlParser.parse("sqlite::memory:?cache=shared")
        cfg.database shouldBe ":memory:"
        cfg.params shouldBe mapOf("cache" to "shared")
    }

    test("sqlite shorthand without slashes") {
        // sqlite:./local.db — pragmatischer Komfort-Pfad
        val cfg = ConnectionUrlParser.parse("sqlite:./local.db")
        cfg.database shouldBe "./local.db"
    }

    // ─── PoolSettings & defaults ─────────────────────────────────

    test("default PoolSettings are applied") {
        val cfg = ConnectionUrlParser.parse("postgresql://localhost/mydb")
        cfg.pool shouldBe PoolSettings()
    }

    // ─── Error cases ─────────────────────────────────────────────

    test("blank URL throws") {
        shouldThrow<IllegalArgumentException> { ConnectionUrlParser.parse("") }
        shouldThrow<IllegalArgumentException> { ConnectionUrlParser.parse("   ") }
    }

    test("URL without scheme throws") {
        shouldThrow<IllegalArgumentException> { ConnectionUrlParser.parse("localhost/mydb") }
    }

    test("unsupported dialect throws") {
        val ex = shouldThrow<IllegalArgumentException> {
            ConnectionUrlParser.parse("db2://localhost/mydb")
        }
        ex.message!!.contains("db2") shouldBe true
    }

    test("postgresql without database throws") {
        shouldThrow<IllegalArgumentException> {
            ConnectionUrlParser.parse("postgresql://localhost/")
        }
    }

    test("postgresql without host throws") {
        shouldThrow<IllegalArgumentException> {
            ConnectionUrlParser.parse("postgresql:///mydb")
        }
    }

    test("sqlite without path throws") {
        shouldThrow<IllegalArgumentException> {
            ConnectionUrlParser.parse("sqlite:")
        }
    }

    test("error messages mask the password (LogScrubber integration)") {
        val ex = shouldThrow<IllegalArgumentException> {
            ConnectionUrlParser.parse("db2://admin:secret@host/db")
        }
        ex.message!!.contains("secret") shouldBe false
        ex.message!!.contains("***") shouldBe true
    }

    // ─── ConnectionConfig.toString() Maskierung ──────────────────

    test("ConnectionConfig.toString() masks password") {
        val cfg = ConnectionUrlParser.parse("postgresql://admin:secret@localhost/mydb")
        val s = cfg.toString()
        s.contains("secret") shouldBe false
        s.contains("***") shouldBe true
    }

    test("ConnectionConfig.toString() shows null when password is absent") {
        val cfg = ConnectionUrlParser.parse("postgresql://admin@localhost/mydb")
        cfg.toString().contains("password=null") shouldBe true
    }

    // ─── MSSQL ───────────────────────────────────────────────────

    test("mssql full URL parses on the generic path incl. ssl extraction") {
        val cfg = ConnectionUrlParser.parse(
            "mssql://sa:pw@db.example.com:1433/shop" +
                "?encrypt=true&trustServerCertificate=true&applicationName=x",
        )
        cfg.dialect shouldBe DatabaseDialect.MSSQL
        cfg.host shouldBe "db.example.com"
        cfg.port shouldBe 1433
        cfg.database shouldBe "shop"
        cfg.user shouldBe "sa"
        cfg.ssl.mode shouldBe SslMode.REQUIRE
        cfg.params shouldBe mapOf("applicationName" to "x")
    }

    test("sqlserver alias is normalized to MSSQL") {
        ConnectionUrlParser.parse("sqlserver://h/db").dialect shouldBe DatabaseDialect.MSSQL
    }
})
