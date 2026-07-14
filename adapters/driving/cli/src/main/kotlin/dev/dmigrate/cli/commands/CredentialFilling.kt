package dev.dmigrate.cli.commands

import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionUrlParser

/**
 * Baut den CLI-`urlParser` für **Single-Connection**-Ops: `parse → Stufe 2 (Env) → Stufe 4 (Store)`.
 * Stufe 4 greift nur, wenn [rawSource] ein Connection-**Name** ist (kein Inline-`://`) — dann ist der
 * Store-Lookup-Key genau dieser Name. Inline-URLs und der Default-Fall (`--source` weggelassen) machen in
 * diesem Schnitt **keinen** Store-Lookup (Namensdurchreichung für `default_*` = Folgeschnitt). Dual-
 * Connection-Ops (`transfer`, `compare`) sind bewusst ausgenommen (teilen einen `urlParser` → brauchen die
 * per-Connection-Namensdurchreichung). Der **MCP-Pfad** nutzt diesen Baustein **nie**.
 */
internal class CredentialFilling(
    rawSource: String?,
    stderr: (String) -> Unit = { System.err.println(it) },
    env: (String) -> String? = System::getenv,
    sessionFactory: () -> CredentialFillSession = ::defaultFillSession,
) {
    private val envFiller = EnvCredentialFiller(env)
    private val storeFiller: StoreCredentialFiller? =
        rawSource?.takeUnless { it.contains("://") }?.let { StoreCredentialFiller(it, sessionFactory(), stderr) }

    /** `parse → Env(2) → Store(4)`. */
    fun parser(parse: (String) -> ConnectionConfig = ConnectionUrlParser::parse): (String) -> ConnectionConfig =
        { url ->
            val afterEnv = envFiller.fill(parse(url))
            storeFiller?.fill(afterEnv) ?: afterEnv
        }
}

private fun defaultFillSession(): CredentialFillSession = CredentialFillSession(
    masterSecretResolver = MasterSecretResolver(
        prompt = { System.console()?.readPassword("Master passphrase for credential store: ") },
    ),
    baseDir = defaultCredentialBaseDir(),
)
