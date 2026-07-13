package dev.dmigrate.connection

import dev.dmigrate.driver.connection.CredentialStoreException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ein Store-Eintrag im entschlüsselten Zustand. [pass] wird nach Gebrauch [wipe]-t; niemals als `String`
 * materialisiert (Passwort bleibt Byte-Array).
 */
internal class CredentialEntry(val user: String, val pass: ByteArray) {
    fun wipe() {
        pass.fill(0)
    }
}

/**
 * Serialisiert die Eintrags-Map in einen **längenpräfixierten Binär-Blob** (kein `String` fürs Passwort,
 * keine Delimiter-Probleme). Format: `count | (nameLen name userLen user passLen pass)*`.
 */
internal object CredentialBlobCodec {

    private const val MAX_FIELD_BYTES = 1 shl 20 // 1 MiB Sanity-Grenze pro Feld

    fun serialize(entries: Map<String, CredentialEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { dos ->
            dos.writeInt(entries.size)
            for ((name, e) in entries) {
                writeField(dos, name.toByteArray(StandardCharsets.UTF_8))
                writeField(dos, e.user.toByteArray(StandardCharsets.UTF_8))
                writeField(dos, e.pass)
            }
        }
        return out.toByteArray()
    }

    fun deserialize(blob: ByteArray): LinkedHashMap<String, CredentialEntry> {
        val map = LinkedHashMap<String, CredentialEntry>()
        try {
            DataInputStream(ByteArrayInputStream(blob)).use { dis ->
                val count = dis.readInt()
                if (count < 0 || count > MAX_FIELD_BYTES) corrupt()
                repeat(count) {
                    val name = String(readField(dis), StandardCharsets.UTF_8)
                    val user = String(readField(dis), StandardCharsets.UTF_8)
                    map[name] = CredentialEntry(user, readField(dis))
                }
            }
        } catch (e: IOException) {
            throw CredentialStoreException("Store-Inhalt beschädigt.", e)
        }
        return map
    }

    /** UTF-8-Kodierung ohne `String`-Zwischenschritt (Passwort bleibt Byte-Array). */
    fun charsToBytes(chars: CharArray): ByteArray {
        val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        return ByteArray(bb.remaining()).also { bb.get(it) }
    }

    /** UTF-8-Dekodierung ohne `String`-Zwischenschritt. */
    fun bytesToChars(bytes: ByteArray): CharArray {
        val cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        return CharArray(cb.remaining()).also { cb.get(it) }
    }

    private fun writeField(dos: DataOutputStream, b: ByteArray) {
        dos.writeInt(b.size)
        dos.write(b)
    }

    private fun readField(dis: DataInputStream): ByteArray {
        val len = dis.readInt()
        if (len < 0 || len > MAX_FIELD_BYTES) corrupt()
        return ByteArray(len).also { dis.readFully(it) }
    }

    private fun corrupt(): Nothing = throw CredentialStoreException("Store-Inhalt beschädigt.")
}

/**
 * AES-256-GCM mit PBKDF2-HMAC-SHA256-Schlüsselableitung. Dateiformat:
 * `magic | version | iterations | salt | nonce | ciphertext+tag`. Der Header wird als GCM-**AAD**
 * mitauthentifiziert (Parameter-Tampering wird erkannt). Die Iterationszahl steht **explizit** im
 * Header, damit ein späterer OWASP-Bump alte Dateien nicht bricht.
 */
internal object CredentialCipher {

    private val MAGIC = byteArrayOf('D'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte())
    private const val VERSION = 1
    private const val VERSION_OFFSET = 4
    private const val ITER_OFFSET = 5
    private const val SALT_OFFSET = 9
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val NONCE_OFFSET = SALT_OFFSET + SALT_LEN
    private const val HEADER_LEN = NONCE_OFFSET + NONCE_LEN
    private const val GCM_TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val BYTE_MASK = 0xFF

    fun encrypt(plaintext: ByteArray, secret: CharArray, iterations: Int, random: SecureRandom): ByteArray {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val header = buildHeader(iterations, salt, nonce)
        val key = deriveKey(secret, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(plaintext)
        } catch (e: GeneralSecurityException) {
            throw CredentialStoreException("Verschlüsselung fehlgeschlagen.", e)
        }
    }

    fun decrypt(file: ByteArray, secret: CharArray): ByteArray {
        if (file.size < HEADER_LEN || !hasMagic(file)) {
            throw CredentialStoreException("Beschädigte oder fremde Store-Datei.")
        }
        val iterations = readInt(file, ITER_OFFSET)
        val salt = file.copyOfRange(SALT_OFFSET, SALT_OFFSET + SALT_LEN)
        val nonce = file.copyOfRange(NONCE_OFFSET, HEADER_LEN)
        val header = file.copyOfRange(0, HEADER_LEN)
        val key = deriveKey(secret, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(header)
            cipher.doFinal(file, HEADER_LEN, file.size - HEADER_LEN)
        } catch (e: AEADBadTagException) {
            throw CredentialStoreException("Falsches Master-Secret oder manipulierte Store-Datei.", e)
        } catch (e: GeneralSecurityException) {
            throw CredentialStoreException("Entschlüsselung fehlgeschlagen.", e)
        }
    }

    private fun deriveKey(secret: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(secret, salt, iterations, KEY_BITS)
        try {
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return SecretKeySpec(keyBytes, "AES")
            } finally {
                keyBytes.fill(0)
            }
        } catch (e: GeneralSecurityException) {
            throw CredentialStoreException("Schlüsselableitung fehlgeschlagen.", e)
        } finally {
            spec.clearPassword()
        }
    }

    private fun buildHeader(iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray {
        val h = ByteArray(HEADER_LEN)
        System.arraycopy(MAGIC, 0, h, 0, MAGIC.size)
        h[VERSION_OFFSET] = VERSION.toByte()
        writeInt(h, ITER_OFFSET, iterations)
        System.arraycopy(salt, 0, h, SALT_OFFSET, SALT_LEN)
        System.arraycopy(nonce, 0, h, NONCE_OFFSET, NONCE_LEN)
        return h
    }

    private fun hasMagic(b: ByteArray): Boolean = MAGIC.indices.all { b[it] == MAGIC[it] }

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and BYTE_MASK shl 24) or
            (b[off + 1].toInt() and BYTE_MASK shl 16) or
            (b[off + 2].toInt() and BYTE_MASK shl 8) or
            (b[off + 3].toInt() and BYTE_MASK)
}
