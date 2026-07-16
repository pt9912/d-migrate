package dev.dmigrate.connection

import dev.dmigrate.server.ports.CredentialResolution
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files

class FileCredentialProviderTest : FunSpec({

    test("scheme is file:") {
        FileCredentialProvider().scheme shouldBe "file:"
    }

    test("reads the file content as the full connect URL, trimmed") {
        val file = Files.createTempFile("dmigrate-cred-", ".url")
        try {
            Files.writeString(file, "  postgresql://app:s3cret@host:5432/db\n\n")
            val outcome = FileCredentialProvider().resolve("file:$file")
                .shouldBeInstanceOf<CredentialResolution.Success>()
            outcome.url shouldBe "postgresql://app:s3cret@host:5432/db"
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("blank path surfaces FILE_NOT_FOUND") {
        FileCredentialProvider().resolve("file:")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
            .reason shouldBe CredentialResolution.REASON_FILE_NOT_FOUND
    }

    test("missing file surfaces FILE_NOT_FOUND") {
        FileCredentialProvider().resolve("file:/no/such/dmigrate-${System.nanoTime()}.url")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
            .reason shouldBe CredentialResolution.REASON_FILE_NOT_FOUND
    }

    test("a directory (not a regular file) surfaces FILE_NOT_FOUND") {
        val dir = Files.createTempDirectory("dmigrate-cred-dir-")
        try {
            FileCredentialProvider().resolve("file:$dir")
                .shouldBeInstanceOf<CredentialResolution.Failure>()
                .reason shouldBe CredentialResolution.REASON_FILE_NOT_FOUND
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    test("empty file surfaces EMPTY_VALUE") {
        val file = Files.createTempFile("dmigrate-cred-empty-", ".url")
        try {
            Files.writeString(file, "   \n\t ")
            FileCredentialProvider().resolve("file:$file")
                .shouldBeInstanceOf<CredentialResolution.Failure>()
                .reason shouldBe CredentialResolution.REASON_EMPTY_VALUE
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("an I/O error surfaces FILE_UNREADABLE, never echoing the secret content") {
        val provider = FileCredentialProvider(
            isRegularFile = { true },
            readFile = { throw IOException("permission denied for /secret/db-super-secret-value") },
            fileSize = { 10L }, // pass the size gate so the readFile path is exercised
        )
        val outcome = provider.resolve("file:/secret/db")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
        outcome.reason shouldBe CredentialResolution.REASON_FILE_UNREADABLE
        // detail names the operator-facing path but never the underlying exception's leaked fragment
        outcome.detail shouldContain "/secret/db"
        outcome.detail shouldNotContain "super-secret-value"
    }

    test("a file exceeding the size cap surfaces FILE_UNREADABLE (no unbounded read / OOM)") {
        val provider = FileCredentialProvider(
            isRegularFile = { true },
            readFile = { error("must not read an oversized file") },
            fileSize = { FileCredentialProvider.MAX_FILE_BYTES + 1 },
        )
        provider.resolve("file:/huge")
            .shouldBeInstanceOf<CredentialResolution.Failure>()
            .reason shouldBe CredentialResolution.REASON_FILE_UNREADABLE
    }

    test("a leading UTF-8 BOM is stripped from the URL") {
        val file = Files.createTempFile("dmigrate-cred-bom-", ".url")
        try {
            Files.writeString(file, "\uFEFFpostgresql://app:pw@host/db")
            FileCredentialProvider().resolve("file:$file")
                .shouldBeInstanceOf<CredentialResolution.Success>()
                .url shouldBe "postgresql://app:pw@host/db"
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("a BOM-only file is treated as empty (EMPTY_VALUE), not a bogus 1-char URL") {
        val file = Files.createTempFile("dmigrate-cred-bomonly-", ".url")
        try {
            Files.writeString(file, "\uFEFF")
            FileCredentialProvider().resolve("file:$file")
                .shouldBeInstanceOf<CredentialResolution.Failure>()
                .reason shouldBe CredentialResolution.REASON_EMPTY_VALUE
        } finally {
            Files.deleteIfExists(file)
        }
    }
})
