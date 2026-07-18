package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class SslSettingsParserTest : FunSpec({

    fun pg(params: Map<String, String>) =
        SslSettingsParser.extract(DatabaseDialect.POSTGRESQL, params, "postgresql://h/db")

    fun mysql(params: Map<String, String>) =
        SslSettingsParser.extract(DatabaseDialect.MYSQL, params, "mysql://h/db")

    test("PG: alle sslmode-Werte auf neutrale Modi gemappt") {
        pg(mapOf("sslmode" to "disable")).ssl.mode shouldBe SslMode.DISABLE
        pg(mapOf("sslmode" to "allow")).ssl.mode shouldBe SslMode.ALLOW
        pg(mapOf("sslmode" to "prefer")).ssl.mode shouldBe SslMode.PREFER
        pg(mapOf("sslmode" to "require")).ssl.mode shouldBe SslMode.REQUIRE
        pg(mapOf("sslmode" to "verify-ca")).ssl.mode shouldBe SslMode.VERIFY_CA
        pg(mapOf("sslmode" to "verify-full")).ssl.mode shouldBe SslMode.VERIFY_FULL
    }

    test("PG: sslrootcert → rootCert; ssl-Keys aus remainingParams entfernt, Rest bleibt") {
        val e = pg(mapOf("sslmode" to "require", "sslrootcert" to "/ca.pem", "applicationName" to "x"))
        e.ssl shouldBe SslSettings(SslMode.REQUIRE, "/ca.pem")
        e.remainingParams shouldBe mapOf("applicationName" to "x")
    }

    test("PG: Key case-insensitive, Wert case-insensitive") {
        pg(mapOf("SSLMODE" to "REQUIRE")).ssl.mode shouldBe SslMode.REQUIRE
    }

    test("PG: case-abweichendes Duplikat wird vollständig konsumiert (Befund 9, CWE-178)") {
        // `sslmode=verify-full` (erster Treffer) gewinnt; das case-abweichende
        // Duplikat `sslMode=disable` darf NICHT in remainingParams überleben,
        // sonst überschriebe es den validierten Modus in der emittierten URL.
        val e = pg(mapOf("sslmode" to "verify-full", "sslMode" to "disable"))
        e.ssl.mode shouldBe SslMode.VERIFY_FULL
        e.remainingParams shouldBe emptyMap()
    }

    test("MySQL: case-abweichendes sslMode-Duplikat wird vollständig konsumiert (Befund 9)") {
        val e = mysql(mapOf("sslMode" to "REQUIRED", "SSLMODE" to "DISABLED"))
        e.ssl.mode shouldBe SslMode.REQUIRE
        e.remainingParams shouldBe emptyMap()
    }

    test("PG: ungültiger sslmode → Fehler mit gescrubbter URL") {
        val ex = shouldThrow<IllegalArgumentException> {
            SslSettingsParser.extract(
                DatabaseDialect.POSTGRESQL,
                mapOf("sslmode" to "bogus"),
                "postgresql://user:secret@h/db",
            )
        }
        ex.message!! shouldContain "sslmode"
        ex.message!! shouldContain "bogus"
        ex.message!! shouldNotContain "secret"
    }

    test("MySQL: sslMode-Namen auf neutrale Modi gemappt") {
        mysql(mapOf("sslMode" to "DISABLED")).ssl.mode shouldBe SslMode.DISABLE
        mysql(mapOf("sslMode" to "PREFERRED")).ssl.mode shouldBe SslMode.PREFER
        mysql(mapOf("sslMode" to "REQUIRED")).ssl.mode shouldBe SslMode.REQUIRE
        mysql(mapOf("sslMode" to "VERIFY_CA")).ssl.mode shouldBe SslMode.VERIFY_CA
        mysql(mapOf("sslMode" to "VERIFY_IDENTITY")).ssl.mode shouldBe SslMode.VERIFY_FULL
    }

    test("MySQL: Legacy ssl=true → PREFER (opportunistisch), ssl=false → DISABLE") {
        mysql(mapOf("ssl" to "true")).ssl.mode shouldBe SslMode.PREFER
        mysql(mapOf("ssl" to "false")).ssl.mode shouldBe SslMode.DISABLE
    }

    test("MySQL: sslMode gewinnt über ssl; beide konsumiert") {
        val e = mysql(mapOf("sslMode" to "REQUIRED", "ssl" to "true", "useUnicode" to "true"))
        e.ssl.mode shouldBe SslMode.REQUIRE
        e.remainingParams shouldBe mapOf("useUnicode" to "true")
    }

    test("MySQL: ungültiger sslMode → Fehler") {
        shouldThrow<IllegalArgumentException> { mysql(mapOf("sslMode" to "NOPE")) }
    }

    test("SQLite: SSL-Keys unberührt, SslSettings leer") {
        val e = SslSettingsParser.extract(
            DatabaseDialect.SQLITE,
            mapOf("sslmode" to "require", "journal_mode" to "wal"),
            "sqlite:///x.db",
        )
        e.ssl shouldBe SslSettings()
        e.remainingParams shouldBe mapOf("sslmode" to "require", "journal_mode" to "wal")
    }

    test("kein SSL-Param → leeres SslSettings, Params unverändert") {
        val e = pg(mapOf("applicationName" to "x"))
        e.ssl shouldBe SslSettings()
        e.remainingParams shouldBe mapOf("applicationName" to "x")
    }
})
