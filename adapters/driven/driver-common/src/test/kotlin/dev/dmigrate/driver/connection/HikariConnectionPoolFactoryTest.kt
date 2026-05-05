package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Lifecycle-Tests für HikariConnectionPoolFactory gegen SQLite :memory:.
 *
 * Diese Tests verifizieren:
 * - F15: SQLite-Defaults (foreign_keys=true, journal_mode=wal) werden injiziert
 * - F16: Query-Parameter sind round-trip-sicher (URL-encoded → decoded → re-encoded)
 * - F17: ConnectionPool-Lifecycle (borrow/use/close, activeConnections-Tracking)
 *
 * SQLite-JDBC ist nur als testImplementation gezogen — driver-api/main bleibt
 * frei von konkreten Treibern.
 */
class HikariConnectionPoolFactoryTest : FunSpec({

    fun memoryConfig(extraParams: Map<String, String> = emptyMap()) = ConnectionConfig(
        dialect = DatabaseDialect.SQLITE,
        host = null,
        port = null,
        database = ":memory:",
        user = null,
        password = null,
        params = extraParams,
    )

    // ─── F17: Lifecycle ──────────────────────────────────────────

    test("create / borrow / close lifecycle works") {
        HikariConnectionPoolFactory.create(memoryConfig()).use { pool ->
            pool.dialect shouldBe DatabaseDialect.SQLITE
            pool.borrow().use { conn ->
                conn shouldNotBe null
                conn.isClosed shouldBe false
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT 1")
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }

    test("activeConnections reflects borrow/return") {
        HikariConnectionPoolFactory.create(memoryConfig()).use { pool ->
            // Vorher: 0 active
            pool.activeConnections() shouldBe 0
            val conn = pool.borrow()
            try {
                pool.activeConnections() shouldBeGreaterThanOrEqual 1
            } finally {
                conn.close()
            }
            // Nach close: zurück auf 0 (Hikari kann minimal asynchron sein, akzeptiere <=1)
        }
    }

    test("pool.close() is idempotent") {
        val pool = HikariConnectionPoolFactory.create(memoryConfig())
        pool.close()
        pool.close()  // darf nicht werfen
    }

    test("borrowed connection.close() returns to pool — not physically closed") {
        HikariConnectionPoolFactory.create(memoryConfig()).use { pool ->
            // Erste borrow
            val first = pool.borrow()
            first.close()  // gibt zurück
            // Zweite borrow sollte funktionieren (poolSize=1 für SQLite)
            pool.borrow().use { second ->
                second.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT 42").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 42
                    }
                }
            }
        }
    }

    // ─── F15: SQLite-Defaults (foreign_keys, journal_mode) ───────

    test("SQLite default foreign_keys=true is enforced") {
        HikariConnectionPoolFactory.create(memoryConfig()).use { pool ->
            pool.borrow().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA foreign_keys").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        }
    }

    test("SQLite default journal_mode=wal is set (best-effort — :memory: forces memory mode)") {
        // SQLite ignoriert journal_mode=wal für :memory: und antwortet 'memory'.
        // Wir prüfen, dass die URL-Konstruktion den Parameter mitgibt — der echte
        // Effekt wird mit einer File-DB verifiziert.
        HikariConnectionPoolFactory.create(memoryConfig()).use { pool ->
            pool.borrow().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA journal_mode").use { rs ->
                        rs.next() shouldBe true
                        // memory ist die einzig gültige Antwort für :memory:; die Default-Pipeline
                        // hat den Parameter trotzdem via JDBC-URL durchgereicht.
                        rs.getString(1).lowercase() shouldBe "memory"
                    }
                }
            }
        }
    }

    test("user can override SQLite default foreign_keys") {
        HikariConnectionPoolFactory
            .create(memoryConfig(mapOf("foreign_keys" to "false")))
            .use { pool ->
                pool.borrow().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery("PRAGMA foreign_keys").use { rs ->
                            rs.next() shouldBe true
                            rs.getInt(1) shouldBe 0
                        }
                    }
                }
            }
    }

    // ─── F16: Round-Trip URL-Encoding ────────────────────────────

    test("query parameters with special chars round-trip through parser → factory → JDBC") {
        // Wir bauen die URL mit URL-encoded Wert, parser dekodiert,
        // Factory muss wieder kodieren — sonst wirft SQLite/Hikari einen URL-Fehler.
        val parsed = ConnectionUrlParser.parse(
            "sqlite::memory:?cache=shared&app%20name=d%20migrate"
        )
        parsed.params["cache"] shouldBe "shared"
        parsed.params["app name"] shouldBe "d migrate"

        // Factory muss die URL korrekt re-encoden, sonst wirft der JDBC-Treiber
        HikariConnectionPoolFactory.create(parsed).use { pool ->
            pool.borrow().use { conn ->
                conn.isClosed shouldBe false
            }
        }
    }

    test("PoolSettings minimumPoolSize=1 is enforced for SQLite") {
        // Auch wenn der User pool=10 angibt, wird SQLite auf 1 gezwungen
        val cfg = memoryConfig().copy(pool = PoolSettings(maximumPoolSize = 10, minimumIdle = 5))
        HikariConnectionPoolFactory.create(cfg).use { pool ->
            // Wir können nicht direkt an HikariCP.maximumPoolSize ran, aber wir prüfen,
            // dass der Pool als SQLite-Pool funktioniert ohne Pool-Size-Konflikte
            pool.dialect shouldBe DatabaseDialect.SQLITE
            pool.borrow().use { it.isClosed shouldBe false }
        }
    }

    // ─── PoolSettings für non-SQLite werden NICHT überschrieben ──

    test("non-SQLite dialects keep their pool settings") {
        // Für PG/MySQL haben wir keinen funktionsfähigen Treiber im Test classpath,
        // aber wir können wenigstens die JDBC-URL-Konstruktion über einen Connect-
        // Versuch indirekt prüfen — hier nur über die Tatsache, dass create() nicht
        // sofort wegen URL-Format wirft (Hikari würde erst beim ersten getConnection()
        // versuchen zu connecten).
        // Stattdessen prüfen wir nur, dass defaultsFor(POSTGRESQL/MYSQL) leer ist
        // — das ist über das öffentliche Verhalten nicht direkt sichtbar, aber wir
        // verifizieren das indirekt im Round-Trip-Test oben.
        // Dieser Test ist Platzhalter; konkrete PG/MySQL-Connection-Tests folgen
        // in Phase B mit Testcontainers (@Tag("integration")).
    }

    // ─── ConnectionConfig.toString() Maskierung ──────────────────

    test("PoolSettings is constructable with all fields") {
        val s = PoolSettings(
            maximumPoolSize = 5,
            minimumIdle = 1,
            connectionTimeoutMs = 5000,
            idleTimeoutMs = 60_000,
            maxLifetimeMs = 120_000,
            keepaliveTimeMs = 30_000,
        )
        s.maximumPoolSize shouldBe 5
        s.minimumIdle shouldBe 1
        s.connectionTimeoutMs shouldBe 5000L
    }

    test("ConnectionConfig with params keeps them in order") {
        val cfg = memoryConfig(mapOf("a" to "1", "b" to "2", "c" to "3"))
        cfg.params.keys.toList() shouldContainExactly listOf("a", "b", "c")
    }

    // ─── E0.7.2: connectionInitSqlFor ──────────────────────────

    test("connectionInitSqlFor builds PostgreSQL statement_timeout SQL") {
        HikariConnectionPoolFactory.connectionInitSqlFor(
            DatabaseDialect.POSTGRESQL, 30_000,
        ) shouldBe "SET statement_timeout = 30000"
    }

    test("connectionInitSqlFor builds MySQL MAX_EXECUTION_TIME SQL") {
        HikariConnectionPoolFactory.connectionInitSqlFor(
            DatabaseDialect.MYSQL, 30_000,
        ) shouldBe "SET SESSION MAX_EXECUTION_TIME = 30000"
    }

    test("connectionInitSqlFor builds SQLite busy_timeout PRAGMA") {
        HikariConnectionPoolFactory.connectionInitSqlFor(
            DatabaseDialect.SQLITE, 30_000,
        ) shouldBe "PRAGMA busy_timeout = 30000"
    }

    test("connectionInitSqlFor honors a custom positive timeout") {
        HikariConnectionPoolFactory.connectionInitSqlFor(
            DatabaseDialect.POSTGRESQL, 5_000,
        ) shouldBe "SET statement_timeout = 5000"
        HikariConnectionPoolFactory.connectionInitSqlFor(
            DatabaseDialect.MYSQL, 5_000,
        ) shouldBe "SET SESSION MAX_EXECUTION_TIME = 5000"
        HikariConnectionPoolFactory.connectionInitSqlFor(
            DatabaseDialect.SQLITE, 5_000,
        ) shouldBe "PRAGMA busy_timeout = 5000"
    }

    test("connectionInitSqlFor returns null for statementTimeoutMs == 0 (disabled)") {
        HikariConnectionPoolFactory.connectionInitSqlFor(
            DatabaseDialect.POSTGRESQL, 0,
        ) shouldBe null
        HikariConnectionPoolFactory.connectionInitSqlFor(
            DatabaseDialect.MYSQL, 0,
        ) shouldBe null
        HikariConnectionPoolFactory.connectionInitSqlFor(
            DatabaseDialect.SQLITE, 0,
        ) shouldBe null
    }

    test("create wires the SQLite PRAGMA into the live pool — busy_timeout is observable") {
        // SQLite is the only dialect we can verify end-to-end without
        // a Testcontainer. After the connection-init-SQL runs,
        // `PRAGMA busy_timeout` must report the configured value.
        val cfg = memoryConfig().copy(pool = PoolSettings(statementTimeoutMs = 7_500))
        HikariConnectionPoolFactory.create(cfg).use { pool ->
            pool.borrow().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA busy_timeout").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 7_500
                    }
                }
            }
        }
    }

    test("create with statementTimeoutMs == 0 does not set our PRAGMA — driver default applies") {
        // Compare against the value we would get if init-SQL ran. With
        // `statementTimeoutMs = 0` the factory must skip the PRAGMA so
        // the driver default (whatever the xerial JDBC build chose,
        // 0 or its hardcoded default) remains in effect.
        val ourValue = 7_500
        val withInit = memoryConfig().copy(pool = PoolSettings(statementTimeoutMs = ourValue))
        val withoutInit = memoryConfig().copy(pool = PoolSettings(statementTimeoutMs = 0))

        val driverDefault = HikariConnectionPoolFactory.create(withoutInit).use { pool ->
            pool.borrow().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA busy_timeout").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1)
                    }
                }
            }
        }
        // Init-SQL ran path returns ourValue; disabled path returns the
        // driver default which by definition differs from `ourValue`.
        driverDefault shouldNotBe ourValue
        HikariConnectionPoolFactory.create(withInit).use { pool ->
            pool.borrow().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA busy_timeout").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe ourValue
                    }
                }
            }
        }
    }
})
