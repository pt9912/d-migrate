package dev.dmigrate.connection

import dev.dmigrate.driver.connection.CredentialStoreException
import dev.dmigrate.driver.connection.CredentialStorePort
import dev.dmigrate.driver.connection.StoredCredential
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * Lokaler verschlüsselter Credential-Store (LN-025 „O2", ADR 0034). Die **ganze Datei** ist ein
 * AES-256-GCM-Blob (`~/.d-migrate/credentials.enc`); jede Operation entschlüsselt zuerst und braucht das
 * Master-Secret. Der Schreibpfad ist atomar (Temp im selben Verzeichnis + `ATOMIC_MOVE`), Dateirechte
 * `0600` bei Erstellung, das Verzeichnis `0700` (POSIX; auf Nicht-POSIX Plattform-Default).
 *
 * @param baseDir Basisverzeichnis (Default `~/.d-migrate`); für Tests injizierbar (`@TempDir`).
 * @param masterSecretProvider liefert das Master-Secret je Operation (Env oder Prompt); der Store wiped die
 *   zurückgegebene Kopie nach Gebrauch.
 * @param writeIterations PBKDF2-Iterationen für **neue** Schreibvorgänge (Lesen nutzt die im Header
 *   gespeicherte Zahl); für schnelle Tests herabsetzbar.
 */
class AesGcmCredentialStore(
    private val baseDir: Path = Paths.get(System.getProperty("user.home"), DEFAULT_DIR_NAME),
    private val masterSecretProvider: () -> CharArray,
    private val writeIterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) : CredentialStorePort {

    private val storeFile: Path get() = baseDir.resolve(STORE_FILENAME)

    override fun put(name: String, user: String, password: CharArray) {
        val entries = readEntries()
        try {
            entries.remove(name)?.wipe()
            entries[name] = CredentialEntry(user, CredentialBlobCodec.charsToBytes(password))
            writeEntries(entries)
        } finally {
            entries.values.forEach { it.wipe() }
        }
    }

    override fun listNames(): List<String> {
        val entries = readEntries()
        try {
            return entries.keys.sorted()
        } finally {
            entries.values.forEach { it.wipe() }
        }
    }

    override fun resolve(name: String): StoredCredential? {
        val entries = readEntries()
        try {
            val e = entries[name] ?: return null
            return StoredCredential(e.user, CredentialBlobCodec.bytesToChars(e.pass))
        } finally {
            entries.values.forEach { it.wipe() }
        }
    }

    override fun isInitialized(): Boolean = Files.isRegularFile(storeFile)

    private fun readEntries(): LinkedHashMap<String, CredentialEntry> {
        if (!Files.isRegularFile(storeFile)) return LinkedHashMap()
        val file = try {
            Files.readAllBytes(storeFile)
        } catch (e: IOException) {
            throw CredentialStoreException("Credential-Store nicht lesbar: ${storeFile.fileName}", e)
        }
        val secret = masterSecretProvider()
        val plaintext = try {
            CredentialCipher.decrypt(file, secret)
        } finally {
            secret.fill(' ')
        }
        return try {
            CredentialBlobCodec.deserialize(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeEntries(entries: Map<String, CredentialEntry>) {
        val plaintext = CredentialBlobCodec.serialize(entries)
        val secret = masterSecretProvider()
        val encrypted = try {
            CredentialCipher.encrypt(plaintext, secret, writeIterations, random)
        } finally {
            secret.fill(' ')
            plaintext.fill(0)
        }
        writeAtomically(encrypted)
    }

    private fun writeAtomically(bytes: ByteArray) {
        ensureDir()
        val posix = isPosix()
        val tmp = if (posix) {
            Files.createTempFile(baseDir, TMP_PREFIX, TMP_SUFFIX, PosixFilePermissions.asFileAttribute(OWNER_RW))
        } else {
            Files.createTempFile(baseDir, TMP_PREFIX, TMP_SUFFIX)
        }
        try {
            Files.write(tmp, bytes)
            moveIntoPlace(tmp)
        } catch (e: IOException) {
            Files.deleteIfExists(tmp)
            throw CredentialStoreException("Store nicht schreibbar: ${storeFile.fileName}", e)
        }
    }

    private fun moveIntoPlace(tmp: Path) {
        try {
            Files.move(tmp, storeFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ensureDir() {
        if (Files.isDirectory(baseDir)) return
        if (isPosix()) {
            Files.createDirectories(baseDir, PosixFilePermissions.asFileAttribute(OWNER_RWX))
        } else {
            Files.createDirectories(baseDir)
        }
    }

    private fun isPosix(): Boolean =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    companion object {
        const val DEFAULT_DIR_NAME = ".d-migrate"
        const val STORE_FILENAME = "credentials.enc"
        const val DEFAULT_ITERATIONS = 600_000
        private const val TMP_PREFIX = "credentials"
        private const val TMP_SUFFIX = ".tmp"
        private val OWNER_RW = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val OWNER_RWX = OWNER_RW + PosixFilePermission.OWNER_EXECUTE
    }
}
