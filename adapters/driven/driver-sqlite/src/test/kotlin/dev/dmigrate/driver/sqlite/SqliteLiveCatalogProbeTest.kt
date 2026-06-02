package dev.dmigrate.driver.sqlite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.sql.DriverManager

/**
 * Plan-2 §A.2: pins the contract that
 * [SqliteLiveCatalogProbe.probe] reads `sqlite_master` into the
 * four port-level sets, drops `sqlite_%` system objects, and
 * surfaces SQL exceptions to the caller.
 */
class SqliteLiveCatalogProbeTest : FunSpec({

    test("probe reads tables, views, indices, and triggers separately") {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { s ->
                s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
                s.execute("CREATE TABLE other (id INTEGER PRIMARY KEY)")
                s.execute("CREATE VIEW v AS SELECT id FROM t")
                s.execute("CREATE INDEX ix_t_name ON t(name)")
                s.execute(
                    """CREATE TRIGGER tr_t_audit AFTER INSERT ON t
                       BEGIN SELECT 1; END""",
                )
            }
            val catalog = SqliteLiveCatalogProbe.probe(conn)
            catalog.tables shouldBe setOf("t", "other")
            catalog.views shouldBe setOf("v")
            catalog.indices shouldBe setOf("ix_t_name")
            catalog.triggers shouldBe setOf("tr_t_audit")
        }
    }

    test("probe filters sqlite_% system objects") {
        // SQLite creates `sqlite_autoindex_<table>_<n>` for table PRIMARY KEYs
        // (when the column type isn't INTEGER PRIMARY KEY) and
        // `sqlite_sequence` when AUTOINCREMENT is used. The probe MUST
        // ignore these — they're never user objects and never collide
        // with renderer-chosen rebuild temp names.
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { s ->
                s.execute("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
                s.execute("CREATE TABLE u (a TEXT, b TEXT, PRIMARY KEY (a, b))")
            }
            val catalog = SqliteLiveCatalogProbe.probe(conn)
            catalog.tables shouldContain "t"
            catalog.tables shouldContain "u"
            // sqlite_sequence and sqlite_autoindex_* must be filtered.
            catalog.tables shouldNotContain "sqlite_sequence"
            catalog.indices.none { it.startsWith("sqlite_") } shouldBe true
        }
    }

    test("probe on empty database returns empty sets") {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            val catalog = SqliteLiveCatalogProbe.probe(conn)
            catalog.tables shouldBe emptySet<String>()
            catalog.views shouldBe emptySet<String>()
            catalog.indices shouldBe emptySet<String>()
            catalog.triggers shouldBe emptySet<String>()
        }
    }
})
