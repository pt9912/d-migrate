package dev.dmigrate.connection

import dev.dmigrate.driver.connection.CredentialStoreException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.FunSpec
import java.security.SecureRandom

class CredentialCryptoTest : FunSpec({

    test("blob codec round-trips names, users and passwords") {
        val entries = linkedMapOf(
            "prod" to CredentialEntry("admin", "s3cr3t".toByteArray()),
            "dev" to CredentialEntry("dev", "täb\tnewline\n".toByteArray()),
        )
        val back = CredentialBlobCodec.deserialize(CredentialBlobCodec.serialize(entries))
        back.keys.toList() shouldBe listOf("prod", "dev")
        back["prod"]!!.user shouldBe "admin"
        String(back["prod"]!!.pass) shouldBe "s3cr3t"
        String(back["dev"]!!.pass) shouldBe "täb\tnewline\n" // delimiter-safe (length-prefixed)
    }

    test("empty map round-trips to empty map") {
        CredentialBlobCodec.deserialize(CredentialBlobCodec.serialize(emptyMap())).size shouldBe 0
    }

    test("chars<->bytes round-trip without a String (UTF-8, incl. non-ASCII)") {
        val chars = "pä\$w0rd€".toCharArray()
        val bytes = CredentialBlobCodec.charsToBytes(chars)
        CredentialBlobCodec.bytesToChars(bytes) shouldBe chars
    }

    test("a truncated blob is rejected as corrupt") {
        val good = CredentialBlobCodec.serialize(
            linkedMapOf("prod" to CredentialEntry("admin", "s3cr3t".toByteArray())),
        )
        shouldThrow<CredentialStoreException> {
            CredentialBlobCodec.deserialize(good.copyOfRange(0, good.size - 3))
        }
    }

    test("cipher encrypt then decrypt round-trips the plaintext") {
        val secret = "master".toCharArray()
        val pt = "hello world".toByteArray()
        val file = CredentialCipher.encrypt(pt, secret.copyOf(), 1000, SecureRandom())
        String(CredentialCipher.decrypt(file, secret.copyOf())) shouldBe "hello world"
    }

    test("cipher rejects a too-short file") {
        shouldThrow<CredentialStoreException> {
            CredentialCipher.decrypt(byteArrayOf(1, 2, 3), "m".toCharArray())
        }
    }

    test("a header with a non-positive iteration count is a clean corrupt-file error, not an IllegalArgumentException") {
        val file = CredentialCipher.encrypt("x".toByteArray(), "m".toCharArray(), 1000, SecureRandom())
        // Iterationszahl (Big-Endian-Int ab Offset 5) auf 0 überschreiben — PBEKeySpec würde sonst ein
        // ungefangenes IllegalArgumentException werfen (Absturz statt sauberem Fehler).
        setIterations(file, 0)
        shouldThrow<CredentialStoreException> {
            CredentialCipher.decrypt(file, "m".toCharArray())
        }
    }

    test("a header with an absurd iteration count is rejected as corrupt (no PBKDF2 runaway)") {
        val file = CredentialCipher.encrypt("x".toByteArray(), "m".toCharArray(), 1000, SecureRandom())
        setIterations(file, Int.MAX_VALUE) // > MAX_ITERATIONS → sauberer Fehler statt Milliarden-Runden-Lauf
        shouldThrow<CredentialStoreException> {
            CredentialCipher.decrypt(file, "m".toCharArray())
        }
    }
})

/** Überschreibt die Iterationszahl (Big-Endian-Int) im 37-Byte-Header ab Offset 5. */
private fun setIterations(file: ByteArray, value: Int) {
    val iterOffset = 5
    file[iterOffset] = (value ushr 24).toByte()
    file[iterOffset + 1] = (value ushr 16).toByte()
    file[iterOffset + 2] = (value ushr 8).toByte()
    file[iterOffset + 3] = value.toByte()
}
