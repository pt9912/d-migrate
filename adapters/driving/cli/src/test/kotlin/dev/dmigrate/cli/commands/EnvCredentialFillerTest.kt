package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EnvCredentialFillerTest : FunSpec({

    fun cfg(dialect: DatabaseDialect, password: String?) = ConnectionConfig(
        dialect = dialect, host = "h", port = null, database = "db", user = "u", password = password,
    )

    test("fills a missing password from D_MIGRATE_DB_PASSWORD for an auth dialect") {
        val filler = EnvCredentialFiller(env = { if (it == EnvCredentialFiller.ENV_VAR) "envpw" else null })
        filler.fill(cfg(DatabaseDialect.POSTGRESQL, null)).password shouldBe "envpw"
    }

    test("empty password (user:@host / empty \${VAR}) counts as missing and is filled") {
        val filler = EnvCredentialFiller(env = { "envpw" })
        filler.fill(cfg(DatabaseDialect.MYSQL, "")).password shouldBe "envpw"
    }

    test("an explicit password wins — env is ignored") {
        val filler = EnvCredentialFiller(env = { "envpw" })
        filler.fill(cfg(DatabaseDialect.POSTGRESQL, "explicit")).password shouldBe "explicit"
    }

    test("SQLite is never filled (no-auth dialect gate)") {
        val filler = EnvCredentialFiller(env = { "envpw" })
        filler.fill(cfg(DatabaseDialect.SQLITE, null)).password shouldBe null
    }

    test("no env → config unchanged (no fail-closed; passwordless auth stays possible)") {
        val filler = EnvCredentialFiller(env = { null })
        filler.fill(cfg(DatabaseDialect.POSTGRESQL, null)).password shouldBe null
    }

    test("empty env is treated as unset") {
        val filler = EnvCredentialFiller(env = { "" })
        filler.fill(cfg(DatabaseDialect.POSTGRESQL, null)).password shouldBe null
    }

    test("fillingParser composes parse then fill") {
        val filler = EnvCredentialFiller(env = { "envpw" })
        val parser = filler.fillingParser { _ -> cfg(DatabaseDialect.POSTGRESQL, null) }
        parser("postgresql://u@h/db").password shouldBe "envpw"
    }
})
