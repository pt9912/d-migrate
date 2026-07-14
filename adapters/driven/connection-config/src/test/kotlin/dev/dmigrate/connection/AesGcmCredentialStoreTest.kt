package dev.dmigrate.connection

import dev.dmigrate.driver.connection.CredentialStoreException
import dev.dmigrate.driver.connection.StoredCredential
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class AesGcmCredentialStoreTest : FunSpec({

    val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    fun tempDir(): Path = Files.createTempDirectory("cred-store-test")

    fun storeIn(dir: Path, secret: String = "master-pw") = AesGcmCredentialStore(
        baseDir = dir, masterSecretProvider = { secret.toCharArray() }, writeIterations = 1000,
    )

    fun storeFile(dir: Path): Path = dir.resolve(AesGcmCredentialStore.STORE_FILENAME)

    test("put then resolve round-trips user and password") {
        val store = storeIn(tempDir())
        store.put("prod", "admin", "s3cr3t".toCharArray())
        val c = store.resolve("prod").shouldNotBeNull()
        c.user shouldBe "admin"
        String(c.password()) shouldBe "s3cr3t"
    }

    test("listNames returns sorted names") {
        val store = storeIn(tempDir())
        store.put("zeta", "u", "p".toCharArray())
        store.put("alpha", "u", "p".toCharArray())
        store.listNames() shouldContainExactly listOf("alpha", "zeta")
    }

    test("put upserts an existing name") {
        val store = storeIn(tempDir())
        store.put("prod", "old", "oldpass".toCharArray())
        store.put("prod", "new", "newpass".toCharArray())
        val c = store.resolve("prod").shouldNotBeNull()
        c.user shouldBe "new"
        String(c.password()) shouldBe "newpass"
        store.listNames() shouldContainExactly listOf("prod")
    }

    test("missing store: empty list and null resolve, no exception") {
        val store = storeIn(tempDir())
        store.listNames() shouldBe emptyList()
        store.resolve("x") shouldBe null
    }

    test("wrong master secret fails to decrypt") {
        val dir = tempDir()
        storeIn(dir, "right").put("prod", "admin", "s3cr3t".toCharArray())
        shouldThrow<CredentialStoreException> { storeIn(dir, "wrong").listNames() }
        shouldThrow<CredentialStoreException> { storeIn(dir, "wrong").resolve("prod") }
    }

    test("each write uses a fresh nonce/salt so identical content yields different ciphertext") {
        val dir = tempDir()
        val store = storeIn(dir)
        store.put("prod", "admin", "s3cr3t".toCharArray())
        val first = Files.readAllBytes(storeFile(dir))
        store.put("prod", "admin", "s3cr3t".toCharArray())
        val second = Files.readAllBytes(storeFile(dir))
        first.contentEquals(second) shouldBe false
    }

    test("tampering the ciphertext/tag is detected") {
        val dir = tempDir()
        storeIn(dir).put("prod", "admin", "s3cr3t".toCharArray())
        val bytes = Files.readAllBytes(storeFile(dir))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        Files.write(storeFile(dir), bytes)
        shouldThrow<CredentialStoreException> { storeIn(dir).listNames() }
    }

    test("tampering the header (GCM AAD) is detected") {
        val dir = tempDir()
        storeIn(dir).put("prod", "admin", "s3cr3t".toCharArray())
        val bytes = Files.readAllBytes(storeFile(dir))
        bytes[9] = (bytes[9] + 1).toByte() // salt byte → derived-key + AAD mismatch
        Files.write(storeFile(dir), bytes)
        shouldThrow<CredentialStoreException> { storeIn(dir).listNames() }
    }

    test("a foreign/short file is rejected cleanly") {
        val dir = tempDir()
        Files.createDirectories(dir)
        Files.write(storeFile(dir), "not a credential store".toByteArray())
        shouldThrow<CredentialStoreException> { storeIn(dir).listNames() }
    }

    test("store file is owner-only 0600 on POSIX") {
        if (!posix) return@test
        val dir = tempDir()
        storeIn(dir).put("prod", "admin", "s3cr3t".toCharArray())
        Files.getPosixFilePermissions(storeFile(dir)) shouldBe
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }

    test("base directory is created 0700 on POSIX when absent") {
        if (!posix) return@test
        val dir = tempDir().resolve(".d-migrate")
        AesGcmCredentialStore(baseDir = dir, masterSecretProvider = { "m".toCharArray() }, writeIterations = 1000)
            .put("prod", "admin", "s3cr3t".toCharArray())
        Files.getPosixFilePermissions(dir) shouldBe setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
        )
    }

    test("no leftover temp files after a write") {
        val dir = tempDir()
        storeIn(dir).put("prod", "admin", "s3cr3t".toCharArray())
        val leftovers = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.count() }
        leftovers shouldBe 0L
    }

    test("write fails cleanly (CredentialStoreException) when the base dir is a regular file") {
        // baseDir existiert als reguläre Datei → ensureDir/createDirectories wirft IOException.
        // Muss als sauberer, secret-freier CredentialStoreException (Exit 7) ankommen, nicht als roher Crash.
        val notADir = Files.createTempFile("cred-store-not-a-dir", ".tmp")
        val store = AesGcmCredentialStore(
            baseDir = notADir, masterSecretProvider = { "m".toCharArray() }, writeIterations = 1000,
        )
        shouldThrow<CredentialStoreException> { store.put("prod", "admin", "s3cr3t".toCharArray()) }
    }

    test("StoredCredential masks in toString and clears on wipe") {
        val c = StoredCredential("admin", "s3cr3t".toCharArray())
        c.toString() shouldBe "StoredCredential(user=***, password=***)"
        c.wipe()
        String(c.password()) shouldBe "      " // 6 spaces (wiped)
    }
})
