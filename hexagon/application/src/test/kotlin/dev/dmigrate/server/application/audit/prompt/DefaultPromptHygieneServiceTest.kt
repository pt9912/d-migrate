package dev.dmigrate.server.application.audit.prompt

import dev.dmigrate.server.application.ai.AiProviderId
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Phase G § 5.3 + § 6 G.4 — Akzeptanztests für den Hygiene-Service.
 */
class DefaultPromptHygieneServiceTest : FunSpec({

    val tenant = TenantId("acme")
    val principal = PrincipalId("alice")
    val schemaRef = ServerResourceUri(tenant, ResourceKind.SCHEMAS, "warehouse-v1")

    val service = DefaultPromptHygieneService()

    fun request(
        prompt: String,
        payload: String = """{"foo":"bar"}""",
        allowedRefs: List<ServerResourceUri> = listOf(schemaRef),
        maxPromptBytes: Int = 32_768,
        maxPayloadBytes: Int = 16_384,
    ) = PromptHygieneRequest(
        toolName = "procedure_transform_plan",
        tenantId = tenant,
        principalId = principal,
        allowedResourceRefs = allowedRefs,
        payloadJson = payload,
        promptText = prompt,
        providerId = AiProviderId.NOOP,
        maxPromptBytes = maxPromptBytes,
        maxPayloadBytes = maxPayloadBytes,
    )

    test("Plan §6 G.4: harmlose Eingabe -> Allow + stabile Fingerprints") {
        val first = service.sanitize(request("Analyse procedure foo for ${schemaRef.render()}"))
        val second = service.sanitize(request("Analyse procedure foo for ${schemaRef.render()}"))
        first.shouldBeInstanceOf<PromptHygieneResult.Allow>()
        second.shouldBeInstanceOf<PromptHygieneResult.Allow>()
        second.promptFingerprint shouldBe first.promptFingerprint
        second.payloadFingerprint shouldBe first.payloadFingerprint
        first.allowedRefs shouldContainExactly listOf(schemaRef)
    }

    test("Plan §6 G.4: unterschiedliche Eingaben -> unterschiedliche Fingerprints") {
        val a = service.sanitize(request("alpha"))
        val b = service.sanitize(request("beta"))
        a.shouldBeInstanceOf<PromptHygieneResult.Allow>()
        b.shouldBeInstanceOf<PromptHygieneResult.Allow>()
        a.promptFingerprint shouldNotBe b.promptFingerprint
    }

    test("Plan §6 G.4: JDBC-URL mit Passwort wird blockiert") {
        val r = service.sanitize(
            request("connect via jdbc:postgresql://app:topsecret@db.internal/mydb"),
        )
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.SECRET_DETECTED
        block.detectedClasses shouldContain DetectedSecretClass.JDBC_AUTHORITY_PASSWORD
    }

    test("Plan §6 G.4: API-Key-Pattern wird blockiert (query-Param Form)") {
        val r = service.sanitize(
            request("contact endpoint at https://example.com/v1?api_key=secret123abc"),
        )
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        // Externer URL wird *vor* der API-Key-Klassifizierung
        // erkannt (Pipeline-Reihenfolge); beide Pfade sind Block.
        // Akzeptiere beide Reasons als korrekte Blockade.
        (block.reason in setOf(
            PromptHygieneBlockReason.SECRET_DETECTED,
            PromptHygieneBlockReason.EXTERNAL_URL_DETECTED,
        )) shouldBe true
    }

    test("Plan §6 G.4: AWS Access-Key-Pattern wird blockiert") {
        val r = service.sanitize(request("for the bucket use AKIA1234567890ABCDEF for auth"))
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.SECRET_DETECTED
        block.detectedClasses shouldContain DetectedSecretClass.AWS_ACCESS_KEY
    }

    test("Plan §6 G.4: Bearer-Token-Form wird blockiert") {
        val r = service.sanitize(request("call POST /v2 with header: Bearer abcdef0123456789"))
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.SECRET_DETECTED
        block.detectedClasses shouldContain DetectedSecretClass.BEARER_TOKEN
    }

    test("Plan §6 G.4: PEM-Privat-Schluessel wird als PRIVATE_KEY_DETECTED blockiert") {
        val r = service.sanitize(
            request("here is the key:\n-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END..."),
        )
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.PRIVATE_KEY_DETECTED
        block.detectedClasses shouldContain DetectedSecretClass.PEM_PRIVATE_KEY
    }

    test("Plan §6 G.4: OpenSSH-Privat-Schluessel wird als PRIVATE_KEY_DETECTED blockiert") {
        val r = service.sanitize(
            request("ssh key:\n-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNz..."),
        )
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.PRIVATE_KEY_DETECTED
        block.detectedClasses shouldContain DetectedSecretClass.SSH_PRIVATE_KEY
    }

    test("Plan §6 G.4: grosse SQL-INSERT-Bulk wird als BULK_DATA_DETECTED blockiert") {
        val rows = (1..100).joinToString("\n") { "INSERT INTO t (a,b,c) VALUES (1,2,3);" }
        val r = service.sanitize(request("execute these:\n$rows"))
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.BULK_DATA_DETECTED
    }

    test("Plan §6 G.4: grosse CSV-Bulk wird als BULK_DATA_DETECTED blockiert") {
        val rows = (1..80).joinToString("\n") { "id,name,age,role" }
        val r = service.sanitize(request("data:\n$rows"))
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.BULK_DATA_DETECTED
    }

    test("kleine Optionen mit kommas und Zeilen bleiben erlaubt") {
        // Genau unter den Heuristik-Schwellen (BULK_LINE_THRESHOLD=50).
        val small = (1..5).joinToString("\n") { "name=$it,role=admin" }
        val r = service.sanitize(request("settings:\n$small"))
        r.shouldBeInstanceOf<PromptHygieneResult.Allow>()
    }

    test("Plan §6 G.4: Prompt ueber maxPromptBytes -> PROMPT_TOO_LARGE") {
        val big = "A".repeat(2_048)
        val r = service.sanitize(request(big, maxPromptBytes = 1_024))
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.PROMPT_TOO_LARGE
    }

    test("Plan §6 G.4: Payload ueber maxPayloadBytes -> PAYLOAD_TOO_LARGE") {
        val payload = "{" + "\"k\":\"" + "x".repeat(2_048) + "\"}"
        val r = service.sanitize(request("ok", payload = payload, maxPayloadBytes = 1_024))
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.PAYLOAD_TOO_LARGE
    }

    test("Plan §4.6: dmigrate-Ref ausserhalb Whitelist -> UNAUTHORIZED_REF") {
        val foreign = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, "foreign-1").render()
        val r = service.sanitize(
            request(
                "compare ${schemaRef.render()} against $foreign",
                allowedRefs = listOf(schemaRef),
            ),
        )
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.UNAUTHORIZED_REF
    }

    test("Plan §4.6: externer http-URL im Prompt -> EXTERNAL_URL_DETECTED") {
        val r = service.sanitize(request("see https://docs.example.com/info for spec"))
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.reason shouldBe PromptHygieneBlockReason.EXTERNAL_URL_DETECTED
    }

    test("Plan §6 G.4 Akzeptanz: Block.publicMessage enthaelt KEINE Secrets") {
        val r = service.sanitize(
            request("the password is jdbc:postgresql://x:topsecret@db/d"),
        )
        val block = r.shouldBeInstanceOf<PromptHygieneResult.Block>()
        block.publicMessage shouldNotContain "topsecret"
        block.publicMessage shouldNotContain "jdbc"
        block.publicMessage shouldNotContain "postgresql"
    }

    test("Allow normalisiert CR/LF, sodass Fingerprints CR-LF-stabil sind") {
        val crlf = "line a\r\nline b\r\n"
        val lf = "line a\nline b\n"
        val a = service.sanitize(request(crlf))
        val b = service.sanitize(request(lf))
        a.shouldBeInstanceOf<PromptHygieneResult.Allow>()
        b.shouldBeInstanceOf<PromptHygieneResult.Allow>()
        a.promptFingerprint shouldBe b.promptFingerprint
        a.sanitizedPrompt shouldBe b.sanitizedPrompt
    }

    test("Konstruktor-Invarianten von PromptHygieneRequest greifen") {
        // Direkter Konstruktor-Test, kein Service-Call.
        val args = listOf(
            { request(prompt = "") },
            { request(prompt = " ") },
            { request(prompt = "ok", maxPromptBytes = 0) },
            { request(prompt = "ok", maxPayloadBytes = 0) },
            { request(prompt = "ok", payload = "") },
        )
        for (a in args) {
            try {
                a()
                error("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
})
