@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package dev.dmigrate.cli.integration

import com.google.gson.JsonParser
import dev.dmigrate.mcp.server.McpServerConfig
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

/**
 * `mcp-real-e2e-scope-matrix.md` Teil A: beweist gegen den echten
 * CLI-Subprozess (`startRealCliSubprocess`, Produktionsverdrahtung über
 * `--stdio-token-file`), dass Scope-Enforcement für **jeden** Eintrag in
 * `McpServerConfig.DEFAULT_SCOPE_MAPPING` tatsächlich funktioniert — sowohl
 * für die sieben JSON-RPC-Protokollmethoden
 * (`ResponseErrorException`/`InvalidRequest`) als auch für die übrigen
 * Tool-Namen, die über den zentralen `tools/call`-Handler dispatchen
 * (`ToolsCallResult.isError`/`FORBIDDEN_PRINCIPAL`).
 *
 * Zwei Subprozess-Starts insgesamt (nicht einer pro Scope/Tool-Kombination,
 * AE-A4): der Voll-Scope-Prinzipal durchläuft die gesamte Matrix in einer
 * Session (Positiv-Fall — keine Scope-Ablehnung erwartet), der
 * Null-Scope-Prinzipal ebenso (Negativ-Fall — Scope-Ablehnung erwartet für
 * jeden Eintrag). Beide Prinzipale kommen aus [ScopeMatrixTokenFile]
 * (Datei-basiert, nicht `StubStdioTokenStore`/`IntegrationFixtures`, die am
 * In-Process-Harness hängen).
 *
 * Positiv-Fall-Sonderfall (AE-A2): die fünf `*_start`-Tools laufen ohne
 * `--policy-file` gegen `PolicyService`s fail-closed-Default
 * (`ConfiguredPolicyService.kt:10`) — ein `POLICY_DENIED`-Ergebnis dort ist
 * kein Testfehlschlag, sondern der Beleg, dass die Scope-Prüfung
 * durchgelassen hat (sie läuft strikt vor der Policy-Prüfung).
 */
class McpScopeEnforcementMatrixTest : FunSpec({

    test("full-scope principal is never scope-rejected across the whole DEFAULT_SCOPE_MAPPING") {
        runMatrix(fullScope = true) { name, response, expectedScope ->
            withClue("'$name' (needs '$expectedScope') must not be scope-rejected for the full-scope principal; response=$response") {
                isScopeDenied(name, response, expectedScope) shouldBe false
            }
        }
    }

    test("no-scope principal is scope-rejected for every DEFAULT_SCOPE_MAPPING entry") {
        runMatrix(fullScope = false) { name, response, expectedScope ->
            withClue("'$name' must be scope-rejected (needs '$expectedScope') for the no-scope principal; response=$response") {
                isScopeDenied(name, response, expectedScope) shouldBe true
            }
        }
    }
})

/** JSON-RPC-Protokollmethoden aus `DEFAULT_SCOPE_MAPPING` — alles andere ist ein `tools/call`-Tool-Name. */
private val PROTOCOL_METHODS = setOf(
    "tools/list",
    "resources/list",
    "resources/templates/list",
    "resources/read",
    "connections/list",
    "prompts/list",
    "prompts/get",
)

private const val STARTUP_TIMEOUT_MS = 15_000L

private fun runMatrix(fullScope: Boolean, assertEntry: (name: String, response: String, expectedScope: String) -> Unit) {
    val stateDir = Files.createTempDirectory("dmigrate-it-scope-matrix-state-")
    val tokenDir = Files.createTempDirectory("dmigrate-it-scope-matrix-tokens-")
    try {
        val tokens = ScopeMatrixTokenFile.write(tokenDir)
        val token = if (fullScope) tokens.fullScopeToken else tokens.noScopeToken
        val cli = startRealCliSubprocess(
            stateDir.toString(),
            extraArgs = listOf("--stdio-token-file", tokens.path.toString()),
            env = mapOf("DMIGRATE_MCP_STDIO_TOKEN" to token),
        )
        try {
            val ready = cli.awaitStderrLine(contains = "MCP stdio server started", timeoutMs = STARTUP_TIMEOUT_MS)
            withClue("real CLI subprocess must emit the documented startup line (stderr=${cli.stderrSnapshot()})") {
                ready shouldBe true
            }
            cli.requestResponse(
                """{"jsonrpc":"2.0","id":0,"method":"initialize","params":""" +
                    """{"protocolVersion":"2025-11-25",""" +
                    """"clientInfo":{"name":"scope-matrix","version":"0.0.0"},""" +
                    """"capabilities":{}}}""",
            )
            cli.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

            var id = 1
            for ((name, scopes) in McpServerConfig.DEFAULT_SCOPE_MAPPING) {
                val response = cli.requestResponse(requestFor(id, name))
                assertEntry(name, response, scopes.first())
                id++
            }
            cli.closeStdin()
        } finally {
            cli.killIfAlive()
        }
    } finally {
        stateDir.deleteRecursively()
        tokenDir.deleteRecursively()
    }
}

/**
 * Scope-Check läuft vor jeder argumentspezifischen Validierung — für
 * beide Dispatch-Pfade (`mcp-real-e2e-scope-matrix.md` Review-Bestätigung).
 * Minimale/leere Argumente reichen daher überall; nur `prompts/get`
 * (`name: String` ohne Default) braucht ein syntaktisch gültiges Feld.
 */
private fun requestFor(id: Int, name: String): String = when {
    name == "prompts/get" -> """{"jsonrpc":"2.0","id":$id,"method":"$name","params":{"name":""}}"""
    name in PROTOCOL_METHODS -> """{"jsonrpc":"2.0","id":$id,"method":"$name","params":{}}"""
    else -> """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name"}}"""
}

private fun isScopeDenied(name: String, responseLine: String, expectedScope: String): Boolean {
    val root = JsonParser.parseString(responseLine).asJsonObject
    return if (name in PROTOCOL_METHODS) {
        isProtocolScopeDenial(root, name, expectedScope)
    } else {
        isToolCallScopeDenial(root, expectedScope)
    }
}

private fun isProtocolScopeDenial(root: com.google.gson.JsonObject, name: String, expectedScope: String): Boolean {
    val error = root.getAsJsonObject("error") ?: return false
    if (error.get("code")?.asInt != JSON_RPC_INVALID_REQUEST) return false
    val message = error.get("message")?.asString ?: return false
    return message.contains("lacks required scope(s) for '$name'") && message.contains(expectedScope)
}

private fun isToolCallScopeDenial(root: com.google.gson.JsonObject, expectedScope: String): Boolean {
    val result = root.getAsJsonObject("result") ?: return false
    if (result.get("isError")?.asBoolean != true) return false
    val envelopeText = result.getAsJsonArray("content")?.firstOrNull()?.asJsonObject?.get("text")?.asString
        ?: return false
    val envelope = JsonParser.parseString(envelopeText).asJsonObject
    if (envelope.get("code")?.asString != "FORBIDDEN_PRINCIPAL") return false
    return envelope.getAsJsonArray("details")?.any { detail ->
        val obj = detail.asJsonObject
        obj.get("key")?.asString == "reason" &&
            obj.get("value")?.asString.orEmpty().let { it.contains("missing scope(s)") && it.contains(expectedScope) }
    } ?: false
}

private const val JSON_RPC_INVALID_REQUEST = -32600
