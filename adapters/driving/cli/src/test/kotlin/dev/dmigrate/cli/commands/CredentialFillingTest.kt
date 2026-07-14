package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.CredentialStoreException
import dev.dmigrate.driver.connection.CredentialStorePort
import dev.dmigrate.driver.connection.StoredCredential
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.nio.file.Paths

/** Fake-Store, der — wie der echte — die vom Provider gelieferte Secret-Kopie nach Gebrauch wiped. */
private class FillingFakeStore(
    private val initialized: Boolean,
    private val entries: Map<String, Pair<String, String>>,
    private val secretProvider: () -> CharArray,
    private val onSecret: (String) -> Unit = {},
    private val throwOnResolve: Boolean = false,
) : CredentialStorePort {
    override fun put(name: String, user: String, password: CharArray): Unit = error("unused")
    override fun listNames(): List<String> = entries.keys.sorted()
    override fun isInitialized(): Boolean = initialized
    override fun resolve(name: String): StoredCredential? {
        val s = secretProvider()
        onSecret(String(s))
        s.fill(' ')
        if (throwOnResolve) throw CredentialStoreException("Falsches Master-Secret oder manipulierte Store-Datei.")
        val e = entries[name] ?: return null
        return StoredCredential(e.first, e.second.toCharArray())
    }
}

private val UNUSED_DIR: Path = Paths.get("/unused")

private fun resolver(secret: String?, onCall: () -> Unit = {}) = MasterSecretResolver(
    prompt = { onCall(); secret?.toCharArray() }, env = { null }, isTty = { secret != null },
)

private fun session(
    entries: Map<String, Pair<String, String>>,
    initialized: Boolean = true,
    secret: String? = "master",
    onCall: () -> Unit = {},
    onSecret: (String) -> Unit = {},
) = CredentialFillSession(
    masterSecretResolver = resolver(secret, onCall),
    baseDir = UNUSED_DIR,
    storeFactory = { _, provider -> FillingFakeStore(initialized, entries, provider, onSecret) },
)

private fun cfg(dialect: DatabaseDialect, password: String?, user: String? = "u") =
    ConnectionConfig(dialect = dialect, host = "h", port = null, database = "db", user = user, password = password)

class CredentialFillingTest : FunSpec({

    // --- CredentialFillSession ---

    test("session: resolves a credential; master secret resolved once, stays intact (copy-on-read)") {
        var calls = 0
        val seen = mutableListOf<String>()
        val s = session(mapOf("prod" to ("admin" to "s3cr3t")), onCall = { calls++ }, onSecret = { seen += it })
        val c = s.resolve("prod").shouldNotBeNull()
        c.user shouldBe "admin"
        String(c.password()) shouldBe "s3cr3t"
        s.resolve("prod").shouldNotBeNull()
        calls shouldBe 1
        seen shouldBe listOf("master", "master") // never blanked between the two decrypts
    }

    test("session: missing store → null, master secret never requested") {
        var calls = 0
        session(emptyMap(), initialized = false, onCall = { calls++ }).resolve("prod").shouldBeNull()
        calls shouldBe 0
    }

    test("session: no master secret (non-TTY) → null, no fail-closed") {
        session(mapOf("prod" to ("admin" to "s3cr3t")), secret = null).resolve("prod").shouldBeNull()
    }

    test("session: unknown name → null even with a valid master secret") {
        session(mapOf("prod" to ("admin" to "s3cr3t"))).resolve("other").shouldBeNull()
    }

    test("session: wipe clears the cached secret") {
        val s = session(mapOf("prod" to ("admin" to "s3cr3t")))
        s.resolve("prod").shouldNotBeNull()
        s.wipe() // best-effort; no throw, subsequent storeExists still works
        s.storeExists() shouldBe true
    }

    // --- StoreCredentialFiller ---

    test("filler: fills password and (missing) user from the store") {
        val err = mutableListOf<String>()
        val out = StoreCredentialFiller("prod", session(mapOf("prod" to ("admin" to "s3cr3t"))), err::add)
            .fill(cfg(DatabaseDialect.POSTGRESQL, password = null, user = null))
        out.password shouldBe "s3cr3t"
        out.user shouldBe "admin"
        err.shouldBeEmpty()
    }

    test("filler: keeps an explicit URL user, fills only the password") {
        val out = StoreCredentialFiller("prod", session(mapOf("prod" to ("admin" to "s3cr3t"))), {})
            .fill(cfg(DatabaseDialect.POSTGRESQL, password = null, user = "urluser"))
        out.user shouldBe "urluser"
        out.password shouldBe "s3cr3t"
    }

    test("filler: an explicit password wins — store not consulted") {
        val out = StoreCredentialFiller("prod", session(mapOf("prod" to ("admin" to "s3cr3t"))), {})
            .fill(cfg(DatabaseDialect.POSTGRESQL, password = "explicit"))
        out.password shouldBe "explicit"
    }

    test("filler: SQLite is never touched") {
        val out = StoreCredentialFiller("prod", session(mapOf("prod" to ("admin" to "s3cr3t"))), {})
            .fill(cfg(DatabaseDialect.SQLITE, password = null))
        out.password shouldBe null
    }

    test("filler: store exists but no entry → unchanged + secret-free diagnostic") {
        val err = mutableListOf<String>()
        val out = StoreCredentialFiller("prod", session(emptyMap(), initialized = true), err::add)
            .fill(cfg(DatabaseDialect.POSTGRESQL, password = null))
        out.password shouldBe null
        err.single() shouldBe "Note: no usable stored credential for 'prod' " +
            "(missing entry or master secret) — continuing without it."
    }

    test("filler: no store → unchanged, no diagnostic") {
        val err = mutableListOf<String>()
        val out = StoreCredentialFiller("prod", session(emptyMap(), initialized = false), err::add)
            .fill(cfg(DatabaseDialect.POSTGRESQL, password = null))
        out.password shouldBe null
        err.shouldBeEmpty()
    }

    // --- CredentialFilling.parser: wrong-secret path maps to exit 7 (not an uncaught crash) ---

    test("parser: a store-decrypt failure surfaces as IllegalArgumentException (runners map it to exit 7)") {
        val throwingSession = CredentialFillSession(
            masterSecretResolver = resolver("master"),
            baseDir = UNUSED_DIR,
            storeFactory = { _, provider -> FillingFakeStore(true, emptyMap(), provider, throwOnResolve = true) },
        )
        val filling = CredentialFilling(rawSource = "prod", stderr = {}, sessionFactory = { throwingSession })
        shouldThrow<IllegalArgumentException> {
            filling.parser { cfg(DatabaseDialect.POSTGRESQL, password = null) }("postgresql://h/db")
        }
    }

    // --- CredentialFilling.storeOnTop (Bundle-Seam-Fall: Store ÜBER einen bestehenden urlParser) ---

    test("storeOnTop: a URL rawSource returns the base parser unchanged (no session built)") {
        var baseCalled = false
        val base: (String) -> ConnectionConfig = { baseCalled = true; cfg(DatabaseDialect.POSTGRESQL, "explicit") }
        val p = CredentialFilling.storeOnTop("postgresql://h/db", base, {}) { error("session must not be built") }
        p("postgresql://h/db").password shouldBe "explicit"
        baseCalled shouldBe true
    }

    test("storeOnTop: a name composes the store fill on top of base (base still invoked)") {
        var baseCalled = false
        val base: (String) -> ConnectionConfig = {
            baseCalled = true
            cfg(DatabaseDialect.POSTGRESQL, password = null, user = null)
        }
        val p = CredentialFilling.storeOnTop("prod", base, {}) { session(mapOf("prod" to ("admin" to "s3cr3t"))) }
        val out = p("postgresql://h/db")
        baseCalled shouldBe true
        out.password shouldBe "s3cr3t"
        out.user shouldBe "admin"
    }

    test("storeOnTop: a store-decrypt failure surfaces as IllegalArgumentException") {
        val base: (String) -> ConnectionConfig = { cfg(DatabaseDialect.POSTGRESQL, password = null) }
        val p = CredentialFilling.storeOnTop("prod", base, {}) {
            CredentialFillSession(
                masterSecretResolver = resolver("master"),
                baseDir = UNUSED_DIR,
                storeFactory = { _, provider -> FillingFakeStore(true, emptyMap(), provider, throwOnResolve = true) },
            )
        }
        shouldThrow<IllegalArgumentException> { p("postgresql://h/db") }
    }

    // --- CredentialFilling.perConnectionStoreFiller (Dual-Connection: transfer) ---

    test("perConnectionStoreFiller: URL ref unchanged; name refs filled; a single shared session") {
        var sessionsBuilt = 0
        val filler = CredentialFilling.perConnectionStoreFiller(stderr = {}) {
            sessionsBuilt++
            session(mapOf("prod" to ("admin" to "s3cr3t"), "warehouse" to ("wh" to "whpw")))
        }
        // URL-Ref → unverändert, keine Session gebaut
        filler(cfg(DatabaseDialect.POSTGRESQL, "explicit"), "postgresql://h/db").password shouldBe "explicit"
        // zwei Name-Refs (Quelle + Ziel) → beide aus demselben Store gefüllt, EINE Session
        filler(cfg(DatabaseDialect.POSTGRESQL, password = null), "prod").password shouldBe "s3cr3t"
        filler(cfg(DatabaseDialect.POSTGRESQL, password = null), "warehouse").password shouldBe "whpw"
        sessionsBuilt shouldBe 1
    }
})
