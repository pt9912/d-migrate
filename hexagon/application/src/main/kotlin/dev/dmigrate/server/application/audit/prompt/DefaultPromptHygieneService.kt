package dev.dmigrate.server.application.audit.prompt

import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.driver.connection.ConnectionSecretMasker
import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * LF-017 / LF-024 / LN-030 / LN-031— pattern-basierter Default-Hygiene-
 * Service.
 *
 * Pipeline pro [PromptHygieneRequest]:
 *
 * 1. Größenvalidierung — Prompt/Payload überschreitet Cap →
 *    [PromptHygieneBlockReason.PROMPT_TOO_LARGE] /
 *    [PromptHygieneBlockReason.PAYLOAD_TOO_LARGE].
 * 2. Secret-Pattern-Scan über Prompt **und** Payload — irgendein
 *    Match → [PromptHygieneBlockReason.SECRET_DETECTED] (oder
 *    [PromptHygieneBlockReason.PRIVATE_KEY_DETECTED] für PEM/SSH).
 * 3. Externe-URL-Scan — `http(s)://`-URLs außerhalb der
 *    `dmigrate://`-Whitelist im Prompt blocken
 *    ([PromptHygieneBlockReason.EXTERNAL_URL_DETECTED]).
 * 4. Resource-Ref-Whitelist — `dmigrate://`-URIs im Prompt müssen
 *    in [PromptHygieneRequest.allowedResourceRefs] stehen
 *    ([PromptHygieneBlockReason.UNAUTHORIZED_REF]).
 * 5. Bulk-Daten-Heuristik — viele Zeilen mit CSV-/SQL-Indikatoren →
 *    [PromptHygieneBlockReason.BULK_DATA_DETECTED].
 * 6. Allow → Prompt + Payload werden whitespace-normalisiert
 *    (CR/LF → LF, Trim) und über UTF-8 SHA-256 zu Fingerprints
 *    gehasht.
 *
 * LF-012 / LN-011 / LN-017 / LN-027 LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz: Fehlerdetails enthalten keine Secrets —
 * der `publicMessage`-Text in [PromptHygieneResult.Block] ist
 * generisch ("secret pattern detected"), die Pattern-Match-Werte
 * werden NIE ausgegeben.
 */
class DefaultPromptHygieneService : PromptHygieneService {

    override fun sanitize(request: PromptHygieneRequest): PromptHygieneResult {
        // 1. Größencheck.
        val promptBytes = request.promptText.toByteArray(Charsets.UTF_8)
        if (promptBytes.size > request.maxPromptBytes) {
            return block(
                PromptHygieneBlockReason.PROMPT_TOO_LARGE,
                "prompt exceeds the configured maxPromptBytes",
            )
        }
        val payloadBytes = request.payloadJson.toByteArray(Charsets.UTF_8)
        if (payloadBytes.size > request.maxPayloadBytes) {
            return block(
                PromptHygieneBlockReason.PAYLOAD_TOO_LARGE,
                "payload exceeds the configured maxPayloadBytes",
            )
        }

        // 2. Secret-Scan.
        val combined = request.promptText + "\n" + request.payloadJson
        val privateKeys = scanPrivateKeys(combined)
        if (privateKeys.isNotEmpty()) {
            return PromptHygieneResult.Block(
                reason = PromptHygieneBlockReason.PRIVATE_KEY_DETECTED,
                publicMessage = "private key material detected",
                detectedClasses = privateKeys,
            )
        }
        val secrets = scanSecrets(combined)
        if (secrets.isNotEmpty()) {
            return PromptHygieneResult.Block(
                reason = PromptHygieneBlockReason.SECRET_DETECTED,
                publicMessage = "secret pattern detected",
                detectedClasses = secrets,
            )
        }

        // 3. Externe-URL-Scan (nur im Prompt — Payload-JSON darf
        // URLs enthalten, etwa als String-Wert eines erlaubten
        // Optionsfeldes; der Prompt ist fact aber LLM-gerichtet).
        if (containsExternalUrl(request.promptText)) {
            return block(
                PromptHygieneBlockReason.EXTERNAL_URL_DETECTED,
                "non-dmigrate URL in prompt is not allowed",
            )
        }

        // 4. Resource-Ref-Whitelist.
        val unauthorizedRef = findUnauthorizedRef(request.promptText, request.allowedResourceRefs)
        if (unauthorizedRef != null) {
            return block(
                PromptHygieneBlockReason.UNAUTHORIZED_REF,
                "prompt references a resource that is not in the allowed set",
            )
        }

        // 5. Bulk-Daten-Heuristik.
        if (looksLikeBulkData(request.promptText)) {
            return block(
                PromptHygieneBlockReason.BULK_DATA_DETECTED,
                "prompt contains bulk data; use a resource reference instead",
            )
        }

        // 6. Allow + Fingerprints.
        val cleanPrompt = normalize(request.promptText)
        val cleanPayload = normalize(request.payloadJson)
        return PromptHygieneResult.Allow(
            sanitizedPrompt = cleanPrompt,
            sanitizedPayloadJson = cleanPayload,
            promptFingerprint = sha256Hex(cleanPrompt.toByteArray(Charsets.UTF_8)),
            payloadFingerprint = sha256Hex(cleanPayload.toByteArray(Charsets.UTF_8)),
            allowedRefs = request.allowedResourceRefs.toList(),
        )
    }

    private fun block(reason: PromptHygieneBlockReason, message: String): PromptHygieneResult.Block =
        PromptHygieneResult.Block(reason, message, emptySet())

    private fun scanPrivateKeys(text: String): Set<DetectedSecretClass> {
        val found = mutableSetOf<DetectedSecretClass>()
        if (PEM_PRIVATE_KEY.containsMatchIn(text)) found += DetectedSecretClass.PEM_PRIVATE_KEY
        if (SSH_PRIVATE_KEY.containsMatchIn(text)) found += DetectedSecretClass.SSH_PRIVATE_KEY
        return found
    }

    private fun scanSecrets(text: String): Set<DetectedSecretClass> {
        val found = mutableSetOf<DetectedSecretClass>()

        // LF-012 / LN-011 / LN-017 / LN-027: JDBC-/URL-Passwörter — ConnectionSecretMasker
        // ist die kanonische Maskierungs-Logik. Wenn `mask` den
        // Text verändert, wissen wir, dass mindestens ein Secret-
        // Pattern gematcht hat.
        val masked = ConnectionSecretMasker.mask(text)
        if (masked != text) {
            // Klasse anhand der getroffenen Pattern unterscheiden —
            // beide werden gleichbehandelt (Block), aber das Audit
            // braucht die feine Auflösung.
            if (URL_AUTHORITY_PASSWORD.containsMatchIn(text)) {
                found += DetectedSecretClass.JDBC_AUTHORITY_PASSWORD
            }
            if (QUERY_PARAM_PASSWORD.containsMatchIn(text)) {
                found += DetectedSecretClass.QUERY_PARAM_PASSWORD
            }
            if (QUERY_PARAM_API_KEY.containsMatchIn(text)) {
                found += DetectedSecretClass.QUERY_PARAM_API_KEY
            }
            if (QUERY_PARAM_TOKEN.containsMatchIn(text)) {
                found += DetectedSecretClass.QUERY_PARAM_TOKEN
            }
        }

        if (BEARER_TOKEN.containsMatchIn(text)) found += DetectedSecretClass.BEARER_TOKEN
        if (APPROVAL_TOKEN.containsMatchIn(text)) found += DetectedSecretClass.APPROVAL_TOKEN
        if (AWS_ACCESS_KEY.containsMatchIn(text)) found += DetectedSecretClass.AWS_ACCESS_KEY
        if (GENERIC_API_KEY_HINT.containsMatchIn(text)) {
            found += DetectedSecretClass.GENERIC_API_KEY_HINT
        }
        return found
    }

    private fun containsExternalUrl(text: String): Boolean =
        EXTERNAL_HTTP_URL.containsMatchIn(text)

    private fun findUnauthorizedRef(
        text: String,
        allowed: List<ServerResourceUri>,
    ): String? {
        val allowedRendered = allowed.map { it.render() }.toSet()
        val matches = DMIGRATE_REF.findAll(text)
        for (match in matches) {
            if (match.value !in allowedRendered) return match.value
        }
        return null
    }

    private fun looksLikeBulkData(text: String): Boolean {
        val newlineCount = text.count { it == '\n' }
        if (newlineCount < BULK_LINE_THRESHOLD) return false
        // Heuristisch: viele Zeilen + entweder CSV-typische
        // Komma-Dichte oder INSERT/VALUES-Häufigkeit.
        val csvLikeRows = text.lineSequence().count { line ->
            line.count { it == ',' } >= CSV_COMMA_THRESHOLD_PER_ROW
        }
        if (csvLikeRows >= BULK_LINE_THRESHOLD) return true
        val sqlInserts = INSERT_VALUES.findAll(text).count()
        return sqlInserts >= BULK_SQL_INSERT_THRESHOLD
    }

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').trim()

    private companion object {
        // Sub-Pattern aus ConnectionSecretMasker — wir nutzen sie
        // nur zur **Klassen-Identifikation**, das eigentliche
        // Block-Signal kommt aus `mask(text) != text`.
        val URL_AUTHORITY_PASSWORD = Regex(
            """(?<prefix>(?:jdbc:)?[a-zA-Z][a-zA-Z0-9+\-.]*://)(?<user>[^:/@?#]*):(?<pwd>[^@/?#]*)@""",
        )
        val QUERY_PARAM_PASSWORD = Regex("""(?i)[?&;](password|pwd|passwd|passphrase|sslpassword)=""")
        val QUERY_PARAM_API_KEY = Regex("""(?i)[?&;](api_key|api-key)=""")
        val QUERY_PARAM_TOKEN = Regex("""(?i)[?&;](token|access_token|access-token|secret)=""")

        // LF-012 / LN-011 / LN-017 / LN-027: zusätzliche Pattern.
        val BEARER_TOKEN = Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-]{8,}""")
        val APPROVAL_TOKEN = Regex("""\btok_[A-Za-z0-9_\-]{8,}""")
        val AWS_ACCESS_KEY = Regex("""\bAKIA[0-9A-Z]{16}\b""")
        val GENERIC_API_KEY_HINT = Regex(
            """(?i)\b(api[_-]?key|access[_-]?key|secret[_-]?key|client[_-]?secret)\s*[=:]\s*['"]?[A-Za-z0-9._\-]{16,}""",
        )

        val PEM_PRIVATE_KEY = Regex(
            """-----BEGIN (?:RSA |EC |DSA |ENCRYPTED |PGP )?PRIVATE KEY-----""",
        )
        val SSH_PRIVATE_KEY = Regex("""-----BEGIN OPENSSH PRIVATE KEY-----""")

        // Externe URLs — alles, was http(s):// sagt, aber kein
        // dmigrate://-Ref ist. LF-012 / LN-011 / LN-017 / LN-027.
        val EXTERNAL_HTTP_URL = Regex("""\bhttps?://[^\s"'<>]+""")
        val DMIGRATE_REF = Regex("""dmigrate://[^\s"'<>)]+""")

        // Bulk-Daten-Heuristik.
        const val BULK_LINE_THRESHOLD: Int = 50
        const val CSV_COMMA_THRESHOLD_PER_ROW: Int = 3
        const val BULK_SQL_INSERT_THRESHOLD: Int = 10
        val INSERT_VALUES = Regex("""(?i)\binsert\s+into\s+\w+[^;]*values\s*\(""")
    }
}
