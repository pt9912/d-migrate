package dev.dmigrate.mcp.auth

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private fun tempFile(suffix: String, content: String): Path {
    val path = Files.createTempFile("stdio-token-", suffix)
    Files.writeString(path, content)
    path.toFile().deleteOnExit()
    return path
}

class FileStdioTokenStoreTest : FunSpec({

    test("loads JSON tokens and maps fields verbatim") {
        val path = tempFile(
            ".json",
            """
            {
              "tokens": [
                {
                  "fingerprint": "abc123",
                  "principalId": "alice",
                  "tenantId": "acme",
                  "scopes": ["dmigrate:read", "dmigrate:job:start"],
                  "isAdmin": false,
                  "auditSubject": "alice@acme",
                  "expiresAt": "2030-01-01T00:00:00Z"
                }
              ]
            }
            """.trimIndent(),
        )
        val store = FileStdioTokenStore.load(path)
        val grant = store.lookup("abc123")!!
        grant.principalId shouldBe PrincipalId("alice")
        grant.tenantId shouldBe TenantId("acme")
        grant.scopes shouldBe setOf("dmigrate:read", "dmigrate:job:start")
        grant.isAdmin shouldBe false
        grant.auditSubject shouldBe "alice@acme"
        grant.expiresAt shouldBe Instant.parse("2030-01-01T00:00:00Z")
    }

    test("loads YAML tokens via .yaml extension") {
        val path = tempFile(
            ".yaml",
            """
            tokens:
              - fingerprint: "yamlfp"
                principalId: "carol"
                tenantId: "globex"
                scopes:
                  - "dmigrate:read"
                isAdmin: true
                auditSubject: "carol@globex"
                expiresAt: "2031-06-30T12:34:56Z"
            """.trimIndent(),
        )
        val store = FileStdioTokenStore.load(path)
        val grant = store.lookup("yamlfp")!!
        grant.principalId shouldBe PrincipalId("carol")
        grant.isAdmin shouldBe true
        grant.scopes shouldBe setOf("dmigrate:read")
    }

    test("loads YAML tokens via .yml extension") {
        val path = tempFile(
            ".yml",
            """
            tokens:
              - fingerprint: "ymlfp"
                principalId: "dave"
                tenantId: "initech"
                scopes: []
                auditSubject: "dave"
                expiresAt: "2030-01-01T00:00:00Z"
            """.trimIndent(),
        )
        val store = FileStdioTokenStore.load(path)
        store.lookup("ymlfp") shouldNotBe null
    }

    test("isAdmin defaults to false when omitted") {
        val path = tempFile(
            ".json",
            """
            {"tokens":[{"fingerprint":"x","principalId":"p","tenantId":"t","scopes":["dmigrate:read"],"auditSubject":"p","expiresAt":"2030-01-01T00:00:00Z"}]}
            """.trimIndent(),
        )
        FileStdioTokenStore.load(path).lookup("x")!!.isAdmin shouldBe false
    }

    test("unknown fingerprint returns null") {
        val path = tempFile(
            ".json",
            """
            {"tokens":[{"fingerprint":"a","principalId":"p","tenantId":"t","scopes":[],"auditSubject":"p","expiresAt":"2030-01-01T00:00:00Z"}]}
            """.trimIndent(),
        )
        FileStdioTokenStore.load(path).lookup("zzz") shouldBe null
    }

    test("blank scopes are filtered out") {
        val path = tempFile(
            ".json",
            """
            {"tokens":[{"fingerprint":"a","principalId":"p","tenantId":"t","scopes":["dmigrate:read","",  "  "],"auditSubject":"p","expiresAt":"2030-01-01T00:00:00Z"}]}
            """.trimIndent(),
        )
        FileStdioTokenStore.load(path).lookup("a")!!.scopes shouldBe setOf("dmigrate:read")
    }

    test("empty tokens array yields empty store") {
        val path = tempFile(".json", """{"tokens":[]}""")
        FileStdioTokenStore.load(path).lookup("anything") shouldBe null
    }

    test("missing 'tokens' field is rejected") {
        val path = tempFile(".json", """{"foo":1}""")
        val ex = shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
        ex.message!! shouldContain "missing 'tokens'"
    }

    test("non-array 'tokens' is rejected") {
        val path = tempFile(".json", """{"tokens":"oops"}""")
        val ex = shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
        ex.message!! shouldContain "must be an array"
    }

    test("non-object root is rejected") {
        val path = tempFile(".json", """[]""")
        val ex = shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
        ex.message!! shouldContain "root must be an object"
    }

    test("non-object grant entry is rejected") {
        val path = tempFile(".json", """{"tokens":["nope"]}""")
        val ex = shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
        ex.message!! shouldContain "not an object"
    }

    test("missing required field is rejected") {
        val path = tempFile(
            ".json",
            """
            {"tokens":[{"fingerprint":"a","tenantId":"t","scopes":[],"auditSubject":"p","expiresAt":"2030-01-01T00:00:00Z"}]}
            """.trimIndent(),
        )
        val ex = shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
        ex.message!! shouldContain "principalId"
    }

    test("blank required field is rejected") {
        val path = tempFile(
            ".json",
            """
            {"tokens":[{"fingerprint":"a","principalId":"   ","tenantId":"t","scopes":[],"auditSubject":"p","expiresAt":"2030-01-01T00:00:00Z"}]}
            """.trimIndent(),
        )
        shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
    }

    test("missing scopes field is rejected") {
        val path = tempFile(
            ".json",
            """
            {"tokens":[{"fingerprint":"a","principalId":"p","tenantId":"t","auditSubject":"p","expiresAt":"2030-01-01T00:00:00Z"}]}
            """.trimIndent(),
        )
        val ex = shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
        ex.message!! shouldContain "scopes"
    }

    test("non-array scopes is rejected") {
        val path = tempFile(
            ".json",
            """
            {"tokens":[{"fingerprint":"a","principalId":"p","tenantId":"t","scopes":"nope","auditSubject":"p","expiresAt":"2030-01-01T00:00:00Z"}]}
            """.trimIndent(),
        )
        val ex = shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
        ex.message!! shouldContain "scopes"
    }

    test("malformed expiresAt is rejected with helpful message") {
        val path = tempFile(
            ".json",
            """
            {"tokens":[{"fingerprint":"a","principalId":"p","tenantId":"t","scopes":[],"auditSubject":"p","expiresAt":"not-a-date"}]}
            """.trimIndent(),
        )
        val ex = shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
        ex.message!! shouldContain "RFC-3339"
    }

    test("malformed JSON body is rejected") {
        val path = tempFile(".json", """{tokens:""")
        val ex = shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
        ex.message!! shouldContain "malformed"
    }

    test("nonexistent file is rejected with unreadable message") {
        val path = Path.of("/no/such/path-${System.nanoTime()}.json")
        val ex = shouldThrow<StdioTokenStoreLoadException> { FileStdioTokenStore.load(path) }
        ex.message!! shouldContain "unreadable"
    }
})
