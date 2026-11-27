package dev.dmigrate.server.persistence.jdbc.migration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.InputStream
import java.net.URL

/**
 * Issue #84: Im Native-Image findet Flyways Scanner keine Migration (`unsupported protocol: resource`).
 * Der Provider liest die Namen aus dem Index und muss auf der JVM dieselben Migrationen liefern wie der Scanner.
 */
class IndexedMigrationResourcesTest : FunSpec({

    val provider = IndexedMigrationResources("db/migration", JdbcMigrationRunner::class.java.classLoader)

    test("der Index nennt jede .sql-Datei von db/migration, sortiert") {
        provider.getResources("V", arrayOf(".sql")).map { it.filename }.shouldContainExactly(
            "V1__server_state_initial.sql",
            "V2__schema_artifact_stores.sql",
            "V3__diff_profile_indexes.sql",
        )
    }

    test("eine Migration liest sich als SQL und traegt ihren Pfad relativ zum Ort") {
        val migration = provider.getResource("V1__server_state_initial.sql")

        migration shouldNotBe null
        migration!!.relativePath shouldBe "V1__server_state_initial.sql"
        migration.absolutePath shouldBe "db/migration/V1__server_state_initial.sql"
        migration.read().use { it.readText() }.uppercase() shouldContain "CREATE TABLE"
    }

    test("Praefix und Endung filtern: kein R__-Callback, keine andere Endung") {
        provider.getResources("R", arrayOf(".sql")).shouldBeEmpty()
        provider.getResources("V", arrayOf(".java")).shouldBeEmpty()
    }

    test("ein unbekannter Name liefert null") {
        provider.getResource("V99__gibt_es_nicht.sql") shouldBe null
    }

    test("ohne Index scheitert der Zugriff laut und nennt den Pfad") {
        val blind = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): InputStream? = null
            override fun getResource(name: String): URL? = null
        }

        val failure = shouldThrow<IllegalStateException> {
            IndexedMigrationResources("db/migration", blind).getResources("V", arrayOf(".sql"))
        }

        failure.message!! shouldContain "db/migration/migrations.index"
    }

    test("Java-Migrationen gibt es nicht") {
        NoJavaMigrations.getClasses().shouldBeEmpty()
    }
})
